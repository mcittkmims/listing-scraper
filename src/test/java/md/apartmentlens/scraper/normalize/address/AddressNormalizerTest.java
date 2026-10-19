package md.apartmentlens.scraper.normalize.address;

import static org.assertj.core.api.Assertions.assertThat;

import md.apartmentlens.scraper.normalize.Issues;
import md.apartmentlens.scraper.normalize.address.PlaceGazetteer.Place;
import md.apartmentlens.scraper.source.GeoPoint;
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
        addresses = new AddressNormalizer(StreetGazetteer.load(), places);
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
            "'ул. Гренобле', street, Grenoble, alias",
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
        // Bulevardul Mircea cel Bătrân is in Chișinău; the Strada Mircea cel Bătrân around it belong to
        // suburbs (Codru, Stăuceni, ...), so an ad in Chișinău means the boulevard.
        assertThat(chisinau("bd. Mircea cel Bătrân", "4").streetType()).isEqualTo("boulevard");
        assertThat(chisinau("Mircea cel Bătrân", "4").streetType()).isEqualTo("boulevard");
        // Where one locality has both, the type stays open without a pin: Strada and Bulevardul Dacia.
        var noType = chisinau("Dacia", "4");
        assertThat(noType.streetName()).isEqualTo("Dacia");
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
        var a = addresses.normalize("-", ".", chisinau, null, issues); // no pin: nothing to go on
        assertThat(a.streetName()).isNull();
        assertThat(a.houseNumber()).isNull();
    }

    AddressNormalizer.Address pinned(String street, String house, double lat, double lon) {
        return addresses.normalize(street, house, chisinau, new GeoPoint(lat, lon), new Issues());
    }

    @Test
    void thePinChoosesBetweenStreetsWithTheSameRussianName() {
        // "Буковина" is the Russian name of both Strada Bucovina and Strada Bucovinei in Chișinău;
        // these real 999.md pins stand on Strada Bucovina, in Ciocana.
        assertThat(pinned("ул. Буковина", "5", 47.06907, 28.891363).streetName()).isEqualTo("Bucovina");
        assertThat(pinned("ул. Буковина", "2", 47.067783, 28.883339).streetName()).isEqualTo("Bucovina");
        // Without a pin nothing tells them apart: not guessed.
        assertThat(chisinau("ул. Буковина", "5").streetMatch()).isEqualTo("unresolved");
    }

    @Test
    void aNumberedLaneIsAnotherStreet() {
        var a = pinned("ш. Мунчешть", "404", 46.981403, 28.8975);
        assertThat(a.streetName()).isEqualTo("Muncești");
        assertThat(a.streetType()).isEqualTo("highway");
    }

    @Test
    void anExactTransliterationIsAnExactMatch() {
        var a = pinned("ул. Яловень", "136/B", 47.003624, 28.77253);
        assertThat(a.streetName()).isEqualTo("Ialoveni");
        assertThat(a.streetMatch()).isIn("exact", "alias");
    }

    @Test
    void theBuildingOfAnAddressComesFromTheMap() {
        var a = pinned("bd. Dacia", "47/2", 46.9881, 28.85863);
        assertThat(a.building()).isNotNull();
        assertThat(chisinau("bd. Dacia", "9999").building()).isNull(); // a number the map does not have
    }

    @Test
    void looserSpellingOnlyAmongTheBuildingsAroundThePin() {
        StreetGazetteer streets = addresses.streets();
        GeoPoint onBucovina = new GeoPoint(47.06907, 28.891363);
        assertThat(streets.resolveAtPin("Bucovina", null, onBucovina)).hasValueSatisfying(
                m -> assertThat(m.method()).isEqualTo("nearby"));
        // A name unlike every street around the pin is not forced onto one of them.
        assertThat(streets.resolveAtPin("Columna", null, onBucovina)).isEmpty();
    }

    @Test
    void streetsOnTheCitysEdgeAreFoundEvenWhenTheyBelongToASuburb() {
        // Sellers list Strada Regina Elisabeta (Durlești) as "Chișinău".
        assertThat(chisinau("str. Regina Elisabeta", "4").streetMatch()).isEqualTo("exact");
    }

    @Test
    void streetsOfNeighbouringVillagesAreNotUsedWithoutAPin() {
        // Cricova has its own Strada Mihail Sadoveanu, inside Chișinău's search area; an ad in Chișinău
        // gets Chișinău's street (in Ciocana), an ad in Cricova gets Cricova's.
        var inChisinau = chisinau("str. Mihail Sadoveanu", "1");
        assertThat(inChisinau.street().point().kmTo(new GeoPoint(47.0500, 28.8840))).isLessThan(1.0);
        Place cricova = places.settlement("Cricova", "Chișinău mun.", null).orElseThrow();
        var inCricova = addresses.normalize("str. Mihail Sadoveanu", "1", cricova, null, new Issues());
        assertThat(inCricova.street().point().kmTo(cricova.point())).isLessThan(3.0);
        assertThat(places.ownerOf(inCricova.street().point())).contains(cricova);
    }

    @Test
    void aYearAtTheEndOfTheNameMayBeLeftOut() {
        Place ialoveni = places.settlement("Ialoveni", "Ialoveni", null).orElseThrow();
        var a = addresses.normalize("str. 31 August", "5", ialoveni, null, new Issues());
        assertThat(a.streetName()).isEqualTo("31 August 1989");
    }

    @Test
    void theStreetAtTheSellersPinWhenTheSellerGaveNone() {
        // A hidden street ("-") with the seller's own pin on Strada Bucovina, among its buildings.
        var hidden = pinned("-", ".", 47.06907, 28.891363);
        assertThat(hidden.streetMatch()).isEqualTo("from_pin");
        assertThat(hidden.streetName()).isEqualTo("Bucovina");
        // A pin with no building within 40 m gives nothing.
        assertThat(pinned("-", ".", 46.9600, 28.9500).streetName()).isNull();
    }

    @Test
    void areasWrittenInsteadOfStreets() {
        var cluj = chisinau("Cartierul Cluj", ".");
        assertThat(cluj.areaName()).isEqualTo("Cartierul Cluj");
        assertThat(cluj.streetName()).isNull();
        assertThat(chisinau("ЖК Grand Park", ".").areaName()).isEqualTo("ЖК Grand Park");
        // With the seller's pin among a street's buildings, that street too.
        var pinnedArea = pinned("Complexul locativ Ciocana Nouă", "5", 47.06907, 28.891363);
        assertThat(pinnedArea.areaName()).isNotNull();
        assertThat(pinnedArea.streetMatch()).isEqualTo("from_pin");
        // A real street is not an area.
        assertThat(chisinau("str. Ismail", "1").areaName()).isNull();
    }

    @Test
    void aNamedStreetIsNotReplacedByADifferentOneAtThePin() {
        // The seller wrote a street the map does not know; the pin stands among Bucovina's buildings.
        // Writing a different street there would be a guess, so it stays as written.
        var a = pinned("str. Luceafăr Mare", "5", 47.06907, 28.891363);
        assertThat(a.streetMatch()).isEqualTo("unresolved");
        assertThat(a.streetName()).isEqualTo("Luceafăr Mare");
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
