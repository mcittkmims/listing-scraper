package md.apartmentlens.scraper.text;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class NumbersTest {

    @ParameterizedTest
    @CsvSource({
            "'60 000 €', 60000",
            "'1 200 000 lei', 1200000",
            "'1.200.000', 1200000",
            "'60.000 euro', 60000",
            "'60 de mii €', 60000",
            "'60 mii', 60000",
            "'75 тыс. евро', 75000",
            "'1,2 mln lei', 1200000",
            "'1.5 млн', 1500000",
            "'€89,900', 89900",
            "'Preț: 45500', 45500",
    })
    void amounts(String text, long expected) {
        assertThat(Numbers.parseAmount(text)).hasValueSatisfying(v -> assertThat(v).isEqualByComparingTo(BigDecimal.valueOf(expected)));
    }

    @ParameterizedTest
    @CsvSource({
            "'66,38 m²', 66.38",
            "'42.2 mp', 42.2",
            "'54', 54",
            "'2,7', 2.7",
    })
    void decimals(String text, String expected) {
        assertThat(Numbers.parseDecimal(text)).hasValueSatisfying(v -> assertThat(v).isEqualByComparingTo(expected));
    }

    @org.junit.jupiter.api.Test
    void noNumber() {
        assertThat(Numbers.parseAmount("Preț negociabil")).isEmpty();
        assertThat(Numbers.parseDecimal(null)).isEmpty();
    }

    @Test
    void separators() {
        assertThat(Numbers.toDecimal("1,234.56")).isEqualByComparingTo("1234.56");
        assertThat(Numbers.toDecimal("1.234,56")).isEqualByComparingTo("1234.56");
    }
}
