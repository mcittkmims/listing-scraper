package md.apartmentlens.scraper.normalize.address;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import md.apartmentlens.scraper.text.Text;

/**
 * Street type words, in the forms sellers type them ("str.", "str-la", "bd.", "șos.", "ул.", "пр-т"),
 * and the codes they map to. The address-data tool (database/geodata) has a copy; keep the two in step.
 */
public final class StreetTypes {

    /** Type word (lower case, without the final dot) -> code. Longer words first, so "stradela" wins over "str". */
    private static final Map<String, String> WORDS = new LinkedHashMap<>();

    static {
        add("street", "strada", "stradă", "str", "stada", "ulița", "ul", "улица", "ул"); // not "st": "St. cel Mare" is Ștefan
        add("lane", "stradela", "str-la", "str-lă", "strla", "stradelă", "str-ela", "переулок", "пер", "пер-к");
        add("boulevard", "bulevardul", "bulevard", "bd", "bdul", "bd-ul", "bul", "blvd", "bld", "b-dul",
                "проспект", "просп", "пр-т", "пр-кт", "пр", "бульвар", "бул", "б-р");
        add("highway", "șoseaua", "soseaua", "șos", "sos", "шоссе", "ш");
        add("avenue", "calea", "каля");
        add("square", "piața", "piata", "p-ța", "pța", "площадь", "пл");
        add("alley", "aleea", "аллея"); // not "al": "Al. Russo" is Alecu Russo
        add("road", "drumul", "drum", "тракт");
        add("dead_end", "fundătura", "fundatura", "fund", "tupicul", "тупик", "туп");
        add("passage", "intrarea", "intr", "pasajul", "trecerea", "проезд");
    }

    private static final Pattern PREFIX;
    private static final Pattern SUFFIX;

    static {
        String alternatives = WORDS.keySet().stream()
                .sorted((a, b) -> b.length() - a.length())
                .map(Pattern::quote)
                .reduce((a, b) -> a + "|" + b)
                .orElseThrow();
        // A type word at the start, followed by a dot and/or a space: "str. Ismail", "str.Ismail", "ул Мира".
        PREFIX = Pattern.compile("^(" + alternatives + ")(?:\\.\\s*|\\s+)", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
        // A type word at the end: "Ismail str.", "Измаильская ул."
        SUFFIX = Pattern.compile("\\s+(" + alternatives + ")\\.?$", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    }

    private StreetTypes() {
    }

    private static void add(String code, String... words) {
        for (String w : words) {
            WORDS.put(w.toLowerCase(Locale.ROOT), code);
        }
    }

    /** A street with its type words removed. {@code type} is null when the seller wrote none. */
    public record Split(String type, String name) {
    }

    /**
     * Removes type words from the start and end. Several may be stacked: "str. Calea Ieșilor" is the
     * avenue Ieșilor. The most specific type wins over the generic "street".
     */
    public static Split split(String street) {
        String s = Text.clean(street);
        if (s == null) {
            return new Split(null, null);
        }
        String type = null;
        boolean changed = true;
        while (changed) {
            changed = false;
            Matcher m = PREFIX.matcher(s);
            if (m.find() && m.end() < s.length()) {
                type = merge(type, WORDS.get(m.group(1).toLowerCase(Locale.ROOT)));
                s = s.substring(m.end()).strip();
                changed = true;
                continue;
            }
            Matcher e = SUFFIX.matcher(s);
            if (e.find() && e.start() > 0) {
                type = merge(type, WORDS.get(e.group(1).toLowerCase(Locale.ROOT)));
                s = s.substring(0, e.start()).strip();
                changed = true;
            }
        }
        s = s.replaceAll("^[\"'«„“]+|[\"'»”“.,;:]+$", "").strip();
        String alone = WORDS.get(s.toLowerCase(Locale.ROOT));
        if (alone != null) {
            return new Split(merge(type, alone), null); // only a type word: "str"
        }
        return new Split(type, s.isEmpty() ? null : s);
    }

    private static String merge(String current, String found) {
        if (current == null || current.equals("street")) {
            return found;
        }
        return current;
    }
}
