package md.apartmentlens.scraper.ref;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import md.apartmentlens.scraper.http.PoliteHttpClient;
import md.apartmentlens.scraper.normalize.address.StreetTypes;
import md.apartmentlens.scraper.text.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Imports street names from map.md's public street pages (listed in its sitemap): the Romanian and
 * Russian names and the other spellings map.md knows (its "tags"), with the street's line and village.
 * Each street is linked to the cadastre street it is, so the names count for the official street.
 *
 * <p>map.md publishes no licence for this data. It is kept as its own source ({@code mapmd}) so it can be
 * removed in one step: {@code delete from ref.street where source = 'mapmd'}.
 */
public class MapMdImporter {

    private static final Logger log = LoggerFactory.getLogger(MapMdImporter.class);
    static final String SOURCE = "mapmd";
    static final URI SITEMAP = URI.create("https://map.md/sitemaps/street-ro.xml");
    private static final Pattern LOC = Pattern.compile("<loc>https://map\\.md/ro/street/(\\d+)</loc>");
    private static final Pattern NEXT_DATA = Pattern.compile(
            "<script id=\"__NEXT_DATA__\" type=\"application/json\">(.*?)</script>", Pattern.DOTALL);

    private final PoliteHttpClient http;
    private final JsonMapper json;
    private final RefStore store;
    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final Clock clock;

    public MapMdImporter(PoliteHttpClient http, JsonMapper json, RefStore store, JdbcClient jdbc, TransactionTemplate tx,
                         Clock clock) {
        this.http = http;
        this.json = json;
        this.store = store;
        this.jdbc = jdbc;
        this.tx = tx;
        this.clock = clock;
    }

    /** One street page, reduced to what is stored. */
    record Page(String id, String type, String nameRo, String nameRu, List<String> tags, String geoJson) {
    }

    public Map<String, Integer> run() throws IOException {
        List<String> ids = new ArrayList<>();
        Matcher m = LOC.matcher(new String(http.get(SITEMAP, Map.of()).body(), StandardCharsets.UTF_8));
        while (m.find()) {
            ids.add(m.group(1));
        }
        log.info("map.md: {} street pages listed", ids.size());
        List<Page> pages = new ArrayList<>(ids.size());
        int failed = 0;
        for (String id : ids) {
            try {
                Page p = page(id);
                if (p != null) {
                    pages.add(p);
                }
            } catch (IOException | RuntimeException e) {
                failed++;
                log.warn("map.md street {}: {}", id, e.toString());
            }
            if (pages.size() % 500 == 0 && !pages.isEmpty()) {
                log.info("map.md: {} of {} street pages read", pages.size(), ids.size());
            }
        }
        if (failed > ids.size() / 20) {
            throw new IOException("map.md: " + failed + " of " + ids.size() + " pages failed; nothing stored");
        }
        tx.executeWithoutResult(s -> {
            store.clear(SOURCE);
            for (Page p : pages) {
                store.street(SOURCE, p.id(), null, p.type(), p.nameRo(), p.nameRu(), p.tags(), p.geoJson());
            }
            locateAndLink();
            store.finish(SOURCE, clock.instant());
        });
        Map<String, Integer> counts = store.counts(SOURCE);
        log.info("map.md imported: {} ({} pages failed)", counts, failed);
        return counts;
    }

    Page page(String id) throws IOException {
        String html = new String(http.get(URI.create("https://map.md/ro/street/" + id), Map.of()).body(),
                StandardCharsets.UTF_8);
        Matcher m = NEXT_DATA.matcher(html);
        if (!m.find()) {
            throw new IOException("no page data");
        }
        JsonNode street = json.readTree(m.group(1)).path("props").path("pageProps").path("street");
        if (!street.isObject()) {
            return null;
        }
        return parse(id, street);
    }

