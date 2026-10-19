package md.apartmentlens.scraper.source;

import java.math.BigDecimal;

/** A number with the unit the source gave for it, e.g. 6 "UNIT_ARE" or 54 "m²". Either part may be missing. */
public record Quantity(BigDecimal value, String unit, String text) {

    public static Quantity of(BigDecimal value, String unit) {
        return new Quantity(value, unit, null);
    }

    public static Quantity ofText(String text) {
        return new Quantity(null, null, text);
    }
}
