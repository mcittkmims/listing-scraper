package md.apartmentlens.scraper.normalize.address;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import md.apartmentlens.scraper.source.GeoPoint;
import md.apartmentlens.scraper.text.Text;

/**
 * Settlements and city districts of Moldova, from OpenStreetMap (see tools/build_gazetteer.py).
 * Used to find where an ad's locality is, so streets are looked up only nearby.
 */
public final class PlaceGazetteer {

    private static final Pattern REGION_WORDS = Pattern.compile(
            "\\b(mun|municipiul|r nul|raionul|raion|or|orasul|s|satul|com|comuna|uta|р н|район|мун|г)\\b");

    public record Place(String nameRo, String nameRu, String type, GeoPoint point, int population) {

        public boolean isSettlement() {
            return switch (type) {
                case "city", "town", "village", "hamlet" -> true;
                default -> false;
            };
        }

        /** How far from the place's centre its streets can be. */
        public double radiusKm() {
            return switch (type) {
                case "city" -> population > 300_000 ? 14 : 8;
                case "town" -> 5;
                case "village" -> 3.5;
                case "hamlet" -> 2;
                default -> 2.5; // districts
            };
        }

        int rank() {
            return switch (type) {
                case "city" -> 0;
                case "town" -> 1;
                case "village" -> 2;
                case "hamlet" -> 3;
                default -> 4;
            };
        }
    }

    private final Map<String, List<Place>> settlements = new HashMap<>();
    private final List<Place> allSettlements = new ArrayList<>();
    private final Map<String, List<Place>> districts = new HashMap<>();

    PlaceGazetteer(List<Place> places) {
        for (Place p : places) {
            if (p.isSettlement()) {
                allSettlements.add(p);
            }
            Map<String, List<Place>> index = p.isSettlement() ? settlements : districts;
            for (String name : new String[] {p.nameRo(), p.nameRu()}) {
                if (name != null && !name.isEmpty()) {
                    index.computeIfAbsent(Text.fold(name), k -> new ArrayList<>()).add(p);
                    // Î and Â fold to "a", but written without diacritics they are often "i": "Singera", "Bic".
                    String plain = Text.fold(name.replaceAll("[ÎîÂâ]", "i"));
                    if (!plain.equals(Text.fold(name))) {
                        index.computeIfAbsent(plain, k -> new ArrayList<>()).add(p);
                    }
                }
            }
        }
    }

    public static PlaceGazetteer of(List<Place> places) {
        return new PlaceGazetteer(places);
    }

    /** Every place, for writing the gazetteer files. */
    public List<Place> places() {
        List<Place> out = new ArrayList<>(allSettlements);
        districts.values().forEach(list -> list.stream().filter(p -> !out.contains(p)).forEach(out::add));
        return out;
    }

