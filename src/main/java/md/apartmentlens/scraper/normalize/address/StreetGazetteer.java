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
import java.util.TreeSet;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
    /** Pieces of one street are at most this far apart. */
    static final double SAME_STREET_KM = 1.5;
    private static final double CELL_DEG = 0.05;

    /**
     * @param city the locality the street belongs to, or empty when unknown
     * @param line the street's line, as parts of lat, lon pairs; empty when unknown
     * @param aliasNames other names of the street, as written (their folded forms are in aliasKeys)
     */
    public record Street(String type, String nameRo, String nameRu, GeoPoint point, double extentKm,
                         String keyRo, String keyRu, Set<String> aliasKeys, String translitRu, int index,
                         String city, float[][] line, List<String> aliasNames) {

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

    /** Looser spelling accepted only for streets with buildings right around the pin. */
    static final double NEARBY_MIN = 0.65;
    static final double NEARBY_MARGIN = 0.15;
    static final double NEARBY_KM = 0.12;

    private final List<Street> streets;
    private final AddressIndex addresses;
    private final Map<String, List<Street>> byKey = new HashMap<>();
    private final Map<Long, List<Street>> byCell = new HashMap<>();

    StreetGazetteer(List<Street> streets, AddressIndex addresses) {
        this.streets = streets;
        this.addresses = addresses;
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
        try (InputStream raw = resource("/gazetteer/md-streets.tsv.gz");
             InputStream addressesRaw = resource("/gazetteer/md-addresses.tsv.gz")) {
            List<Street> list = parse(new GZIPInputStream(raw));
            return new StreetGazetteer(list, AddressIndex.parse(new GZIPInputStream(addressesRaw, 1 << 16), list.size()));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static InputStream resource(String name) {
        InputStream in = StreetGazetteer.class.getResourceAsStream(name);
        if (in == null) {
            throw new IllegalStateException(name + " is missing from the classpath");
        }
        return in;
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
                    new GeoPoint(Double.parseDouble(f[4]), Double.parseDouble(f[5])), Double.parseDouble(f[6]),
                    list.size(), f.length > 8 ? f[8] : "", f.length > 9 ? Polyline.decode(f[9]) : new float[0][]));
        }
        return list;
    }

    public static Street street(String type, String nameRo, String nameRu, List<String> aliases, GeoPoint point,
                                double extentKm, int index, String city, float[][] line) {
        Set<String> aliasKeys = aliases.stream().map(Text::fold).filter(k -> !k.isEmpty())
                .collect(Collectors.toCollection(LinkedHashSet::new));
        return new Street(type, nameRo, nameRu, point, extentKm, Text.fold(nameRo), Text.fold(nameRu), aliasKeys,
                nameRu.isEmpty() ? "" : Text.cyrillicToLatinKey(nameRu), index, city, line, List.copyOf(aliases));
    }

    public int size() {
        return streets.size();
    }

    /** The building with this house number on the street, from the map's address points. */
    public Optional<GeoPoint> address(Street street, String houseNumber) {
        return street == null ? Optional.empty() : addresses.find(street.index(), houseNumber);
    }

    /** Streets with a building within {@code radiusKm} of the point, nearest first. */
    public List<Street> streetsAround(GeoPoint point, double radiusKm) {
        return addresses.streetsAround(point, radiusKm).stream().map(streets::get).toList();
    }

    /** Distance from a point to a street: to its line or nearest building, else to its centre minus its length. */
    public double distanceKm(Street s, GeoPoint p) {
        double d = addresses.distanceKm(s.index(), p);
        if (s.line().length > 0) {
            d = Math.min(d, Polyline.distanceKm(s.line(), p.lat(), p.lon()));
        }
        return Double.isInfinite(d) ? Math.max(0, s.point().kmTo(p) - s.extentKm()) : d;
    }

    public static StreetGazetteer of(List<Street> streets, AddressIndex addresses) {
        return new StreetGazetteer(streets, addresses);
    }

    /** Every street, in index order; for writing the gazetteer files. */
    public List<Street> streets() {
        return streets;
    }

    public AddressIndex addressIndex() {
        return addresses;
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
        return resolve(name, type, center, radiusKm, pinned, s -> true);
    }

    /** Like {@link #resolve(String, String, GeoPoint, double, boolean)}, among the streets {@code allowed} accepts. */
    public Optional<Match> resolve(String name, String type, GeoPoint center, double radiusKm, boolean pinned,
                                   Predicate<Street> allowed) {
        Optional<Match> found = resolveName(name, type, center, radiusKm, pinned, allowed);
        if (found.isEmpty() && name != null && INITIAL_IE.matcher(name).find()) {
            // Russian writes Эминеску; sellers often type "Еминеску".
            found = resolveName(INITIAL_IE.matcher(name).replaceAll("$1Э"), type, center, radiusKm, pinned, allowed);
        }
        return found;
    }

    private static final Pattern INITIAL_IE = Pattern.compile("(^|[\\s.])[Ее]");

    private Optional<Match> resolveName(String name, String type, GeoPoint center, double radiusKm, boolean pinned,
                                        Predicate<Street> allowed) {
        String key = Text.fold(name);
        if (key.isEmpty() || center == null) {
            return Optional.empty();
        }
        List<Street> near = nearby(center, radiusKm).stream().filter(allowed).toList();

        // 1-2. The name exactly, as official, Russian, old or alternative name, or a Russian spelling that
        // transliterates exactly to the official name ("ул. Яловень" for Ialoveni).
        String translit = Text.hasCyrillic(name) ? Text.cyrillicToLatinKey(name) : null;
        List<Street> exact = near.stream()
                .filter(s -> allKeys(s).contains(key) || (translit != null && s.keyRo().equals(translit)))
                .toList();
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
                    .filter(s -> !numbersConflict(key, s))
                    .map(s -> new Scored(s, Math.max(Math.max(
                            s.keyRo().isEmpty() || otherInitial(cyrillic, latin, s.keyRo()) ? 0
                                    : Text.similarity(latin, s.keyRo()),
                            cyrillic && !s.keyRu().isEmpty() ? Text.similarity(key, s.keyRu())
                                    : s.translitRu().isEmpty() || otherInitial(cyrillic, latin, s.translitRu()) ? 0
                                    : Text.similarity(latin, s.translitRu())),
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
     * The street the seller meant, among only the streets with buildings right around the pin: a looser
     * spelling is accepted there ("ул. Буковина" for Strada Bucovinei), as no other street is close by.
     * Used when {@link #resolve} finds nothing; the pin must be the seller's own, not a default one.
     */
    public Optional<Match> resolveAtPin(String name, String type, GeoPoint pin) {
        String key = Text.fold(name);
        if (key.length() < 4 || pin == null) {
            return Optional.empty();
        }
        boolean cyrillic = Text.hasCyrillic(name);
        String latin = cyrillic ? Text.cyrillicToLatinKey(name) : key;
        String latinStem = stem(latin);
        record Scored(Street street, double score) {
        }
        List<Scored> scored = streetsAround(pin, NEARBY_KM).stream()
                .map(s -> new Scored(s, likenessAtPin(key, latin, latinStem, cyrillic, s)))
                .sorted(Comparator.comparingDouble(Scored::score).reversed())
                .toList();
        if (scored.isEmpty() || scored.getFirst().score() < NEARBY_MIN) {
            return Optional.empty();
        }
        Scored best = scored.getFirst();
        boolean clearWinner = scored.stream()
                .filter(x -> Text.similarity(x.street().nameKey(), best.street().nameKey()) < SAME_NAME)
                .noneMatch(x -> x.score() > best.score() - NEARBY_MARGIN);
        if (!clearWinner) {
            return Optional.empty();
        }
        Street st = best.street();
        return Optional.of(new Match(st.name(), st.type(), st, "nearby"));
    }

    /** How much a seller's street name looks like a street's name, in any spelling: 0 to 1. */
    public double likeness(String name, Street s) {
        String key = Text.fold(name);
        boolean cyrillic = Text.hasCyrillic(name);
        String latin = cyrillic ? Text.cyrillicToLatinKey(name) : key;
        return likenessAtPin(key, latin, stem(latin), cyrillic, s);
    }

    private static double likenessAtPin(String key, String latin, String latinStem, boolean cyrillic, Street s) {
        return numbersConflict(key, s) ? 0 : likeness(key, latin, latinStem, cyrillic, s);
    }

    /** The numbers in a name ("2-й пер. Мицкевича", "Linia 8"); names with different numbers are other streets. */
    static Set<String> numbers(String key) {
        Set<String> out = new TreeSet<>();
        Matcher m = Pattern.compile("\\d+").matcher(key);
        while (m.find()) {
            out.add(m.group().replaceFirst("^0+(?=\\d)", ""));
        }
        return out;
    }

    private static boolean numbersConflict(String key, Street s) {
        // A number at the end of what the seller wrote is a house number ("Stan Posadnicu 25"), unless the
        // street's own name ends with a number too ("Linia 8").
        String[] words = key.split(" ");
        String street = s.nameKey();
        if (words.length > 1 && words[words.length - 1].matches("\\d+") && !street.matches(".*\\d$")) {
            key = String.join(" ", Arrays.copyOf(words, words.length - 1));
        }
        Set<String> mine = numbers(key);
        Set<String> theirs = numbers(street);
        return !mine.isEmpty() && !theirs.isEmpty() && !mine.equals(theirs)
                || !mine.isEmpty() && theirs.isEmpty() && mine.stream().allMatch(n -> n.length() <= 2)
                || mine.isEmpty() && !theirs.isEmpty() && theirs.stream().allMatch(n -> n.length() <= 2);
    }

    private static double likeness(String key, String latin, String latinStem, boolean cyrillic, Street s) {
        if (numbersConflict(key, s)) {
            return 0; // "2 пер. Мицкевича" is not "1-й переулок Мицкевича", "Линия 1" is not "Linia 8"
        }
        double ro = s.keyRo().isEmpty() ? 0 : Math.max(Text.similarity(latin, s.keyRo()),
                Text.similarity(latinStem, stem(s.keyRo())));
        double ru = cyrillic && !s.keyRu().isEmpty() ? Text.similarity(key, s.keyRu())
                : s.translitRu().isEmpty() ? 0 : Text.similarity(latin, s.translitRu());
        double stems = !latinStem.isEmpty() && !s.keyRo().isEmpty() && latinStem.equals(stem(s.keyRo())) ? STEM_SCORE : 0;
        return Math.max(Math.max(ro, ru), stems);
    }

    /**
     * Two names that differ only in their first letter are two streets ("Perilor", "Merilor"), not a typo. Î
     * folds to "a", so I and A are one letter here ("Invierea", "Învierea"), as are K and C in Russian names
     * typed in Latin letters ("Kosmonavtov"). Russian text is transliterated first, which can change the first
     * letter (Х: H, Г: G or H), so it is not held to this.
     */
    static boolean otherInitial(boolean cyrillic, String latin, String keyRo) {
        if (cyrillic || latin.length() != keyRo.length() || latin.length() < 2) {
            return false;
        }
        return initial(latin) != initial(keyRo) && latin.substring(1).equals(keyRo.substring(1));
    }

    private static char initial(String key) {
        return switch (key.charAt(0)) {
            case 'i' -> 'a';
            case 'k' -> 'c';
            default -> key.charAt(0);
        };
    }

    /** The building at the pin must be this close, and the next street's buildings clearly farther. */
    static final double AT_PIN_KM = 0.04;

    /**
     * The street the pin stands on, from the buildings around it: the nearest building is within 40 m and
     * every other street's buildings are clearly farther (a pin on a corner gives nothing).
     */
    public Optional<Street> streetAt(GeoPoint pin) {
        List<AddressIndex.Nearest> near = addresses.nearestBuildings(pin, 0.1);
        if (near.isEmpty() || near.getFirst().km() > AT_PIN_KM) {
            return Optional.empty();
        }
        Street first = streets.get(near.getFirst().street());
        double d1 = near.getFirst().km();
        for (AddressIndex.Nearest n : near.subList(1, near.size())) {
            Street other = streets.get(n.street());
            if (sameName(other, first)) {
                continue; // another piece or spelling of the same street
            }
            if (n.km() < d1 + 0.02 || n.km() < 1.5 * d1) {
                return Optional.empty();
            }
            break;
        }
        return Optional.of(first);
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
        // A lane number left over is another street: "Stradela 2 Muncești" is not Șoseaua Muncești. A year
        // at the end is part of the name: "31 August" is "31 August 1989".
        String firstWord = nameKey.split(" ")[0];
        if (nameWords.contains(firstWord) && firstWord.chars().allMatch(Character::isDigit)) {
            return false;
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

    /**
     * Present when all streets share one name: pieces or types of one street, or spellings of one name
     * in the map data ("Mihail Sadoveanu", "Mihai Sadoveanu"). The longest street's name is returned.
     */
    private static Optional<String> single(List<Street> list) {
        Street longest = list.stream().max(Comparator.comparingDouble(Street::extentKm)).orElseThrow();
        boolean one = list.stream().allMatch(s -> s.nameKey().equals(longest.nameKey())
                || (sameName(s, longest) && s.point().kmTo(longest.point()) - s.extentKm() - longest.extentKm() <= SAME_STREET_KM));
        return one ? Optional.of(longest.nameKey()) : Optional.empty();
    }

    static boolean sameName(Street a, Street b) {
        return a.nameKey().equals(b.nameKey()) || Text.similarity(a.nameKey(), b.nameKey()) >= SAME_NAME;
    }

    /**
     * Among streets found for a name, picks the one meant. Nothing is picked when they carry different
     * names. When the same name has several types (Strada Moscova, Bulevardul Moscova), the seller's pin
     * decides if it is clearly closer to one of them, then the type the seller wrote; otherwise the name is
     * returned without a type or coordinates.
     */
    private Optional<Match> pick(List<Street> list, String type, GeoPoint center, boolean pinned, String method) {
        if (single(list).isEmpty()) {
            if (!pinned) {
                return Optional.empty();
            }
            // Different streets carry the name ("Буковина" is Strada Bucovina and Strada Bucovinei): the pin
            // decides when it stands among the buildings of only one of them, or is clearly nearest to one.
            List<Street> around = streetsAround(center, NEARBY_KM);
            List<Street> inPlace = list.stream().filter(around::contains).toList();
            if (!inPlace.isEmpty() && single(inPlace).isPresent()) {
                list = inPlace;
            } else {
                List<Street> sorted = list.stream().sorted(Comparator.comparingDouble(s -> distanceKm(s, center))).toList();
                Street first = sorted.getFirst();
                double d1 = distanceKm(first, center);
                double d2 = sorted.stream().filter(s -> !sameName(s, first))
                        .mapToDouble(s -> distanceKm(s, center)).min().orElse(Double.MAX_VALUE);
                if (d1 > 0.25 || d2 - d1 < 0.5) {
                    return Optional.empty();
                }
                list = list.stream().filter(s -> sameName(s, first)).toList();
            }
        }
        Comparator<Street> byDistance = Comparator.comparingDouble(s -> distanceKm(s, center));
        List<Street> sorted = list.stream().sorted(byDistance).toList();
        Street canonical = list.stream().max(Comparator.comparingDouble(Street::extentKm)).orElseThrow();
        Street nearest = sorted.getFirst();
        Set<String> types = list.stream().map(Street::type).collect(Collectors.toSet());
        if (types.size() == 1) {
            return Optional.of(new Match(nameFor(nearest, canonical), nearest.type(), nearest, method));
        }
        if (pinned) {
            double d1 = distanceKm(nearest, center);
            double d2 = sorted.stream().filter(s -> !s.type().equals(nearest.type()))
                    .mapToDouble(s -> distanceKm(s, center)).min().orElse(Double.MAX_VALUE);
            if (d1 < 0.3 && d2 - d1 > 1.0) {
                return Optional.of(new Match(nameFor(nearest, canonical), nearest.type(), nearest, method));
            }
        }
        if (type != null) {
            Optional<Street> sameType = sorted.stream().filter(s -> s.type().equals(type)).findFirst();
            if (sameType.isPresent()) {
                return Optional.of(new Match(nameFor(sameType.get(), canonical), type, sameType.get(), method));
            }
        }
        return Optional.of(new Match(canonical.name(), null, null, method));
    }

    /**
     * A street's own name, unless it is a spelling variant of the canonical one on the same physical street
     * (within {@value #SAME_STREET_KM} km): a similar name elsewhere, in another village, is never borrowed.
     */
    private static String nameFor(Street street, Street canonical) {
        if (street.nameKey().equals(canonical.nameKey())) {
            return street.name();
        }
        boolean samePlace = street.point().kmTo(canonical.point()) - street.extentKm() - canonical.extentKm() <= SAME_STREET_KM;
        return samePlace ? canonical.name() : street.name();
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

}
