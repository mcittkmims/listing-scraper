package md.apartmentlens.scraper.normalize;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;
import java.util.Optional;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import md.apartmentlens.scraper.source.Quantity;
import md.apartmentlens.scraper.text.Numbers;
import md.apartmentlens.scraper.text.Text;

/**
 * Areas in m²: "54 m²", "54 mp", "54 кв.м", land in "ari" (100 m²), "сотки" or hectares.
 * Values outside the range possible for the kind of property are reported and left empty.
 */
public final class AreaNormalizer {

    private static final BigDecimal ARE = BigDecimal.valueOf(100);
    private static final BigDecimal HECTARE = BigDecimal.valueOf(10_000);

    private static final Pattern UNIT_M2 = Pattern.compile(
            "unit_meter_square|m²|m2|mp|m\\.p\\.?|metri patrati|кв\\.?\\s*м|м²|м2|sq\\.?\\s*m", Pattern.CASE_INSENSITIVE);
    private static final Pattern UNIT_ARE = Pattern.compile(
            "unit_are|\\bari?\\b|\\bar\\.|сот(ок|ки|ка|\\.)?|\\bсот\\b", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern UNIT_HECTARE = Pattern.compile(
            "unit_hectare|\\bha\\b|hectar|\\bга\\b|гектар", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    /** An area with decimals in free text, e.g. "66,38 m²" or "42.2 mp". */
    private static final Pattern PRECISE_AREA = Pattern.compile(
            "(?<![\\d.,])(\\d{1,4}[.,]\\d{1,2})\\s*(?:m²|m2|mp|m\\.p|кв\\.?\\s*м|м²|м2|metri)", Pattern.CASE_INSENSITIVE);

    public enum Kind {
        TOTAL("area_total", 10, 1_000),
        TOTAL_ROOM("area_total", 5, 200),
        TOTAL_HOUSE("area_total", 15, 5_000),
        TOTAL_COUNTRY_HOUSE("area_total", 4, 2_000),
        LIVING("area_living", 3, 3_000),
        KITCHEN("area_kitchen", 2, 200),
        LAND("land_area", 10, 200_000);

        final String field;
        final BigDecimal min;
        final BigDecimal max;

        Kind(String field, int min, int max) {
            this.field = field;
            this.min = BigDecimal.valueOf(min);
            this.max = BigDecimal.valueOf(max);
        }
    }

    public static Kind totalKind(String propertyType) {
        return switch (propertyType) {
            case "apartment" -> Kind.TOTAL;
            case "room" -> Kind.TOTAL_ROOM;
            case "country_house" -> Kind.TOTAL_COUNTRY_HOUSE;
            default -> Kind.TOTAL_HOUSE;
        };
    }

    /** The area in m², or empty. Building areas without a unit are m²; land without a unit is ambiguous. */
    public BigDecimal area(Quantity q, Kind kind, Issues issues) {
        if (q == null) {
            return null;
        }
        String text = Text.clean(q.text());
        BigDecimal value = q.value() != null ? q.value() : (text == null ? null : Numbers.parseDecimal(text).orElse(null));
        if (value == null) {
            if (text != null) {
                issues.add(kind.field, text, "unparsed");
            }
            return null;
        }
        if (value.signum() == 0) {
            return null; // sources store 0 for "not filled in"
        }
        String unitText = q.unit() != null ? q.unit() : text;
        Optional<BigDecimal> factor = factor(unitText);
        if (factor.isEmpty()) {
            if (kind == Kind.LAND) {
                issues.add(kind.field, describe(q), "unit_missing");
                return null;
            }
            factor = Optional.of(BigDecimal.ONE);
        }
        BigDecimal m2 = value.multiply(factor.get()).setScale(2, RoundingMode.HALF_UP);
        if (m2.compareTo(kind.min) < 0 || m2.compareTo(kind.max) > 0) {
            issues.add(kind.field, describe(q), "implausible");
            return null;
        }
        return m2;
    }

    static Optional<BigDecimal> factor(String unit) {
        if (unit == null) {
            return Optional.empty();
        }
        String u = unit.toLowerCase(Locale.ROOT);
        if (UNIT_HECTARE.matcher(u).find()) {
            return Optional.of(HECTARE);
        }
        if (UNIT_ARE.matcher(u).find()) {
            return Optional.of(ARE);
        }
        if (UNIT_M2.matcher(u).find()) {
            return Optional.of(BigDecimal.ONE);
        }
        return Optional.empty();
    }

    /**
     * Some sources round the total area to whole m². When the description states one precise value within
     * one m² of it ("66,38 m²" for 67), that value is used instead. Several different candidates: no change.
     */
    public BigDecimal refineFromText(BigDecimal rounded, String... texts) {
        if (rounded == null || rounded.stripTrailingZeros().scale() > 0) {
            return rounded;
        }
        TreeSet<BigDecimal> candidates = new TreeSet<>();
        for (String text : texts) {
            if (text == null) {
                continue;
            }
            Matcher m = PRECISE_AREA.matcher(text);
            while (m.find()) {
                BigDecimal v = new BigDecimal(m.group(1).replace(',', '.')).setScale(2, RoundingMode.HALF_UP);
                if (v.subtract(rounded).abs().compareTo(BigDecimal.ONE) < 0 && v.compareTo(rounded) != 0) {
                    candidates.add(v);
                }
            }
        }
        return candidates.size() == 1 ? candidates.first() : rounded;
    }

    /**
     * Ceiling height in metres. Read by size, because units are mislabelled (999.md marks 2700 mm as
     * centimetres): 2-8 is metres, 150-800 centimetres, 1500-8000 millimetres.
     */
    public BigDecimal ceilingHeight(Quantity q, Issues issues) {
        if (q == null) {
            return null;
        }
        String text = Text.clean(q.text());
        BigDecimal v = q.value() != null ? q.value() : (text == null ? null : Numbers.parseDecimal(text).orElse(null));
        if (v == null || v.signum() == 0) {
            return null;
        }
        BigDecimal m;
        if (between(v, 2, 8)) {
            m = v;
        } else if (between(v, 150, 800)) {
            m = v.movePointLeft(2);
        } else if (between(v, 1_500, 8_000)) {
            m = v.movePointLeft(3);
        } else {
            issues.add("ceiling_height", describe(q), "implausible");
            return null;
        }
        if (!between(m, 2, 8)) {
            issues.add("ceiling_height", describe(q), "implausible");
            return null;
        }
        return m.setScale(2, RoundingMode.HALF_UP);
    }

    private static boolean between(BigDecimal v, int min, int max) {
        return v.compareTo(BigDecimal.valueOf(min)) >= 0 && v.compareTo(BigDecimal.valueOf(max)) <= 0;
    }

    private static String describe(Quantity q) {
        if (q.text() != null) {
            return q.text();
        }
        return (q.value() == null ? "" : q.value().toPlainString()) + (q.unit() == null ? "" : " " + q.unit());
    }
}
