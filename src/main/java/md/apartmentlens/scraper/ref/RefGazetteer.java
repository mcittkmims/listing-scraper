package md.apartmentlens.scraper.ref;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPOutputStream;
import md.apartmentlens.scraper.normalize.address.AddressIndex;
import md.apartmentlens.scraper.normalize.address.HouseNumbers;
import md.apartmentlens.scraper.normalize.address.PlaceGazetteer;
import md.apartmentlens.scraper.normalize.address.PlaceGazetteer.Place;
import md.apartmentlens.scraper.normalize.address.Polyline;
import md.apartmentlens.scraper.normalize.address.StreetGazetteer;
import md.apartmentlens.scraper.normalize.address.StreetGazetteer.Street;
import md.apartmentlens.scraper.normalize.address.StreetTypes;
import md.apartmentlens.scraper.source.GeoPoint;
import md.apartmentlens.scraper.text.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Builds the normalizer's street and place lists from the {@code ref} tables: each cadastre street with
 * the names of the same street in OpenStreetMap and map.md, plus the streets only those sources know;
 * every address point on its street; and the settlements and city districts. Can also write them to the
 * gazetteer files that tests and an empty database fall back on.
 */
public final class RefGazetteer {

    private static final Logger log = LoggerFactory.getLogger(RefGazetteer.class);

    /** The gazetteers built from one read of the tables. */
    public record Loaded(StreetGazetteer streets, PlaceGazetteer places) {
    }

    private final JdbcTemplate jdbc;

