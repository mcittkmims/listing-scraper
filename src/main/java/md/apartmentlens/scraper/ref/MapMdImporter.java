package md.apartmentlens.scraper.ref;

import java.io.IOException;
import java.net.URI;
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
        Matcher m = LOC.matcher(new String(http.get(SITEMAP, Map.of()).body(), java.nio.charset.StandardCharsets.UTF_8));
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
                java.nio.charset.StandardCharsets.UTF_8);
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

    /** The village of the nearest cadastre street within 300 m, and the cadastre street it is. */
    private void locateAndLink() {
        jdbc.sql("""
                        update ref.street s set locality = (
                            select c.locality from ref.street c
                            where c.source = 'cadastre' and st_dwithin(c.geom, s.geom, 300)
                            order by c.geom::geometry <-> s.geom::geometry limit 1)
                        where s.source = 'mapmd'
                        """).update();
        jdbc.sql("""
                        update ref.street o set same_as = (
                            select c.id from ref.street c
                            where c.source = 'cadastre' and st_dwithin(c.geom, o.geom, 60)
                              and (lower(unaccent(c.name_ro)) = lower(unaccent(o.name_ro))
                                   or similarity(lower(unaccent(c.name_ro)), lower(unaccent(o.name_ro))) >= 0.6)
                            order by similarity(lower(unaccent(c.name_ro)), lower(unaccent(o.name_ro))) desc,
                                     c.geom::geometry <-> o.geom::geometry
                            limit 1)
                        where o.source = 'mapmd' and o.name_ro <> ''
                        """).update();
    }

    private static String text(JsonNode n) {
        return n == null || n.isNull() || n.isMissingNode() ? null : Text.clean(n.asString());
    }
}
