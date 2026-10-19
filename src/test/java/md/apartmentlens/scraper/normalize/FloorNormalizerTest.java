package md.apartmentlens.scraper.normalize;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class FloorNormalizerTest {

    final FloorNormalizer floors = new FloorNormalizer();

    @ParameterizedTest
    @CsvSource(nullValues = "null", value = {
            "'7', '9', 7, null, 9",
            "'3/9', null, 3, null, 9",
            "'etajul 3 din 9', null, 3, null, 9",
            "'3 этаж из 9', null, 3, null, 9",
            "'Demisol', '9', 0, semi_basement, 9",
            "'Subsol', '5', -1, basement, 5",
            "'Mansardă', '5', null, attic, 5",
            "'Penthouse', '16', null, penthouse, 16",
            "null, '2 etaje', null, null, 2",
            "null, '4 și mai multe etaje', null, null, 4",
    })
    void floors(String floor, String total, Integer expectedFloor, String kind, Integer expectedTotal) {
        Issues issues = new Issues();
        var r = floors.floor(floor, total, issues);
        assertThat(r.floor()).isEqualTo(expectedFloor);
        assertThat(r.kind()).isEqualTo(kind);
        assertThat(r.floorsTotal()).isEqualTo(expectedTotal);
        assertThat(issues.list()).isEmpty();
    }

    @Test
    void aFloorAboveTheLastIsDropped() {
        Issues issues = new Issues();
        var r = floors.floor("9", "5", issues);
        assertThat(r.floor()).isNull();
        assertThat(r.floorsTotal()).isEqualTo(5);
        assertThat(issues.list()).extracting(Issue::reason).containsExactly("above_last_floor");
    }

    @Test
    void conflictingTotals() {
        Issues issues = new Issues();
        var r = floors.floor("3/9", "10", issues);
        assertThat(r.floor()).isEqualTo(3);
        assertThat(r.floorsTotal()).isNull();
        assertThat(issues.list()).extracting(Issue::reason).containsExactly("conflict");
    }
}