    public RefGazetteer(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** True when the cadastre has been imported, so the tables can replace the bundled files. */
    public boolean available() {
        Integer n = jdbc.queryForObject("select count(*) from ref.street where source = 'cadastre'", Integer.class);
        return n != null && n > 0;
    }

    /** Everything in the tables, for the running scraper. */
    public Loaded load() {
        return load(true);
    }

    /**
     * @param withMapMd false leaves out the map.md names, as for the files committed to the public repository:
     *                  map.md publishes no licence, so its data is not republished
     */
    public Loaded load(boolean withMapMd) {
        String sources = withMapMd ? "('cadastre', 'osm', 'mapmd')" : "('cadastre', 'osm')";
        long start = System.nanoTime();
        // Names of the same street in other sources, by the cadastre street they belong to.
        Map<Long, List<String[]>> linked = new HashMap<>();
        // Names linked only by the line (translations, old names): kept apart, used only at the seller's pin.
        Map<Long, Set<String>> pinNames = new HashMap<>();
        jdbc.query("select same_as, source, name_ro, name_ru, aliases, link_basis from ref.street where same_as is not null"
                + " and source in " + sources, rs -> {
            String[] aliases = (String[]) rs.getArray(5).getArray();
            if ("line".equals(rs.getString(6))) {
                Set<String> names = pinNames.computeIfAbsent(rs.getLong(1), k -> new LinkedHashSet<>());
                names.add(rs.getString(3));
                names.add(rs.getString(4));
                names.addAll(Arrays.asList(aliases));
                names.remove("");
                return;
            }
            String[] row = new String[3 + aliases.length];
            row[0] = rs.getString(2);
            row[1] = rs.getString(3);
            row[2] = rs.getString(4);
            System.arraycopy(aliases, 0, row, 3, aliases.length);
            linked.computeIfAbsent(rs.getLong(1), k -> new ArrayList<>()).add(row);
        });

        List<Street> streets = new ArrayList<>();
        Map<Long, Integer> index = new HashMap<>();
        jdbc.query("""
                        select s.id, s.source, s.type, s.name_ro, s.name_ru, s.aliases, coalesce(l.name, ''),
                               st_astext(st_simplify(s.geom::geometry, 0.00003))
                        from ref.street s left join ref.locality l on l.source = 'cadastre' and l.full_name = s.locality
                        where s.same_as is null and s.geom is not null and s.source in %s
                          -- a map.md street that is not linked counts only if it is new: no street with a
                          -- similar name within 300 m (else it is a second spelling that did not link)
                          and (s.source <> 'mapmd' or not exists (
                                select 1 from ref.street c
                                where c.source in ('cadastre', 'osm') and c.same_as is null
                                  and st_dwithin(c.geom, s.geom, 300)
                                  and greatest(
                                        case when s.name_ro <> '' and c.name_ro <> ''
                                             then similarity(lower(unaccent(c.name_ro)), lower(unaccent(s.name_ro))) else 0 end,
                                        case when s.name_ru <> '' and c.name_ru <> ''
                                             then similarity(lower(c.name_ru), lower(s.name_ru)) else 0 end) >= 0.4))
                        order by s.id
                        """.formatted(sources), rs -> {
            long id = rs.getLong(1);
            String type = rs.getString(3);
            String nameRo = rs.getString(4);
            String nameRu = rs.getString(5);
            Set<String> aliases = new LinkedHashSet<>(Arrays.asList((String[]) rs.getArray(6).getArray()));
            // "Calea Orheiului", "Drumul Chișinăului": the cadastre keeps such type words in the name.
            StreetTypes.Split split = StreetTypes.split(nameRo);
            if (split.type() != null && split.name() != null && !split.type().equals("street")) {
                aliases.add(nameRo);
                type = split.type();
                nameRo = split.name();
            }
            for (String[] other : linked.getOrDefault(id, List.of())) {
                if (nameRu.isEmpty() && !other[2].isEmpty() && other[0].equals("osm")) {
                    nameRu = other[2];
                }
                for (int i = 1; i < other.length; i++) {
                    aliases.add(other[i]);
                }
            }
            if (nameRu.isEmpty()) {
                for (String[] other : linked.getOrDefault(id, List.of())) {
                    if (!other[2].isEmpty()) {
                        nameRu = other[2];
                        break;
                    }
                }
            }
            aliases.remove(nameRo);
            aliases.remove(nameRu);
            aliases.remove("");
            float[][] line = lines(rs.getString(8));
            GeoPoint center = center(line);
            index.put(id, streets.size());
            Set<String> atPin = new LinkedHashSet<>(pinNames.getOrDefault(id, Set.of()));
            atPin.removeAll(aliases);
            atPin.remove(nameRo);
            atPin.remove(nameRu);
            streets.add(StreetGazetteer.street(type, nameRo, nameRu, List.copyOf(aliases), center, extent(line, center),
                    streets.size(), rs.getString(7), line, List.copyOf(atPin)));
        });

        // Address points on their (cadastre) street, the cadastre's first where both have the number.
        int[] street = new int[2_000_000];
        String[] number = new String[street.length];
        float[] lat = new float[street.length];
        float[] lon = new float[street.length];
        int[] n = {0};
        Map<String, String> clean = new HashMap<>();
        Set<String> seen = new java.util.HashSet<>();
        jdbc.query("""
                        select coalesce(s.same_as, s.id), a.number, st_y(a.geom::geometry), st_x(a.geom::geometry)
                        from ref.address_point a join ref.street s on s.id = a.street_id
                        order by coalesce(s.same_as, s.id), a.source = 'cadastre' desc
                        """, rs -> {
            Integer i = index.get(rs.getLong(1));
            if (i == null) {
                return;
            }
            String value = clean.computeIfAbsent(rs.getString(2), k -> {
                HouseNumbers.Result h = HouseNumbers.parse(k);
                return h.kind() == HouseNumbers.Kind.NUMBER ? h.value() : "";
            });
            if (value.isEmpty() || !seen.add(i + "|" + value)) {
                return;
            }
            if (n[0] == street.length) {
                throw new IllegalStateException("more than " + street.length + " address points");
            }
            street[n[0]] = i;
            number[n[0]] = value;
            lat[n[0]] = rs.getFloat(3);
            lon[n[0]] = rs.getFloat(4);
            n[0]++;
        });
        AddressIndex addresses = AddressIndex.of(Arrays.copyOf(street, n[0]), Arrays.copyOf(number, n[0]),
                Arrays.copyOf(lat, n[0]), Arrays.copyOf(lon, n[0]), streets.size());

        PlaceGazetteer places = PlaceGazetteer.of(places());
        log.info("gazetteer from the ref tables: {} streets, {} address points, {} places, in {} ms", streets.size(),
                addresses.size(), places.places().size(), (System.nanoTime() - start) / 1_000_000);
        return new Loaded(StreetGazetteer.of(streets, addresses), places);
    }

    /**
     * Settlements of the cadastre, with the Russian name and population of the same place in OpenStreetMap;
     * settlements only OpenStreetMap has (left bank of the Dniester); and city districts.
     */
    private List<Place> places() {
        List<Place> out = new ArrayList<>();
        jdbc.query("""
                        select l.name, coalesce(o.name_ru, ''), l.kind, st_y(l.point::geometry), st_x(l.point::geometry),
                               coalesce(nullif(regexp_replace(o.population, '\\D', '', 'g'), '')::int, 0)
                        from ref.locality l
                        left join lateral (select p.name_ru, p.population from ref.osm_place p
                                           where p.place in ('city', 'town', 'village', 'hamlet')
                                             and lower(unaccent(coalesce(p.name_ro, p.name))) = lower(unaccent(l.name))
                                             and st_dwithin(p.geom::geography, l.point, 8000)
                                           order by p.geom <-> l.point::geometry limit 1) o on true
                        where l.source = 'cadastre' and l.point is not null
                        """, rs -> {
            String type = switch (rs.getString(3)) {
                case "mun" -> "city";
                case "or" -> "town";
                default -> "village";
            };
            out.add(new Place(rs.getString(1), rs.getString(2), type, new GeoPoint(rs.getDouble(4), rs.getDouble(5)),
                    rs.getInt(6)));
        });
        jdbc.query("""
                        select coalesce(p.name_ro, p.name), coalesce(p.name_ru, ''), p.place, st_y(p.geom), st_x(p.geom),
                               coalesce(nullif(regexp_replace(p.population, '\\D', '', 'g'), '')::int, 0)
                        from ref.osm_place p
                        where p.place in ('suburb', 'quarter', 'neighbourhood')
                           or (p.place in ('city', 'town', 'village', 'hamlet') and not exists (
                                   select 1 from ref.locality l where l.source = 'cadastre'
                                     and lower(unaccent(l.name)) = lower(unaccent(coalesce(p.name_ro, p.name)))
                                     and st_dwithin(l.point, p.geom::geography, 8000)))
                        """, (org.springframework.jdbc.core.RowCallbackHandler) rs -> out.add(new Place(
                Text.fixDiacritics(rs.getString(1)), rs.getString(2), rs.getString(3),
                new GeoPoint(rs.getDouble(4), rs.getDouble(5)), rs.getInt(6))));
        return out;
    }

    /** Writes the gazetteer files the normalizer falls back on (src/main/resources/gazetteer). */
    public static void export(Loaded g, Path dir) throws IOException {
        Files.createDirectories(dir);
        String credit = "Cadastre of the Republic of Moldova (geodata.gov.md); © OpenStreetMap contributors, ODbL";
        try (Writer w = gzipWriter(dir.resolve("md-streets.tsv.gz"))) {
            w.write("# Streets of Moldova. " + credit + ".\n");
            w.write("type\tname_ro\tname_ru\taliases\tlat\tlon\textent_km\tways\tcity\tline\tpin_names\n");
            for (Street s : g.streets().streets()) {
                w.write(String.join("\t", s.type(), s.nameRo(), s.nameRu(), String.join("|", aliasesOf(s)),
                        String.format(java.util.Locale.ROOT, "%.6f", s.point().lat()),
                        String.format(java.util.Locale.ROOT, "%.6f", s.point().lon()),
                        String.format(java.util.Locale.ROOT, "%.2f", s.extentKm()), "0", s.city(), Polyline.encode(s.line()),
                        String.join("|", s.pinNames())));
                w.write('\n');
            }
        }
        try (Writer w = gzipWriter(dir.resolve("md-addresses.tsv.gz"))) {
            w.write("# House numbers of Moldova; street = row of md-streets.tsv.gz (0-based, after the header). "
                    + credit + ".\n");
            w.write("street\tnumber\tlat\tlon\n");
            g.streets().addressIndex().forEach((street, number, lat, lon) -> {
                try {
                    w.write(street + "\t" + number + "\t" + String.format(java.util.Locale.ROOT, "%.5f\t%.5f", lat, lon) + "\n");
                } catch (IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
            });
        }
        try (Writer w = Files.newBufferedWriter(dir.resolve("md-places.tsv"), StandardCharsets.UTF_8)) {
            w.write("# Settlements and city districts of Moldova. " + credit + ".\n");
            w.write("name_ro\tname_ru\tplace\tlat\tlon\tpopulation\n");
            for (Place p : g.places().places()) {
                w.write(String.join("\t", p.nameRo(), p.nameRu(), p.type(),
                        String.format(java.util.Locale.ROOT, "%.7f", p.point().lat()),
                        String.format(java.util.Locale.ROOT, "%.7f", p.point().lon()),
                        p.population() == 0 ? "" : String.valueOf(p.population())) + "\n");
            }
        }
    }

    /** The original spellings kept for a street: what StreetGazetteer.street folded into its alias keys. */
    private static List<String> aliasesOf(Street s) {
        return s.aliasNames();
    }

    private static Writer gzipWriter(Path file) throws IOException {
        return new OutputStreamWriter(new GZIPOutputStream(Files.newOutputStream(file)), StandardCharsets.UTF_8);
    }

    /** "MULTILINESTRING((x y,x y),(x y,...))" as parts of lat, lon pairs. */
    static float[][] lines(String wkt) {
        if (wkt == null || !wkt.contains("(")) {
            return new float[0][];
        }
        String body = wkt.substring(wkt.indexOf('(') + 1, wkt.lastIndexOf(')'));
        List<float[]> parts = new ArrayList<>();
        for (String part : body.split("\\)\\s*,\\s*\\(")) {
            String[] pts = part.replace("(", "").replace(")", "").split(",");
            float[] out = new float[pts.length * 2];
            for (int i = 0; i < pts.length; i++) {
                String[] xy = pts[i].strip().split("\\s+");
                out[2 * i] = Float.parseFloat(xy[1]);
                out[2 * i + 1] = Float.parseFloat(xy[0]);
            }
            parts.add(out);
        }
        return parts.toArray(new float[0][]);
    }

    static GeoPoint center(float[][] line) {
        double lat = 0;
        double lon = 0;
        int n = 0;
        for (float[] part : line) {
            for (int i = 0; i + 1 < part.length; i += 2) {
                lat += part[i];
                lon += part[i + 1];
                n++;
            }
        }
        return n == 0 ? new GeoPoint(0, 0) : new GeoPoint(lat / n, lon / n);
    }

    static double extent(float[][] line, GeoPoint center) {
        double max = 0;
        for (float[] part : line) {
            for (int i = 0; i + 1 < part.length; i += 2) {
                max = Math.max(max, center.kmTo(new GeoPoint(part[i], part[i + 1])));
            }
        }
        return max;
    }
}