    Page parse(String id, JsonNode street) {
        StreetTypes.Split ro = StreetTypes.split(text(street.path("name_i18n").path("ro")));
        StreetTypes.Split ru = StreetTypes.split(text(street.path("name_i18n").path("ru")));
        if (ro.name() == null && ru.name() == null) {
            return null;
        }
        Set<String> tags = new LinkedHashSet<>();
        for (JsonNode t : street.path("tags")) {
            StreetTypes.Split s = StreetTypes.split(text(t));
            if (s.name() != null) {
                tags.add(s.name());
            }
        }
        String nameRo = ro.name() == null ? "" : ro.name();
        String nameRu = ru.name() == null ? "" : ru.name();
        tags.remove(nameRo);
        tags.remove(nameRu);
        String type = ro.type() != null ? ro.type() : ru.type() != null ? ru.type() : "street";
        JsonNode geometry = street.path("geo_json");
        String geoJson = geometry.isObject() ? json.writeValueAsString(geometry) : null;
        return new Page(id, type, nameRo, nameRu, List.copyOf(tags), geoJson);
    }

    /** The village of the nearest cadastre street within 300 m, and the street it is. */
    private void locateAndLink() {
        jdbc.sql("""
                        update ref.street s set locality = (
                            select c.locality from ref.street c
                            where c.source = 'cadastre' and st_dwithin(c.geom, s.geom, 300)
                            order by c.geom::geometry <-> s.geom::geometry limit 1)
                        where s.source = 'mapmd'
                        """).update();
        link();
    }

    /**
     * Links each map.md street to the street it is, from the cadastre or (where the cadastre has none, as on
     * the left bank) OpenStreetMap: it runs along that street for at least half its length (within 30 m, so
     * crossing streets do not count) with a similar Romanian or Russian name, or lies almost entirely along
     * exactly one street whatever its name (a translation). map.md's names then add to that street instead
     * of making a second one.
     *
     * @return the number of map.md streets linked
     */
    public int link() {
        jdbc.sql("update ref.street set same_as = null, link_basis = null where source = 'mapmd'").update();
        jdbc.sql("""
                        update ref.street m set same_as = x.target, link_basis = 'name'
                        from (
                            select m.id, (
                                select c.id from ref.street c
                                where c.source in ('cadastre', 'osm') and c.same_as is null
                                  and st_dwithin(c.geom, m.geom, 60)
                                  and greatest(
                                        case when m.name_ro <> '' and c.name_ro <> ''
                                             then similarity(lower(unaccent(c.name_ro)), lower(unaccent(m.name_ro))) else 0 end,
                                        case when m.name_ru <> '' then greatest(
                                             case when c.name_ru <> '' then similarity(lower(c.name_ru), lower(m.name_ru)) else 0 end,
                                             coalesce((select max(similarity(lower(o.name_ru), lower(m.name_ru)))
                                                       from ref.street o where o.same_as = c.id and o.name_ru <> ''), 0))
                                        else 0 end) >= 0.6
                                  and st_length(st_intersection(st_buffer(st_transform(c.geom::geometry, 4026), 30),
                                                                st_transform(m.geom::geometry, 4026)))
                                      >= 0.5 * st_length(st_transform(m.geom::geometry, 4026))
                                order by c.source = 'cadastre' desc, c.geom::geometry <-> m.geom::geometry
                                limit 1) as target
                            from ref.street m where m.source = 'mapmd'
                        ) x
                        where m.id = x.id and x.target is not null
                        """).update();
        // Same line, different names: a translation ("Победы" for Strada Biruinței, "Садовая" for Livezilor).
        // Linked only when the map.md street lies almost entirely along exactly one street.
        jdbc.sql("""
                        update ref.street m set same_as = x.target, link_basis = 'line'
                        from (
                            select m.id, min(c.id) as target
                            from ref.street m join ref.street c
                              on c.source in ('cadastre', 'osm') and c.same_as is null and st_dwithin(c.geom, m.geom, 20)
                            where m.source = 'mapmd' and m.same_as is null
                              and st_length(st_intersection(st_buffer(st_transform(c.geom::geometry, 4026), 20),
                                                            st_transform(m.geom::geometry, 4026)))
                                  >= 0.8 * st_length(st_transform(m.geom::geometry, 4026))
                              and st_length(st_transform(m.geom::geometry, 4026)) >= 50
                            group by m.id having count(*) = 1
                        ) x
                        where m.id = x.id
                        """).update();
        int linked = jdbc.sql("select count(*) from ref.street where source = 'mapmd' and same_as is not null")
                .query(Integer.class).single();
        log.info("map.md streets linked to the street they are: {}", linked);
        return linked;
    }

    private static String text(JsonNode n) {
        return n == null || n.isNull() || n.isMissingNode() ? null : Text.clean(n.asString());
    }
}