    public static PlaceGazetteer load() {
        try (InputStream in = PlaceGazetteer.class.getResourceAsStream("/gazetteer/md-places.tsv")) {
            if (in == null) {
                throw new IllegalStateException("gazetteer/md-places.tsv is missing from the classpath");
            }
            return new PlaceGazetteer(parse(in));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static List<Place> parse(InputStream in) throws IOException {
        List<Place> places = new ArrayList<>();
        BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        String line;
        boolean header = true;
        while ((line = r.readLine()) != null) {
            if (line.startsWith("#")) {
                continue;
            }
            if (header) {
                header = false;
                continue;
            }
            String[] f = line.split("\t", -1);
            int population = f[5].isEmpty() ? 0 : Integer.parseInt(f[5]);
            places.add(new Place(f[0], f[1], f[2],
                    new GeoPoint(Double.parseDouble(f[3]), Double.parseDouble(f[4])), population));
        }
        return places;
    }

    /**
     * Removes administrative words and notes: "Chișinău mun." -> "Chișinău", "r-nul Ialoveni" -> "Ialoveni",
     * "Ghindești (sat)" -> "Ghindești", "Catranîc, loc.st.c.f." -> "Catranîc".
     */
    static String placeKey(String label) {
        String s = label.replaceAll("\\(.*?\\)", " ");
        int comma = s.indexOf(',');
        if (comma > 0) {
            s = s.substring(0, comma);
        }
        return REGION_WORDS.matcher(Text.fold(s)).replaceAll(" ").strip().replaceAll("\\s+", " ");
    }

    /** A settlement spelled slightly differently ("Dnestrovsk" for Dnestrovsc), when one name is clearly closest. */
    private List<Place> similarSettlements(String key) {
        if (key.length() < 5) {
            return List.of();
        }
        String best = null;
        double bestScore = 0;
        double second = 0;
        for (String name : settlements.keySet()) {
            double sc = Text.similarity(key, name);
            if (sc > bestScore) {
                second = bestScore;
                bestScore = sc;
                best = name;
            } else if (sc > second) {
                second = sc;
            }
        }
        return best != null && bestScore >= 0.88 && second < bestScore - 0.05 ? settlements.get(best) : List.of();
    }

    /**
     * The settlement an ad is in. Several villages share a name, so the one nearest to the seller's pin,
     * or else to the seat of the ad's region, is chosen.
     */
    public Optional<Place> settlement(String localityLabel, String regionLabel, GeoPoint pin) {
        if (localityLabel == null) {
            return Optional.empty();
        }
        List<Place> candidates = settlements.getOrDefault(placeKey(localityLabel), List.of());
        if (candidates.isEmpty() && localityLabel.contains("/")) {
            // Two names for one town: "Bender/Tighina".
            for (String part : localityLabel.split("/")) {
                candidates = settlements.getOrDefault(placeKey(part), List.of());
                if (!candidates.isEmpty()) {
                    break;
                }
            }
        }
        if (candidates.isEmpty()) {
            candidates = similarSettlements(placeKey(localityLabel));
        }
        if (candidates.isEmpty() && regionLabel != null) {
            candidates = similarInRegion(placeKey(localityLabel), regionLabel);
        }
        if (candidates.isEmpty()) {
            return Optional.empty();
        }
        if (candidates.size() == 1) {
            return Optional.of(candidates.getFirst());
        }
        GeoPoint near = pin;
        if (near == null && regionLabel != null) {
            near = settlements.getOrDefault(placeKey(regionLabel), List.of()).stream()
                    .min(Comparator.comparingInt(Place::rank).thenComparing(p -> -p.population()))
                    .map(Place::point).orElse(null);
        }
        if (near != null) {
            GeoPoint from = near;
            return candidates.stream().min(Comparator.comparingDouble(p -> p.point().kmTo(from)));
        }
        return candidates.stream().min(Comparator.comparingInt(Place::rank).thenComparing(p -> -p.population()));
    }

    /**
     * The settlement a point belongs to: the one whose centre is nearest relative to its size, among
     * settlements within 25 km. A street 12 km from central Chișinău but 1 km from Cricova's centre
     * belongs to Cricova.
     */
    public Optional<Place> ownerOf(GeoPoint p) {
        Place best = null;
        double bestScore = Double.MAX_VALUE;
        for (Place s : allSettlements) {
            if (Math.abs(s.point().lat() - p.lat()) > 0.25 || Math.abs(s.point().lon() - p.lon()) > 0.35) {
                continue;
            }
            double score = s.point().kmTo(p) / s.radiusKm();
            if (score < bestScore) {
                bestScore = score;
                best = s;
            }
        }
        return Optional.ofNullable(best);
    }

    /**
     * A settlement spelled differently ("Jăvreni" for Jevreni) within 40 km of the seat of the ad's region:
     * there a looser similarity (0.8) is safe, as the region leaves only a few dozen villages.
     */
    private List<Place> similarInRegion(String key, String regionLabel) {
        Optional<Place> seat = settlements.getOrDefault(placeKey(regionLabel), List.of()).stream()
                .min(Comparator.comparingInt(Place::rank).thenComparing(p -> -p.population()));
        if (seat.isEmpty() || key.length() < 5) {
            return List.of();
        }
        Place best = null;
        double bestScore = 0;
        double second = 0;
        for (Place p : allSettlements) {
            if (p.point().kmTo(seat.get().point()) > 40) {
                continue;
            }
            double sc = Text.similarity(key, Text.fold(p.nameRo()));
            if (sc > bestScore) {
                second = bestScore;
                bestScore = sc;
                best = p;
            } else if (sc > second) {
                second = sc;
            }
        }
        return best != null && bestScore >= 0.8 && second < bestScore - 0.1 ? List.of(best) : List.of();
    }

    /** The settlement with exactly this name (no spelling tolerance) nearest to a point. */
    public Optional<Place> settlementExactly(String name, GeoPoint near) {
        return settlements.getOrDefault(placeKey(name), List.of()).stream()
                .min(Comparator.comparingDouble(p -> p.point().kmTo(near)));
    }

    /** A city district (sector) or quarter of a settlement, e.g. "Botanica" in Chișinău. */
    public Optional<Place> district(String districtLabel, Place settlement) {
        if (districtLabel == null || settlement == null) {
            return Optional.empty();
        }
        return districts.getOrDefault(placeKey(districtLabel), List.of()).stream()
                .filter(d -> d.point().kmTo(settlement.point()) < settlement.radiusKm() + 3)
                .min(Comparator.comparingDouble(d -> d.point().kmTo(settlement.point())));
    }
}
