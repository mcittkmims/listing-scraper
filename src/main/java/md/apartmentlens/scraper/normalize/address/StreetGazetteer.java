package md.apartmentlens.scraper.normalize.address;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.zip.GZIPInputStream;
import md.apartmentlens.scraper.source.GeoPoint;
import md.apartmentlens.scraper.text.Text;

/**
 * Named streets of Moldova from OpenStreetMap, with Romanian, Russian and old names
 * (see tools/build_gazetteer.py), and the rules that recognize a street as sellers type it.
 *
 * <p>Streets are looked up only near the ad (its pin or its locality), because villages reuse names.
 * Matching tiers, strictest first: the official name, a Russian/old/alternative name, an incomplete
 * name ("Ștefan cel Mare" for "Ștefan cel Mare și Sfânt"), then a close spelling. A tier is used only
 * when it points to a single street; otherwise the street stays unresolved.
 */
public final class StreetGazetteer {

    static final double FUZZY_MIN = 0.82;
    static final double FUZZY_MARGIN = 0.06;
    /** Score given to a name with the same word stems; below an exact spelling, above typical typos. */
    static final double STEM_SCORE = 0.9;
    /** Romanian case endings and Russian adjective endings, longest first (in transliterated form). */
    private static final List<String> ENDINGS = List.of("scaia", "skaia", "scii", "skii", "ului", "ilor", "elor",
            "aia", "lui", "lor", "ei", "ii", "ia", "a", "i", "e", "u");
    /** Two map names this similar are spellings of one street. */
    static final double SAME_NAME = 0.92;
    private static final double CELL_DEG = 0.05;

    public record Street(String type, String nameRo, String nameRu, GeoPoint point, double extentKm,
                         String keyRo, String keyRu, Set<String> aliasKeys, String translitRu) {

        /** The name to show: Romanian, or Russian where the map has no Romanian name. */
        public String name() {
            return nameRo.isEmpty() ? nameRu : nameRo;
        }

        String nameKey() {
            return keyRo.isEmpty() ? keyRu : keyRo;
        }
    }

    /**
     * A recognized street. {@code street} (with its coordinates) and {@code type} are empty when the name
     * is certain but several streets of different types carry it and nothing tells which one is meant.
     */
    public record Match(String name, String type, Street street, String method) {
    }

    private final List<Street> streets;
    private final Map<String, List<Street>> byKey = new HashMap<>();
    private final Map<Long, List<Street>> byCell = new HashMap<>();

    StreetGazetteer(List<Street> streets) {
        this.streets = streets;
        for (Street s : streets) {
            for (String key : allKeys(s)) {
                byKey.computeIfAbsent(key, k -> new ArrayList<>()).add(s);
            }
            byCell.computeIfAbsent(cell(s.point().lat(), s.point().lon()), k -> new ArrayList<>()).add(s);
        }
    }

    private static Set<String> allKeys(Street s) {
        Set<String> keys = new LinkedHashSet<>();
        if (!s.keyRo().isEmpty()) {
            keys.add(s.keyRo());
        }
        if (!s.keyRu().isEmpty()) {
            keys.add(s.keyRu());
        }
        keys.addAll(s.aliasKeys());
        return keys;
    }

