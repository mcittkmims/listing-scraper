package md.apartmentlens.scraper.ref;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import md.apartmentlens.scraper.normalize.address.StreetTypes;
import md.apartmentlens.scraper.source.GeoPoint;
import md.apartmentlens.scraper.text.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Turns the OpenStreetMap staging tables (loaded by database/osm) into {@code ref} streets and address
 * points. Road pieces with the same name are joined into one street per place; roads named after a route
 * or the places they connect are dropped. Each street is then linked to the same cadastre street when
 * their lines lie together and the names agree, so OpenStreetMap's Russian and old names count for it.
 */
public class OsmImporter {

    private static final Logger log = LoggerFactory.getLogger(OsmImporter.class);
    static final String SOURCE = "osm";
    /** Pieces of one street are within this distance of each other. */
    static final double CLUSTER_KM = 1.5;
    private static final Pattern ROUTE_NAME = Pattern.compile(
            "^[A-Z]{1,2}\\d|\\b(de acc?ess?|centura|ocolire|traseul)\\b|[–—]\\s*[A-Z]\\d", Pattern.CASE_INSENSITIVE);
    private static final Pattern ROUTE_CODE = Pattern.compile("^[A-Z]{1,2}\\d+$");
    private static final Set<String> SKIP = Set.of("track", "path", "footway", "cycleway", "steps", "bridleway",
            "proposed", "abandoned", "construction");

    private final JdbcClient jdbc;
    private final JdbcTemplate template;
    private final RefStore store;
    private final TransactionTemplate tx;
    private final Clock clock;

    public OsmImporter(JdbcClient jdbc, JdbcTemplate template, RefStore store, TransactionTemplate tx, Clock clock) {
        this.jdbc = jdbc;
        this.template = template;
        this.store = store;
        this.tx = tx;
        this.clock = clock;
    }

    /** One named road piece. */
    record Way(long id, String type, String nameRo, String nameRu, Set<String> aliases, GeoPoint center) {
    }

    /** Pieces joined into one street. */
    static final class Cluster {
        final List<Way> ways = new ArrayList<>();
        double lat;
        double lon;
        long streetId;
        double extentKm;

        GeoPoint center() {
            return new GeoPoint(lat, lon);
        }
    }

    public Map<String, Integer> run() {
        tx.executeWithoutResult(s -> load());
        Map<String, Integer> counts = store.counts(SOURCE);
        log.info("OpenStreetMap imported: {}", counts);
        return counts;
    }

