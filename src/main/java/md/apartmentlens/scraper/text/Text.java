package md.apartmentlens.scraper.text;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

/** Text helpers shared by every normalizer. Romanian and Russian text, as typed by sellers. */
public final class Text {

    private static final Pattern CONTROL = Pattern.compile("[\\p{Cc}&&[^\\n\\t]]|[\\u200B-\\u200F\\u2060\\uFEFF]");
    private static final Pattern SPACES = Pattern.compile("[\\s\\u00A0\\u2007\\u202F]+");
    private static final Pattern INLINE_SPACES = Pattern.compile("[ \\t\\u00A0\\u2007\\u202F]+");
    private static final Pattern MARKS = Pattern.compile("\\p{M}+");
    private static final Pattern CYRILLIC = Pattern.compile("\\p{IsCyrillic}");
    private static final Pattern LATIN = Pattern.compile("\\p{IsLatin}");
    private static final Pattern NON_KEY = Pattern.compile("[^\\p{L}\\p{N}]+");

    private Text() {
    }

    /** Trims, removes invisible characters and collapses all whitespace to single spaces. Null for blank text. */
    public static String clean(String s) {
        if (s == null) {
            return null;
        }
        String out = SPACES.matcher(CONTROL.matcher(s).replaceAll("")).replaceAll(" ").strip();
        return out.isEmpty() ? null : fixDiacritics(out);
    }

    /** Like {@link #clean} but keeps line breaks, for descriptions. */
    public static String cleanMultiline(String s) {
        if (s == null) {
            return null;
        }
        String out = CONTROL.matcher(s.replace("\r\n", "\n").replace('\r', '\n')).replaceAll("");
        StringBuilder sb = new StringBuilder();
        int blank = 0;
        for (String line : out.split("\n", -1)) {
            String l = INLINE_SPACES.matcher(line).replaceAll(" ").strip();
            if (l.isEmpty()) {
                blank++;
                continue;
            }
            if (!sb.isEmpty()) {
                sb.append(blank > 0 ? "\n\n" : "\n");
            }
            sb.append(l);
            blank = 0;
        }
        return sb.isEmpty() ? null : fixDiacritics(sb.toString());
    }

    /**
     * Replaces the cedilla letters (ş ţ), which keyboards often produce, with the correct Romanian
     * comma-below letters (ș ț), and the old-orthography tilde forms.
     */
    public static String fixDiacritics(String s) {
        if (s == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            sb.append(switch (c) {
                case 'ş' -> 'ș'; // ş -> ș
                case 'Ş' -> 'Ș'; // Ş -> Ș
                case 'ţ' -> 'ț'; // ţ -> ț
                case 'Ţ' -> 'Ț'; // Ţ -> Ț
                case 'ã' -> 'ă'; // ã -> ă
                case 'Ã' -> 'Ă'; // Ã -> Ă
                default -> c;
            });
        }
        return sb.toString();
    }

    /**
     * A comparison key: lower case, no diacritics, letters and digits separated by single spaces.
     * "Ștefan cel Mare și Sfânt", "Ștefan cel Mare și Sfînt" and "stefan cel mare si sfant" give the same key.
     */
    public static String fold(String s) {
        if (s == null) {
            return "";
        }
        // â and î are one letter in two spellings ("Râșcani", "Rîșcani"; "Sfânt", "Sfînt"). Both fold to "a",
        // as sellers who skip diacritics write "Batran" and "Hancesti"; Russian ы also maps to "a".
        String lower = fixDiacritics(s).toLowerCase(Locale.ROOT).replace('ё', 'е').replace('î', 'â');
        // Keep й: in Russian it is a different letter, not и with a mark.
        lower = lower.replace('й', '\u0001');
        String stripped = MARKS.matcher(Normalizer.normalize(lower, Normalizer.Form.NFD)).replaceAll("");
        stripped = stripped.replace('\u0001', 'й');
        return unmixScripts(NON_KEY.matcher(stripped).replaceAll(" ").strip());
    }

    private static final String LATIN_LOOKALIKES = "aceopxykmthbi";
    private static final String CYRILLIC_LOOKALIKES = "асеорхукмтнві";

