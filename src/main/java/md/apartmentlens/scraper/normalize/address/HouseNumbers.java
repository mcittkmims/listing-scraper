package md.apartmentlens.scraper.normalize.address;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import md.apartmentlens.scraper.text.Text;

/**
 * House numbers as sellers type them: "57/A", "9 a", "1\8", "-58", "124 F", "nr. 12", "д. 5".
 *
 * <p>The result is written as number, optional letter, then an optional "/" part: "57/A", "9A", "1/8",
 * "12/3B", "13A/K1". Sellers who hide the number type "-", ".", "***" or "0"; those mean "not given".
 */
public final class HouseNumbers {

    public enum Kind { NUMBER, HIDDEN, BLOCK, INVALID, NONE }

    /** A parsed number; {@code value} is set only for {@link Kind#NUMBER}. */
    public record Result(Kind kind, String value) {

        static final Result NONE = new Result(Kind.NONE, null);
        static final Result HIDDEN = new Result(Kind.HIDDEN, null);
        static final Result BLOCK = new Result(Kind.BLOCK, null);
        static final Result INVALID = new Result(Kind.INVALID, null);
    }

    static final int MAX_NUMBER = 999;

    private static final Pattern PREFIX = Pattern.compile(
            "^(?:nr\\.?|n\\.|№|no\\.?|numărul|numarul|casa|дом|д\\.)\\s*", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern BLOCK_WORD = Pattern.compile(
            "^(?:bloc|bl\\.?|block|блок|корп\\.?|корпус|сектор)(?=[\\s.\\d]|$)",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    /**
     * Number, optional letter, then an optional part after "/" (or "\\", "|", "-"): a number, a letter,
     * or a block such as "K1"; a block may also follow without a separator ("10 F1").
     */
    private static final Pattern NUMBER = Pattern.compile(
            "^(\\d{1,4})\\s*([A-Z])?(?:\\s*[/\\\\|-]\\s*([A-Z]?\\d{1,3}[A-Z]?|[A-Z])|\\s*([A-Z]\\d{1,2}))?$");

    private HouseNumbers() {
    }

    public static Result parse(String raw) {
        String s = Text.clean(raw);
        if (s == null) {
            return Result.NONE;
        }
        if (!s.matches(".*[\\p{L}\\p{N}].*")) {
            return Result.HIDDEN; // only punctuation: "-", ".", "***", "/"
        }
        s = PREFIX.matcher(s).replaceFirst("");
        if (BLOCK_WORD.matcher(s).find()) {
            return Result.BLOCK; // a block of a residential complex, not a house number
        }
        s = s.replaceAll("^[\\s\\-.,*_=]+|[\\s.,*_=/\\\\-]+$", ""); // "-58", "9.", "2/"
        s = latinLetters(s.toUpperCase(Locale.ROOT));
        Matcher m = NUMBER.matcher(s);
        if (!m.matches()) {
            return Result.INVALID;
        }
        int number = Integer.parseInt(m.group(1));
        if (number == 0) {
            return Result.HIDDEN;
        }
        if (number > MAX_NUMBER) {
            return Result.INVALID;
        }
        StringBuilder out = new StringBuilder().append(number);
        if (m.group(2) != null) {
            out.append(m.group(2));
        }
        String part = m.group(3) != null ? m.group(3) : m.group(4);
        if (part != null) {
            out.append('/').append(part.replaceFirst("^0+(?=\\d)", ""));
        }
        return new Result(Kind.NUMBER, out.toString());
    }

    /**
     * Cyrillic letters that look like and stand for the Latin ones ("24А" typed on a Russian keyboard).
     * Other Cyrillic letters ("В" is the third letter, not B) are left, so the number is reported invalid.
     */
    static String latinLetters(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (char c : s.toCharArray()) {
            sb.append(switch (c) {
                case 'А' -> 'A';
                case 'Б' -> 'B';
                case 'Е' -> 'E';
                default -> c;
            });
        }
        return sb.toString();
    }
}