    private void load() {
        store.clear(SOURCE);
        Set<String> places = placeKeys();
        Map<String, List<Way>> groups = new HashMap<>();
        int[] skipped = {0};
        template.query("""
                        select way_id, highway, name, name_ro, name_ru, old_name, old_name_ru, alt_name, alt_name_ru,
                               st_y(st_centroid(geom)), st_x(st_centroid(geom))
                        from ref.osm_way
                        """, rs -> {
            if (SKIP.contains(rs.getString(2))) {
                return;
            }
            Way w = way(rs.getLong(1), rs.getString(3), rs.getString(4), rs.getString(5),
                    List.of(nz(rs.getString(6)), nz(rs.getString(8))), List.of(nz(rs.getString(7)), nz(rs.getString(9))),
                    new GeoPoint(rs.getDouble(10), rs.getDouble(11)), places);
            if (w == null) {
                skipped[0]++;
                return;
            }
            groups.computeIfAbsent(w.type() + "|" + Text.fold(w.nameRo().isEmpty() ? w.nameRu() : w.nameRo()),
                    k -> new ArrayList<>()).add(w);
        });

        Map<String, List<Cluster>> byKey = new HashMap<>();
        int streets = 0;
        for (Map.Entry<String, List<Way>> g : groups.entrySet()) {
            for (Cluster c : cluster(g.getValue())) {
                Way first = c.ways.getFirst();
                String ro = mostCommon(c.ways.stream().map(Way::nameRo).toList());
                String ru = mostCommon(c.ways.stream().map(Way::nameRu).toList());
                Set<String> aliases = new LinkedHashSet<>();
                for (Way w : c.ways) {
                    aliases.addAll(w.aliases());
                    if (!w.nameRo().isEmpty() && !w.nameRo().equals(ro)) {
                        aliases.add(w.nameRo());
                    }
                    if (!w.nameRu().isEmpty() && !w.nameRu().equals(ru)) {
                        aliases.add(w.nameRu());
                    }
                }
                aliases.remove(ro);
                aliases.remove(ru);
                aliases.remove("");
                long[] ids = c.ways.stream().mapToLong(Way::id).toArray();
                c.streetId = jdbc.sql("""
                                insert into ref.street (source, source_id, type, name_ro, name_ru, aliases, geom)
                                select 'osm', ?, ?, ?, ?, ?, st_multi(st_linemerge(st_union(geom)))::geography
                                from ref.osm_way where way_id = any(?) returning id
                                """)
                        .params("w" + Arrays.stream(ids).min().orElseThrow(), first.type(), ro, ru,
                                aliases.toArray(new String[0]), Arrays.stream(ids).boxed().toArray(Long[]::new))
                        .query(Long.class).single();
                byKey.computeIfAbsent(g.getKey().substring(g.getKey().indexOf('|') + 1), k -> new ArrayList<>()).add(c);
                streets++;
            }
        }
        log.info("OpenStreetMap streets: {} from {} named roads ({} roads between places or routes left out)",
                streets, groups.values().stream().mapToInt(List::size).sum(), skipped[0]);

        locateStreets();
        int linked = linkToCadastre();
        log.info("OpenStreetMap streets that are a cadastre street: {}", linked);

        List<RefStore.Address> batch = new ArrayList<>(10_000);
        int[] counts = {0, 0};
        template.query("select street, housenumber, st_y(geom), st_x(geom) from ref.osm_address", rs -> {
            String raw = Text.clean(rs.getString(1));
            String number = Text.clean(rs.getString(2));
            if (raw == null || number == null) {
                return;
            }
            GeoPoint p = new GeoPoint(rs.getDouble(3), rs.getDouble(4));
            StreetTypes.Split split = StreetTypes.split(raw);
            Long streetId = null;
            if (split.name() != null) {
                Cluster best = null;
                double bestKm = 2;
                for (Cluster c : byKey.getOrDefault(Text.fold(split.name()), List.of())) {
                    double km = c.center().kmTo(p) - c.extentKm;
                    if (km < bestKm) {
                        bestKm = km;
                        best = c;
                    }
                }
                streetId = best == null ? null : best.streetId;
            }
            counts[0]++;
            if (streetId != null) {
                counts[1]++;
            }
            batch.add(new RefStore.Address(streetId, null, raw, number, p.lat(), p.lon()));
            if (batch.size() == 10_000) {
                store.addresses(SOURCE, new ArrayList<>(batch));
                batch.clear();
            }
        });
        store.addresses(SOURCE, batch);
        jdbc.sql("""
                        update ref.address_point a set locality = s.locality from ref.street s
                        where a.source = 'osm' and a.street_id = s.id
                        """).update();
        log.info("OpenStreetMap addresses: {} stored, {} linked to their street", counts[0], counts[1]);
        store.finish(SOURCE, clock.instant());
    }

    /** A road piece with its names cleaned, or null for roads that are not addresses. */
    static Way way(long id, String name, String nameRo, String nameRu, List<String> oldAlt, List<String> oldAltRu,
                   GeoPoint center, Set<String> places) {
        String ro = Text.clean(nameRo != null ? nameRo : name);
        String ruOnly = null;
        if (ro == null || (Text.hasCyrillic(ro) && nameRo == null)) {
            ruOnly = Text.clean(name);
            ro = null;
        }
        String main = ro != null ? ro : ruOnly;
        if (main == null || ROUTE_NAME.matcher(main).find()) {
            return null;
        }
        StreetTypes.Split roSplit = ro == null ? new StreetTypes.Split(null, null) : StreetTypes.split(ro);
        StreetTypes.Split ruSplit = StreetTypes.split(nameRu != null ? nameRu : ruOnly);
        String nameKeyText = roSplit.name() != null ? roSplit.name() : ruSplit.name();
        if (nameKeyText == null || isConnector(nameKeyText, places)) {
            return null;
        }
        Set<String> aliases = new LinkedHashSet<>();
        for (String v : oldAlt) {
            for (String part : v.split(";")) {
                StreetTypes.Split s = StreetTypes.split(part);
                if (s.name() != null) {
                    aliases.add(s.name());
                }
            }
        }
        for (String v : oldAltRu) {
            for (String part : v.split(";")) {
                StreetTypes.Split s = StreetTypes.split(part);
                if (s.name() != null) {
                    aliases.add(s.name());
                }
            }
        }
        String type = roSplit.type() != null ? roSplit.type() : ruSplit.type() != null ? ruSplit.type() : "street";
        return new Way(id, type, roSplit.name() == null ? "" : roSplit.name(), ruSplit.name() == null ? "" : ruSplit.name(),
                aliases, center);
    }

