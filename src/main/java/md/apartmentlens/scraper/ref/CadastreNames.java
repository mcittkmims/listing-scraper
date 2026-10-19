package md.apartmentlens.scraper.ref;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the cadastre's full names: "mun. Chișinău, com. Ciorescu, sat. Goian, str. Ichel" is the street
 * Ichel of the village Goian, in the commune Ciorescu of the municipality Chișinău. The cadastre writes
 * them consistently, with a short word for each kind of place and street.
 */
final class CadastreNames {

    /** A place in the chain: its kind (mun, or, com, sat, r-nul, uta, loc, ip) and name. */
    record Part(String kind, String name) {
    }

    /** The settlement a street or address is in, with the larger units around it. */
    record Locality(String fullName, String name, String kind, String commune, String district) {
    }

    record Street(String type, String name) {
    }

    private static final Pattern PLACE = Pattern.compile("^(mun|or|com|sat|s|loc|r-nul|UTA|ÎP)\\.?\\s+(.+)$");
    private static final Map<String, String> STREET_TYPES = Map.of(
            "str.", "street", "str-la", "lane", "bd.", "boulevard", "șos.", "highway", "dr.", "road",
            "fun.", "dead_end", "pas.", "passage", "p-ța", "square", "aleea", "alley");
    private static final Pattern STREET = Pattern.compile("^(str\\.|str-la|bd\\.|șos\\.|dr\\.|fun\\.|pas\\.|p-ța|aleea)\\s*(.+)$");
    /** National and regional roads ("dr. naț. R5. km 8") are not addresses. */
    private static final Pattern ROUTE = Pattern.compile("^(naț|r\\.|reg|loc\\.|km)|\\bkm\\s*\\d", Pattern.CASE_INSENSITIVE);

    private CadastreNames() {
    }

    static List<String> split(String fullName) {
        List<String> parts = new ArrayList<>();
        for (String p : fullName.split(",")) {
            String t = p.strip().replaceAll("\\s+", " ");
            if (!t.isEmpty()) {
                parts.add(t);
            }
        }
        return parts;
    }

    static Part place(String part) {
        Matcher m = PLACE.matcher(part);
        if (!m.matches()) {
            return null;
        }
        String kind = m.group(1).toLowerCase(java.util.Locale.ROOT);
        return new Part(kind.equals("s") ? "sat" : kind.equals("îp") ? "ip" : kind, m.group(2).strip());
    }

    /** The locality from the place parts of a full name (all parts that name a place). */
    static Locality locality(List<String> parts) {
        List<String> placeParts = new ArrayList<>();
        Part settlement = null;
        String commune = null;
        String district = null;
        int rank = Integer.MAX_VALUE;
        for (String p : parts) {
            Part place = place(p);
            if (place == null) {
                continue;
            }
            placeParts.add(p);
            switch (place.kind()) {
                case "r-nul", "uta" -> district = place.name();
                case "mun" -> {
                    if (district == null) {
                        district = place.name();
                    }
                }
                case "com" -> commune = place.name();
                default -> {
                }
            }
            int r = switch (place.kind()) {
                case "sat", "loc" -> 0;
                case "or" -> 1;
                case "com" -> 2;
                case "mun" -> 3;
                default -> Integer.MAX_VALUE; // districts and garden associations are not settlements
            };
            if (r <= rank) {
                rank = r;
                settlement = place;
            }
        }
        if (settlement == null) {
            return null;
        }
        return new Locality(String.join(", ", placeParts), settlement.name(), settlement.kind(), commune, district);
    }

    /** The street of the last part, or null when it is a road between places or not a street. */
    static Street street(String part) {
        Matcher m = STREET.matcher(part.strip());
        if (!m.matches()) {
            return null;
        }
        String name = m.group(2).strip();
        if (ROUTE.matcher(name).find()) {
            return null;
        }
        return new Street(STREET_TYPES.get(m.group(1)), name);
    }
}
