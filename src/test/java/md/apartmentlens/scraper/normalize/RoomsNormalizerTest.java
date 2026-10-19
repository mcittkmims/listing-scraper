package md.apartmentlens.scraper.normalize;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class RoomsNormalizerTest {

    final RoomsNormalizer rooms = new RoomsNormalizer();

    @ParameterizedTest
    @CsvSource({
            "'Apartament cu 1 cameră', 1, false",
            "'Apartament cu 2 camere', 2, false",
            "'Apartament cu 5 camere sau mai multe', 5, true",
            "'3', 3, false",
            "'2 camere', 2, false",
            "'2-комнатная', 2, false",
            "'двухкомнатная квартира', 2, false",
            "'трёхкомнатная', 3, false",
            "'Garsonieră', 1, false",
            "'5+', 5, true",
            "'2+1', 2, false",
            "'trei camere', 3, false",
    })
    void counts(String label, int count, boolean openEnded) {
        Issues issues = new Issues();
        var r = rooms.rooms(label, issues);
        assertThat(r.count()).isEqualTo(count);
        assertThat(r.openEnded()).isEqualTo(openEnded);
        assertThat(r.singleRoomOffer()).isFalse();
        assertThat(issues.list()).isEmpty();
    }

    @Test
    void aRoomInASharedFlat() {
        var r = rooms.rooms("O cameră", new Issues());
        assertThat(r.singleRoomOffer()).isTrue();
        assertThat(r.count()).isEqualTo(1);
    }

    @Test
    void unreadable() {
        Issues issues = new Issues();
        assertThat(rooms.rooms("multe", issues).count()).isNull();
        assertThat(issues.list()).extracting(Issue::reason).containsExactly("unparsed");
        assertThat(rooms.rooms(null, issues).count()).isNull();
    }

    @Test
    void livingRoom() {
        Issues issues = new Issues();
        assertThat(rooms.livingRoom("Apartament cu living", issues)).isTrue();
        assertThat(rooms.livingRoom("Apartament fără living", issues)).isFalse();
        assertThat(rooms.livingRoom(null, issues)).isNull();
        assertThat(issues.list()).isEmpty();
    }
}
