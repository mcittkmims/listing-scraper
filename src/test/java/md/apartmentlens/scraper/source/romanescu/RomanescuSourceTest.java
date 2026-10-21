package md.apartmentlens.scraper.source.romanescu;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Optional;
import md.apartmentlens.scraper.Fixtures;
import md.apartmentlens.scraper.normalize.ListingNormalizer;
import md.apartmentlens.scraper.normalize.NormalizedListing;
import md.apartmentlens.scraper.normalize.PhoneNormalizer;
import md.apartmentlens.scraper.normalize.PriceNormalizer;
import md.apartmentlens.scraper.normalize.address.DefaultPins;
import md.apartmentlens.scraper.source.RawAd;
import md.apartmentlens.scraper.source.SourceAd;
import org.jsoup.Jsoup;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;

/** A Romanescu ad page (synthetic, in the site's markup) through the extract, the parser and the normalizer. */
class RomanescuSourceTest {

    static final RomanescuSource SOURCE = new RomanescuSource(null, Fixtures.JSON);
    static ListingNormalizer normalizer;

    @BeforeAll
    static void setUp() {
        normalizer = new ListingNormalizer(new PriceNormalizer((currency, date) -> Optional.of(BigDecimal.ONE)),
                Fixtures.places(), Fixtures.streets(), new PhoneNormalizer("test-key-0123456789abcdef"),
                DefaultPins.NONE);
    }

    static ObjectNode extract() {
        String html = new String(Fixtures.bytes("romanescu", "apartment.html"), StandardCharsets.UTF_8);
        return SOURCE.extract(Jsoup.parse(html, RomanescuSource.SITE));
    }

    @Test
    void theSitesMalformedStructuredDataIsRepaired() {
        ObjectNode page = extract();
        assertThat(page.path("offer").path("price").asString()).isEqualTo("191250");
        assertThat(page.path("offer").path("itemOffered").path("image")).hasSize(2); // trailing comma
        assertThat(page.path("breadcrumbs").valueStream().map(n -> n.asString()).toList())
                .containsExactly("Vanzare", "Apartamente", "Chisinau", "Botanica"); // repeated keys
    }

    @Test
    void anApartment() {
        SourceAd ad = SOURCE.parse(new RawAd("900001", Fixtures.JSON.writeValueAsBytes(extract())));
        assertThat(ad.externalId()).isEqualTo("900001");
        assertThat(ad.phones()).containsExactly("+37369000004"); // the agent's, not the agency's
        assertThat(ad.pinRadiusM()).isEqualTo(300);
        NormalizedListing l = normalizer.normalize(ad, LocalDate.of(2026, 10, 21));
        assertThat(l.propertyType()).isEqualTo("apartment");
        assertThat(l.priceAmount()).isEqualByComparingTo("191250");
        assertThat(l.rooms()).isEqualTo(3);
        assertThat(l.areaTotalM2()).isEqualByComparingTo("85");
        assertThat(l.floor()).isEqualTo(10);
        assertThat(l.floorsTotal()).isNull(); // "P+13" on a block: 13 or 14 floors, not guessed
        assertThat(l.buildingMaterial()).isEqualTo("brick");
        assertThat(l.locality()).isEqualTo("Chișinău");
        assertThat(l.district()).isEqualTo("Botanica");
        assertThat(l.streetName()).isEqualTo("Cuza-Vodă");
        assertThat(l.amenities()).containsExactly("autonomous_heating", "elevator", "gas");
    }

    @Test
    void aStreetIsTakenFromTheTitleOnlyWithItsType() {
        assertThat(RomanescuParser.street("Casă spațioasă în Durlești | str. Plopilor | 175 mp | 3 ari")).isEqualTo("str. Plopilor");
        assertThat(RomanescuParser.street("Alba Iulia 77/4, Gonvaro, apartament cu 2 camere + living")).isNull();
    }

    @Test
    void theHeightOfAHouse() {
        assertThat(RomanescuParser.floorsTotal("P+1")).isEqualTo("2");
        assertThat(RomanescuParser.floorsTotal("P+1+M")).isEqualTo("3");
        assertThat(RomanescuParser.floorsTotal("P")).isEqualTo("1");
        assertThat(RomanescuParser.floorsTotal("P+13")).isNull();
    }
}
