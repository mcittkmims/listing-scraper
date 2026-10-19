package md.apartmentlens.scraper.normalize.address;

import static org.assertj.core.api.Assertions.assertThat;

import md.apartmentlens.scraper.normalize.address.HouseNumbers.Kind;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Every case here was typed by a seller on 999.md. */
class HouseNumbersTest {

    @ParameterizedTest
    @CsvSource({
            "'88', '88'", "'10 ', '10'", "'3/A', '3/A'", "'57/a', '57/A'", "'2/1', '2/1'", "'12/9', '12/9'",
            "'9 a', '9A'", "'124 F', '124F'", "'1\\8', '1/8'", "'-58', '58'", "'9.', '9'", "'2/', '2'",
            "'9/9a', '9/9A'", "'13a/k1', '13A/K1'", "'1/K1', '1/K1'", "'10 F1', '10/F1'", "'nr. 12', '12'",
            "'д. 5', '5'", "'24А', '24A'", "'2-1', '2/1'", "'Casa15', '15'",
    })
    void numbers(String raw, String expected) {
        assertThat(HouseNumbers.parse(raw)).isEqualTo(new HouseNumbers.Result(Kind.NUMBER, expected));
    }

    @ParameterizedTest
    @ValueSource(strings = {".", "-", "--", "---", "*", "***", "/", "=", ",", "0"})
    void hidden(String raw) {
        assertThat(HouseNumbers.parse(raw).kind()).isEqualTo(Kind.HIDDEN);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Bloc 4", "bl. 3", "корп. 2"})
    void blocksAreNotHouseNumbers(String raw) {
        assertThat(HouseNumbers.parse(raw).kind()).isEqualTo(Kind.BLOCK);
    }

    @ParameterizedTest
    @ValueSource(strings = {"10000", "B-04", "F", "aaaa", "24В"})
    void invalid(String raw) {
        assertThat(HouseNumbers.parse(raw).kind()).isEqualTo(Kind.INVALID);
    }
}
