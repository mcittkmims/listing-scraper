package md.apartmentlens.scraper.normalize.address;

import static org.assertj.core.api.Assertions.assertThat;

import md.apartmentlens.scraper.normalize.Issues;
import md.apartmentlens.scraper.normalize.address.PlaceGazetteer.Place;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Street recognition against the real gazetteer, with streets as sellers typed them on 999.md. */
class AddressNormalizerTest {

    static PlaceGazetteer places;
    static AddressNormalizer addresses;
    static Place chisinau;

    @BeforeAll
    static void load() {
        places = PlaceGazetteer.load();
        addresses = new AddressNormalizer(StreetGazetteer.load());
        chisinau = places.settlement("Chișinău", "Chișinău mun.", null).orElseThrow();
    }

    AddressNormalizer.Address chisinau(String street, String house) {
        return addresses.normalize(street, house, chisinau, null, new Issues());
    }

    @ParameterizedTest
    @CsvSource(nullValues = "null", value = {
            // official name, other spellings of it
            "'str. Ismail', street, Ismail, exact",
            "'str. Izmail', street, Ismail, fuzzy",
            "'bd. Mircea cel Batran', boulevard, Mircea cel Bătrân, exact",
            "'Soseaua Hancesti', highway, Hâncești, exact",
            "'str. Calea Iesilor', avenue, Ieșilor, exact",
            // Russian
            "'ул. Каля Ешилор', avenue, Ieșilor, alias",
            "'ул. Чеукарь', street, Ceucari, alias",
            "'ул. Гренобле', street, Grenoble, fuzzy",
            "'пр-т Мирча чел Бэтрын', boulevard, Mircea cel Bătrân, alias",
            // incomplete or abbreviated
            "'Bd. Ștefan cel Mare', boulevard, Ștefan cel Mare și Sfânt, alias",
            "'M.Sadoveanu', street, Mihail Sadoveanu, partial",
            "'str. Buzdugan', street, Ion Buzdugan, partial",
            "'str. L. Deleanu', street, Liviu Deleanu, partial",
            // repeated or with the district and city
            "'str. Nicolae Testemitanu;str. Nicolae Testemițanu', street, Nicolae Testemițanu, exact",
            "'str. Vasile Lupu, Buiucani, Chișinău', street, Vasile Lupu, exact",
    })
    void streets(String raw, String type, String name, String method) {
        var a = chisinau(raw, "1");
        assertThat(a.streetName()).isEqualTo(name);
        assertThat(a.streetMatch()).isEqualTo(method);
        if (type != null) {
            assertThat(a.streetType()).isEqualTo(type);
        }
    }

    @Test
    void sameNameDifferentTypes() {
        // Chișinău has Bulevardul Mircea cel Bătrân and several Strada Mircea cel Bătrân in its suburbs.
        assertThat(chisinau("bd. Mircea cel Bătrân", "4").streetType()).isEqualTo("boulevard");
        var noType = chisinau("Mircea cel Bătrân", "4");
        assertThat(noType.streetName()).isEqualTo("Mircea cel Bătrân");
        assertThat(noType.streetType()).isNull();
        assertThat(noType.street()).isNull(); // no coordinates: which street is meant is unknown
        // The seller's pin tells them apart: this one is on the boulevard in Ciocana.
        var pinned = addresses.normalize("Mircea cel Bătrân", "4", chisinau,
                new md.apartmentlens.scraper.source.GeoPoint(47.0515, 28.8908), new Issues());
        assertThat(pinned.streetType()).isEqualTo("boulevard");
    }

    @Test
    void houseNumberTypedInTheStreetField() {
        var comma = chisinau("str. Mihail Lomonosov, 39", "39");
        assertThat(comma.streetName()).isEqualTo("Mihail Lomonosov");
        assertThat(comma.houseNumber()).isEqualTo("39");

        var only = chisinau("Alexandru cel Bun 16", "10000");
        assertThat(only.streetName()).isEqualTo("Alexandru cel Bun");
        assertThat(only.houseNumber()).isEqualTo("16");

        var letter = chisinau("Decebal 124", "124 F");
        assertThat(letter.houseNumber()).isEqualTo("124F");
    }

    @Test
    void numbersThatArePartOfTheName() {
        var a = chisinau("str. 31 August 1989", ".");
        assertThat(a.streetName()).isEqualTo("31 August 1989");
        assertThat(a.houseNumber()).isNull();
    }

    @Test
    void theVillageNameIsNotAStreet() {
        Place truseni = places.settlement("Trușeni", "Chișinău mun.", null).orElseThrow();
        var a = addresses.normalize("Trușeni", "1", truseni, null, new Issues());
        assertThat(a.streetName()).isNull();
        assertThat(a.houseNumber()).isEqualTo("1");
    }

    @Test
    void unknownStreetsAreKeptAsWritten() {
        Issues issues = new Issues();
        var a = addresses.normalize("str. Inexistentă Complet", "5", chisinau, null, issues);
        assertThat(a.streetMatch()).isEqualTo("unresolved");
        assertThat(a.streetName()).isEqualTo("Inexistentă Complet");
        assertThat(a.street()).isNull();
        assertThat(issues.has("street")).isTrue();
    }

    @Test
    void hiddenValues() {
        Issues issues = new Issues();
        var a = addresses.normalize("-", ".", chisinau, null, issues);
        assertThat(a.streetName()).isNull();
        assertThat(a.houseNumber()).isNull();
    }

    @Test
    void localityLabels() {
        assertThat(places.settlement("Bender/Tighina", null, null)).isPresent();
        assertThat(PlaceGazetteer.placeKey("Chișinău mun.")).isEqualTo("chisinau");
        assertThat(PlaceGazetteer.placeKey("r-nul Ialoveni")).isEqualTo("ialoveni");
        assertThat(PlaceGazetteer.placeKey("Ghindești (sat)")).isEqualTo("ghindesti");
        assertThat(PlaceGazetteer.placeKey("Catranîc, loc.st.c.f.")).isEqualTo("catranac");
        assertThat(places.settlement("Dnestrovsk", "Slobozia", null)).isPresent();
    }
}