    public static StreetGazetteer load() {
        try (InputStream raw = StreetGazetteer.class.getResourceAsStream("/gazetteer/md-streets.tsv.gz")) {
            if (raw == null) {
                throw new IllegalStateException("gazetteer/md-streets.tsv.gz is missing from the classpath");
            }
            return new StreetGazetteer(parse(new GZIPInputStream(raw)));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static List<Street> parse(InputStream in) throws IOException {
        List<Street> list = new ArrayList<>();
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
            list.add(street(f[0], f[1], f[2], f[3].isEmpty() ? List.of() : Arrays.asList(f[3].split("\\|")),
                    new GeoPoint(Double.parseDouble(f[4]), Double.parseDouble(f[5])), Double.parseDouble(f[6])));
        }
        return list;
    }

    static Street street(String type, String nameRo, String nameRu, List<String> aliases, GeoPoint point, double extentKm) {
        Set<String> aliasKeys = aliases.stream().map(Text::fold).filter(k -> !k.isEmpty())
                .collect(Collectors.toCollection(LinkedHashSet::new));
        return new Street(type, nameRo, nameRu, point, extentKm, Text.fold(nameRo), Text.fold(nameRu), aliasKeys,
                nameRu.isEmpty() ? "" : Text.cyrillicToLatinKey(nameRu));
    }

    public int size() {
        return streets.size();
    }

    /**
     * Finds the street a seller meant.
     *
     * @param name     the street name without type words
     * @param type     the type the seller wrote, or null
     * @param center   the ad's pin or its locality's centre
     * @param radiusKm how far from the centre the street may be
     * @param pinned   true when the centre is the seller's pin, which can tell same-named streets apart
     */
    public Optional<Match> resolve(String name, String type, GeoPoint center, double radiusKm, boolean pinned) {
        String key = Text.fold(name);
        if (key.isEmpty() || center == null) {
            return Optional.empty();
        }
        List<Street> near = nearby(center, radiusKm);

        // 1-2. The name exactly, as official, Russian, old or alternative name.
        List<Street> exact = near.stream().filter(s -> allKeys(s).contains(key)).toList();
        if (!exact.isEmpty()) {
            List<Street> official = exact.stream().filter(s -> s.nameKey().equals(key)).toList();
            boolean specificType = type != null && !type.equals("street");
            if (specificType && official.stream().noneMatch(s -> s.type().equals(type))) {
                // "Bd. Ștefan cel Mare": the boulevard (alias name) over a lane called exactly that.
                List<Street> typed = exact.stream().filter(s -> s.type().equals(type)).toList();
                if (!typed.isEmpty()) {
                    return pick(typed, type, center, pinned, "alias");
                }
            }
            if (!official.isEmpty()) {
                return pick(official, type, center, pinned, "exact");
            }
            // An old or Russian name, unless the seller's type contradicts it: "str-la Renașterii" in
            // Durlești is not the boulevard once called Renașterii.
            List<Street> aliases = type == null || type.equals("street") ? exact
                    : exact.stream().filter(s -> s.type().equals(type)).toList();
            if (!aliases.isEmpty()) {
                return pick(aliases, type, center, pinned, "alias");
            }
        }

        boolean cyrillic = Text.hasCyrillic(name);
        // 3. An incomplete name: every word the seller wrote is in the street's name.
        String[] words = key.split(" ");
        if (Arrays.stream(words).anyMatch(w -> w.length() >= 4)) {
            String[] latinWords = cyrillic ? Text.cyrillicToLatinKey(name).split(" ") : words;
            List<Street> partial = near.stream()
                    .filter(s -> containsWords(cyrillic ? s.keyRu() : s.keyRo(), words)
                            || (cyrillic && containsWords(s.keyRo(), latinWords))
                            || s.aliasKeys().stream().anyMatch(a -> containsWords(a, words)))
                    .toList();
            if (!partial.isEmpty()) {
                return pick(partial, type, center, pinned, "partial");
            }
            // The seller wrote more than the map: "Ștefan cel Mare și Sfânt" where the map has "Ștefan cel Mare".
            List<Street> longer = near.stream()
                    .filter(s -> extendsName(words, s.keyRo()) || (cyrillic && extendsName(latinWords, s.keyRo()))
                            || (cyrillic && extendsName(words, s.keyRu())))
                    .toList();
            if (!longer.isEmpty()) {
                return pick(longer, type, center, pinned, "partial");
            }
        }

        // 4. A close spelling (typos, Russian transliteration of a Romanian name).
        if (key.length() >= 5) {
            String latin = cyrillic ? Text.cyrillicToLatinKey(name) : key;
            record Scored(Street street, double score) {
            }
            String latinStem = stem(latin);
            List<Scored> scored = near.stream()
                    .map(s -> new Scored(s, Math.max(Math.max(
                            s.keyRo().isEmpty() ? 0 : Text.similarity(latin, s.keyRo()),
                            cyrillic && !s.keyRu().isEmpty() ? Text.similarity(key, s.keyRu())
                                    : s.translitRu().isEmpty() ? 0 : Text.similarity(latin, s.translitRu())),
                            // Same word stems: "Буковина" / Bucovinei, "Комсомольская" / Comsomolului.
                            latinStem.length() >= 5 && !s.keyRo().isEmpty() && latinStem.equals(stem(s.keyRo()))
                                    ? STEM_SCORE : 0)))
                    .filter(x -> x.score() >= FUZZY_MIN)
                    .sorted(Comparator.comparingDouble(Scored::score).reversed())
                    .toList();
            if (!scored.isEmpty()) {
                double best = scored.getFirst().score();
                String bestName = scored.getFirst().street().nameKey();
                // Other spellings of the same name in the map data ("Spătaru", "Spătarul") do not compete.
                boolean clearWinner = scored.stream()
                        .filter(x -> Text.similarity(x.street().nameKey(), bestName) < SAME_NAME)
                        .noneMatch(x -> x.score() > best - FUZZY_MARGIN);
                if (clearWinner) {
                    List<Street> top = scored.stream().map(Scored::street)
                            .filter(s -> Text.similarity(s.nameKey(), bestName) >= SAME_NAME).toList();
                    return pick(top, type, center, pinned, "fuzzy");
                }
            }
        }
        return Optional.empty();
    }

    /**
     * True when the seller's words are an incomplete or abbreviated form of the name: each word is a
     * whole word of the name, an initial ("M Eminescu") or the start of one ("Pan Halippa" for Pantelimon),
     * and at least one word of four or more letters matches in full.
     */
    static boolean containsWords(String nameKey, String[] words) {
        if (nameKey.isEmpty()) {
            return false;
        }
        List<String> nameWords = new ArrayList<>(Arrays.asList(nameKey.split(" ")));
        if (nameWords.size() < words.length) {
            return false;
        }
        boolean fullWord = false;
        boolean shortened = nameWords.size() > words.length;
        for (String w : words) {
            int i = nameWords.indexOf(w);
            if (i >= 0) {
                fullWord |= w.length() >= 4;
            } else {
                i = indexOfShortForm(nameWords, w);
                if (i < 0) {
                    return false;
                }
                shortened = true;
            }
            nameWords.remove(i);
        }
        // Either shortened, or all words given in another order ("Deleanu Liviu" for Liviu Deleanu).
        return fullWord && (shortened || !String.join(" ", words).equals(nameKey));
    }

    /**
     * True when the seller's words start with the whole map name and add at most two words without
     * digits: "stefan cel mare si sfint" extends "stefan cel mare". The map name needs two or more words.
     */
    static boolean extendsName(String[] words, String nameKey) {
        if (nameKey.isEmpty()) {
            return false;
        }
        String[] name = nameKey.split(" ");
        if (name.length < 2 || words.length <= name.length || words.length > name.length + 2) {
            return false;
        }
        for (int i = 0; i < name.length; i++) {
            if (!name[i].equals(words[i])) {
                return false;
            }
        }
        for (int i = name.length; i < words.length; i++) {
            if (words[i].chars().anyMatch(Character::isDigit)) {
                return false;
            }
        }
        return true;
    }

    /** Each word without its grammatical ending; words shorter than five letters stay as they are. */
    static String stem(String latinKey) {
        StringBuilder sb = new StringBuilder();
        for (String w : latinKey.split(" ")) {
            String st = w;
            if (w.length() >= 5) {
                for (String e : ENDINGS) {
                    if (w.endsWith(e) && w.length() - e.length() >= 4) {
                        st = w.substring(0, w.length() - e.length());
                        break;
                    }
                }
            }
            if (!sb.isEmpty()) {
                sb.append(' ');
            }
            sb.append(st);
        }
        return sb.toString();
    }

    private static int indexOfShortForm(List<String> nameWords, String w) {
        if (w.chars().anyMatch(Character::isDigit)) {
            return -1;
        }
        for (int i = 0; i < nameWords.size(); i++) {
            String n = nameWords.get(i);
            boolean initial = w.length() <= 2 && n.startsWith(w);
            boolean start = w.length() >= 3 && n.length() > w.length() && n.startsWith(w);
            if (initial || start) {
                return i;
            }
        }
        return -1;
    }

    /** Present when all streets share one name (possibly several pieces or types of it). */
    private static Optional<String> single(List<Street> list) {
        Set<String> names = list.stream().map(Street::nameKey).collect(Collectors.toSet());
        return names.size() == 1 ? Optional.of(names.iterator().next()) : Optional.empty();
    }

    /**
     * Among streets found for a name, picks the one meant. Nothing is picked when they carry different
     * names. When the same name has several types (Strada Moscova, Bulevardul Moscova), the seller's pin
     * decides if it is clearly closer to one of them, then the type the seller wrote; otherwise the name is
     * returned without a type or coordinates.
     */
    private static Optional<Match> pick(List<Street> list, String type, GeoPoint center, boolean pinned, String method) {
        if (single(list).isEmpty()) {
            return Optional.empty();
        }
        Comparator<Street> byDistance = Comparator.comparingDouble(s -> distance(s, center));
        List<Street> sorted = list.stream().sorted(byDistance).toList();
        Street nearest = sorted.getFirst();
        Set<String> types = list.stream().map(Street::type).collect(Collectors.toSet());
        if (types.size() == 1) {
            return Optional.of(new Match(nearest.name(), nearest.type(), nearest, method));
        }
        if (pinned) {
            double d1 = distance(nearest, center);
            double d2 = sorted.stream().filter(s -> !s.type().equals(nearest.type()))
                    .mapToDouble(s -> distance(s, center)).min().orElse(Double.MAX_VALUE);
            if (d1 < 0.3 && d2 - d1 > 1.0) {
                return Optional.of(new Match(nearest.name(), nearest.type(), nearest, method));
            }
        }
        if (type != null) {
            Optional<Street> sameType = sorted.stream().filter(s -> s.type().equals(type)).findFirst();
            if (sameType.isPresent()) {
                return Optional.of(new Match(sameType.get().name(), type, sameType.get(), method));
            }
        }
        return Optional.of(new Match(nearest.name(), null, null, method));
    }

    /** Distance from a point to a street, roughly: to its centre minus its half-length. */
    private static double distance(Street s, GeoPoint p) {
        return Math.max(0, s.point().kmTo(p) - s.extentKm());
    }

    private List<Street> nearby(GeoPoint center, double radiusKm) {
        int cells = (int) Math.ceil(radiusKm / (CELL_DEG * 75)) + 1; // a cell is at least ~3.8 km wide here
        int cLat = (int) Math.floor(center.lat() / CELL_DEG);
        int cLon = (int) Math.floor(center.lon() / CELL_DEG);
        List<Street> out = new ArrayList<>();
        for (int dLat = -cells; dLat <= cells; dLat++) {
            for (int dLon = -cells; dLon <= cells; dLon++) {
                List<Street> list = byCell.get(key(cLat + dLat, cLon + dLon));
                if (list == null) {
                    continue;
                }
                for (Street s : list) {
                    if (s.point().kmTo(center) - s.extentKm() <= radiusKm) {
                        out.add(s);
                    }
                }
            }
        }
        return out;
    }

    private static long cell(double lat, double lon) {
        return key((int) Math.floor(lat / CELL_DEG), (int) Math.floor(lon / CELL_DEG));
    }

    private static long key(int lat, int lon) {
        return ((long) lat << 32) ^ (lon & 0xffffffffL);
    }

    /** Every street key, for tests. */
    Map<String, List<Street>> index() {
        return byKey;
    }
}
