package md.apartmentlens.scraper.normalize;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import md.apartmentlens.scraper.normalize.AreaNormalizer.Kind;
import md.apartmentlens.scraper.source.Quantity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class AreaNormalizerTest {

    final AreaNormalizer areas = new AreaNormalizer();

    @ParameterizedTest
    @CsvSource({
            "'54 m²', 54",
            "'54 mp', 54",
            "'54,5 кв.м', 54.5",
            "'72 m2', 72",
    })
    void textAreas(String text, String m2) {
        assertThat(areas.area(Quantity.ofText(text), Kind.TOTAL, new Issues())).isEqualByComparingTo(m2);
    }

    @Test
    void landUnits() {
        Issues issues = new Issues();
        assertThat(areas.area(Quantity.of(BigDecimal.valueOf(6), "UNIT_ARE"), Kind.LAND, issues)).isEqualByComparingTo("600");
        assertThat(areas.area(Quantity.of(new BigDecimal("1.5"), "UNIT_HECTARE"), Kind.LAND, issues)).isEqualByComparingTo("15000");
        assertThat(areas.area(Quantity.ofText("8 сотки"), Kind.LAND, issues)).isEqualByComparingTo("800");
        assertThat(areas.area(Quantity.ofText("6 ari"), Kind.LAND, issues)).isEqualByComparingTo("600");
        assertThat(issues.list()).isEmpty();
        // Land without a unit could be ari or m²: not guessed.
        assertThat(areas.area(Quantity.of(BigDecimal.valueOf(6), null), Kind.LAND, issues)).isNull();
        assertThat(issues.list()).extracting(Issue::reason).containsExactly("unit_missing");
    }

    @Test
    void impossibleAreasAreDropped() {
        Issues issues = new Issues();
        assertThat(areas.area(Quantity.of(BigDecimal.ONE, "UNIT_METER_SQUARE"), Kind.TOTAL_HOUSE, issues)).isNull();
        assertThat(areas.area(Quantity.of(BigDecimal.valueOf(2500), "UNIT_METER_SQUARE"), Kind.TOTAL, issues)).isNull();
        assertThat(issues.list()).extracting(Issue::reason).containsExactly("implausible", "implausible");
        assertThat(areas.area(Quantity.of(BigDecimal.ZERO, "UNIT_METER_SQUARE"), Kind.TOTAL, new Issues())).isNull();
    }

    @Test
    void preciseAreaFromTheDescription() {
        BigDecimal rounded = BigDecimal.valueOf(67);
        assertThat(areas.refineFromText(rounded, "Suprafață: 66,38 m² | Etaj 7/9")).isEqualByComparingTo("66.38");
        assertThat(areas.refineFromText(BigDecimal.valueOf(42), "o suprafață de 42,2 mp")).isEqualByComparingTo("42.2");
        // Two different precise values: keep the published one.
        assertThat(areas.refineFromText(rounded, "66,38 m² sau 67,4 m²")).isEqualByComparingTo("67");
        // A value more than 1 m² away is about something else (a balcony, another flat).
        assertThat(areas.refineFromText(rounded, "terasă 12,5 m²")).isEqualByComparingTo("67");
    }

    @ParameterizedTest
    @CsvSource({
            "2700, UNIT_CENTIMETER, 2.70",
            "270, UNIT_CENTIMETER, 2.70",
            "2.8, , 2.80",
            "3000, UNIT_CENTIMETER, 3.00",
    })
    void ceilingHeightBySize(String value, String unit, String metres) {
        assertThat(areas.ceilingHeight(Quantity.of(new BigDecimal(value), unit), new Issues())).isEqualByComparingTo(metres);
    }
}