    /** "Călinești-Fălești", "Dănceni-R3": every part a place or a route code. */
    static boolean isConnector(String name, Set<String> places) {
        String[] parts = name.split("\\s*[-–—]\\s*");
        if (parts.length < 2) {
            return false;
        }
        for (String p : parts) {
            if (!places.contains(Text.fold(p)) && !ROUTE_CODE.matcher(p.strip()).matches()) {
                return false;
            }
        }
        return true;
    }

    /** Joins road pieces lying within {@value #CLUSTER_KM} km of another piece of the same street. */
    static List<Cluster> cluster(List<Way> ways) {
        int n = ways.size();
        int[] parent = new int[n];
        for (int i = 0; i < n; i++) {
            parent[i] = i;
        }
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                if (ways.get(i).center().kmTo(ways.get(j).center()) < CLUSTER_KM) {
                    parent[find(parent, i)] = find(parent, j);
                }
            }
        }
        Map<Integer, Cluster> clusters = new HashMap<>();
        for (int i = 0; i < n; i++) {
            Cluster c = clusters.computeIfAbsent(find(parent, i), k -> new Cluster());
            c.ways.add(ways.get(i));
        }
        for (Cluster c : clusters.values()) {
            c.lat = c.ways.stream().mapToDouble(w -> w.center().lat()).average().orElseThrow();
            c.lon = c.ways.stream().mapToDouble(w -> w.center().lon()).average().orElseThrow();
            c.extentKm = c.ways.stream().mapToDouble(w -> w.center().kmTo(c.center())).max().orElse(0);
        }
        return new ArrayList<>(clusters.values());
    }

    private static int find(int[] parent, int i) {
        while (parent[i] != i) {
            parent[i] = parent[parent[i]];
            i = parent[i];
        }
        return i;
    }

    /** Each OSM street gets the locality of the nearest cadastre street within 300 m, else of the nearest locality. */
    private void locateStreets() {
        jdbc.sql("""
                        update ref.street s set locality = (
                            select c.locality from ref.street c
                            where c.source = 'cadastre' and st_dwithin(c.geom, s.geom, 300)
                            order by c.geom::geometry <-> s.geom::geometry limit 1)
                        where s.source = 'osm'
                        """).update();
        jdbc.sql("""
                        update ref.street s set locality = (
                            select l.full_name from ref.locality l
                            where l.source = 'cadastre' and l.point is not null and st_dwithin(l.point, s.geom, 5000)
                            order by l.point::geometry <-> s.geom::geometry limit 1)
                        where s.source = 'osm' and s.locality is null
                        """).update();
    }

    /**
     * Links an OSM street to the cadastre street it is: their lines within 60 m of each other and the
     * same name (ignoring case and diacritics) or a very similar one (trigram similarity of at least 0.6).
     */
    private int linkToCadastre() {
        jdbc.sql("""
                        update ref.street o set same_as = (
                            select c.id from ref.street c
                            where c.source = 'cadastre' and st_dwithin(c.geom, o.geom, 60)
                              and (lower(unaccent(c.name_ro)) = lower(unaccent(o.name_ro))
                                   or similarity(lower(unaccent(c.name_ro)), lower(unaccent(o.name_ro))) >= 0.6)
                            order by similarity(lower(unaccent(c.name_ro)), lower(unaccent(o.name_ro))) desc,
                                     c.geom::geometry <-> o.geom::geometry
                            limit 1)
                        where o.source = 'osm' and o.name_ro <> ''
                        """).update();
        return jdbc.sql("select count(*) from ref.street where source = 'osm' and same_as is not null")
                .query(Integer.class).single();
    }

    private Set<String> placeKeys() {
        Set<String> keys = new HashSet<>();
        jdbc.sql("select name from ref.locality union select name from ref.osm_place union "
                        + "select name_ro from ref.osm_place where name_ro is not null")
                .query(String.class).list().forEach(n -> keys.add(Text.fold(n)));
        return keys;
    }

    private static String mostCommon(List<String> values) {
        Map<String, Integer> counts = new HashMap<>();
        for (String v : values) {
            if (!v.isEmpty()) {
                counts.merge(v, 1, Integer::sum);
            }
        }
        String best = "";
        int bestCount = 0;
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            if (e.getValue() > bestCount || (e.getValue() == bestCount && e.getKey().compareTo(best) < 0)) {
                best = e.getKey();
                bestCount = e.getValue();
            }
        }
        return best;
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
