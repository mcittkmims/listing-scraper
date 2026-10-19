package md.apartmentlens.scraper.normalize.address;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class StreetTypesTest {

    @ParameterizedTest
    @CsvSource(nullValues = "null", value = {
            "'str. Ismail', street, Ismail",
            "'str.Ismail', street, Ismail",
            "'strada Ismail', street, Ismail",
            "'Strada Calea Ieșilor', avenue, Ieșilor",
            "'str. Calea Orheiului', avenue, Orheiului",
            "'bd. Dacia', boulevard, Dacia",
            "'Bulevardul Moscova', boulevard, Moscova",
            "'şos. Hînceşti', highway, Hîncești",
            "'sos.hincesti', highway, hincesti",
            "'str-la Grădinarilor', lane, Grădinarilor",
            "'ул. Язулуй', street, Язулуй",
            "'пр-т Мирча чел Бэтрын', boulevard, Мирча чел Бэтрын",
            "'Московский пр-т', boulevard, Московский",
            "'ш. Хынчешть', highway, Хынчешть",
            "'Ismail', null, Ismail",
            "'Al. Russo', null, Al. Russo",
            "'St. cel Mare', null, St. cel Mare",
            "'str', street, null",
    })
    void split(String raw, String type, String name) {
        assertThat(StreetTypes.split(raw)).isEqualTo(new StreetTypes.Split(type, name));
    }
}
