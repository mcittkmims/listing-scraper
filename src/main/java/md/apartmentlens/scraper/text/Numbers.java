package md.apartmentlens.scraper.text;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reads numbers as sellers write them: "66,38", "1 200 000", "1.200.000", "60 de mii", "1,2 mln". */
public final class Numbers {

    private static final Pattern NUMBER = Pattern.compile(
            "(?<![\\d.,])(\\d{1,3}(?:[ \\u00A0.,]\\d{3})+(?:[.,]\\d{1,2})?|\\d+(?:[.,]\\d+)?)(?![\\d])");
    private static final Pattern THOUSANDS = Pattern.compile(
            "^\\s*(?:de\\s+)?(mii|mie|k|тыс\\.?|тысяч[аи]?|т\\.)", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern MILLIONS = Pattern.compile(
            "^\\s*(?:de\\s+)?(mln\\.?|milioane|milion|million|млн\\.?|миллион(?:а|ов)?)",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private Numbers() {
    }

    /** Parses a single decimal written with a comma or a dot, e.g. "66,38". */
    public static Optional<BigDecimal> parseDecimal(String s) {
        if (s == null) {
            return Optional.empty();
        }
        String t = s.strip().replace(' ', ' ');
        Matcher m = NUMBER.matcher(t);
        if (!m.find()) {
            return Optional.empty();
        }
        return Optional.of(toDecimal(m.group(1)));
    }

    /**
     * Parses the first amount in a text, applying "mii"/"тыс" and "mln"/"млн" multipliers.
     * Returns empty when the text holds no number.
     */
    public static Optional<BigDecimal> parseAmount(String s) {
        if (s == null) {
            return Optional.empty();
        }
        String t = s.replace(' ', ' ');
        Matcher m = NUMBER.matcher(t);
        if (!m.find()) {
            return Optional.empty();
        }
        BigDecimal value = toDecimal(m.group(1));
        String rest = t.substring(m.end()).toLowerCase(Locale.ROOT);
        if (MILLIONS.matcher(rest).find()) {
            value = value.multiply(BigDecimal.valueOf(1_000_000));
        } else if (THOUSANDS.matcher(rest).find()) {
            value = value.multiply(BigDecimal.valueOf(1_000));
        }
        return Optional.of(value.stripTrailingZeros());
    }

    /**
     * Converts a matched number. Rules: with both "," and "." the last one is the decimal separator;
     * a separator used more than once, or once followed by exactly three digits, separates thousands
     * ("1.200.000", "60.000", "1,200"); otherwise it is the decimal separator ("66,38", "1.5").
     */
    static BigDecimal toDecimal(String raw) {
        String s = raw.replace(" ", "").replace("\u00A0", "");
        int lastComma = s.lastIndexOf(',');
        int lastDot = s.lastIndexOf('.');
        if (lastComma >= 0 && lastDot >= 0) {
            int last = Math.max(lastComma, lastDot);
            String intPart = s.substring(0, last).replace(",", "").replace(".", "");
            return new BigDecimal(intPart + "." + s.substring(last + 1));
        }
        int last = Math.max(lastComma, lastDot);
        if (last < 0) {
            return new BigDecimal(s);
        }
        char sep = s.charAt(last);
        boolean repeated = s.indexOf(sep) != last;
        if (repeated || s.length() - last - 1 == 3) {
            return new BigDecimal(s.replace(String.valueOf(sep), ""));
        }
        return new BigDecimal(s.substring(0, last) + "." + s.substring(last + 1));
    }
}