    /**
     * Words typed with letters from both alphabets ("Cерый" with a Latin C, "Каля Eшилор") are rewritten
     * in the alphabet most of their letters use, so they compare equal to the correct spelling.
     */
    static String unmixScripts(String key) {
        if (!hasCyrillic(key) || !hasLatin(key)) {
            return key;
        }
        StringBuilder out = new StringBuilder(key.length());
        for (String word : key.split(" ", -1)) {
            long cyr = word.chars().filter(c -> Character.UnicodeScript.of(c) == Character.UnicodeScript.CYRILLIC).count();
            long lat = word.chars().filter(c -> Character.UnicodeScript.of(c) == Character.UnicodeScript.LATIN).count();
            String from = cyr >= lat ? LATIN_LOOKALIKES : CYRILLIC_LOOKALIKES;
            String to = cyr >= lat ? CYRILLIC_LOOKALIKES : LATIN_LOOKALIKES;
            if (!out.isEmpty()) {
                out.append(' ');
            }
            if (cyr == 0 || lat == 0) {
                out.append(word);
                continue;
            }
            for (char c : word.toCharArray()) {
                int i = from.indexOf(c);
                out.append(i >= 0 ? to.charAt(i) : c);
            }
        }
        return out.toString();
    }

    public static boolean hasCyrillic(String s) {
        return s != null && CYRILLIC.matcher(s).find();
    }

    public static boolean hasLatin(String s) {
        return s != null && LATIN.matcher(s).find();
    }

    /**
     * Transliterates Russian spelling of Romanian names back to Latin letters, as a folded key.
     * "Каля Ешилор" -> "calea esilor"; used only for fuzzy matching, never shown.
     */
    public static String cyrillicToLatinKey(String s) {
        return cyrillicToLatinKey(s, true);
    }

    /**
     * Like {@link #cyrillicToLatinKey(String)}; {@code initialIe} false reads a word-initial Е as "e": Russian
     * writes Эминеску, but sellers often type "Еминеску".
     */
    public static String cyrillicToLatinKey(String s, boolean initialIe) {
        String f = fold(s);
        StringBuilder sb = new StringBuilder(f.length() + 8);
        for (int i = 0; i < f.length(); i++) {
            char c = f.charAt(i);
            char next = i + 1 < f.length() ? f.charAt(i + 1) : ' ';
            char prev = i > 0 ? f.charAt(i - 1) : ' ';
            boolean wordStart = prev == ' ';
            switch (c) {
                case 'а' -> sb.append('a');
                case 'б' -> sb.append('b');
                case 'в' -> sb.append('v');
                case 'г' -> sb.append('g');
                case 'д' -> {
                    char after = i + 2 < f.length() ? f.charAt(i + 2) : ' ';
                    if (next == 'ж' && (after == 'е' || after == 'и')) {
                        sb.append('g'); // "Реджеле" -> "regele", "Джордже" -> "george"
                        i++;
                    } else {
                        sb.append('d');
                    }
                }
                case 'е' -> sb.append(wordStart && initialIe ? "ie" : "e"); // "Ешилор" -> "iesilor"
                case 'ж' -> sb.append('j');
                case 'з' -> sb.append('z');
                case 'и' -> sb.append('i');
                case 'й' -> sb.append('i');
                case 'к' -> sb.append('c');
                case 'л' -> sb.append('l');
                case 'м' -> sb.append('m');
                case 'н' -> sb.append('n');
                case 'о' -> sb.append('o');
                case 'п' -> sb.append('p');
                case 'р' -> sb.append('r');
                case 'с' -> sb.append('s');
                case 'т' -> sb.append('t');
                case 'у' -> sb.append('u');
                case 'ф' -> sb.append('f');
                case 'х' -> sb.append('h');
                case 'ц' -> sb.append('t');
                case 'ч' -> sb.append(next == 'е' || next == 'и' ? "c" : "ci");
                case 'ш' -> sb.append('s');
                case 'щ' -> sb.append("st");
                case 'ъ', 'ь' -> {
                    // Romanian "-i" endings: "Чеукарь" -> "ceucari"
                    if (next == ' ' || i + 1 == f.length()) {
                        sb.append('i');
                    }
                }
                case 'ы' -> sb.append('a'); // "Сфынт" -> "sfant", like Sfânt
                case 'э' -> sb.append(wordStart ? 'e' : 'a'); // "Эминеску" -> "e", "Бэтрын" -> "a" (ă)
                case 'ю' -> sb.append(prev == 'и' ? "u" : "iu");
                case 'я' -> sb.append(prev == 'и' ? "a" : "ia"); // "Дачия" -> "dacia"
                case 'і' -> sb.append('i');
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }

    /** Normalized Levenshtein similarity in [0, 1]. */
    public static double similarity(String a, String b) {
        if (a.equals(b)) {
            return 1.0;
        }
        int max = Math.max(a.length(), b.length());
        if (max == 0) {
            return 1.0;
        }
        return 1.0 - (double) levenshtein(a, b) / max;
    }

    static int levenshtein(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            prev[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] t = prev;
            prev = cur;
            cur = t;
        }
        return prev[b.length()];
    }
}
