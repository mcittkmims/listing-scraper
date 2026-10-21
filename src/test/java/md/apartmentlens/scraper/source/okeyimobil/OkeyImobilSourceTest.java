package md.apartmentlens.scraper.source.okeyimobil;

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
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;

/** An OkeyImobil property (synthetic, in the API's shape) through the source and the normalizer. */
class OkeyImobilSourceTest {

    static final OkeyImobilSource SOURCE = new OkeyImobilSource(null, Fixtures.JSON);
    static ListingNormalizer normalizer;

    @BeforeAll
    static void setUp() {
        normalizer = new ListingNormalizer(new PriceNormalizer((currency, date) -> Optional.of(BigDecimal.ONE)),
                Fixtures.places(), Fixtures.streets(), new PhoneNormalizer("test-key-0123456789abcdef"),
                DefaultPins.NONE);
    }

    static RawAd raw() {
        return SOURCE.toRaw(Fixtures.JSON.readTree(Fixtures.bytes("okeyimobil", "apartment.json")));
    }

    @Test
    void ofTheInternalNotesOnlyThe999LinkIsKept() {
        String stored = new String(raw().body(), StandardCharsets.UTF_8);
        assertThat(stored).doesNotContain("Test Owner", "069000002", "\"notes\"", "\"similar\"", "wp_post_id")
                .contains("https://999.md/ro/100000201");
        assertThat(SOURCE.parse(raw()).crosslinks()).containsExactly("999md:100000201");
    }

    @Test
    void anApartment() {
        SourceAd ad = SOURCE.parse(raw());
        assertThat(ad.url()).isEqualTo("https://okeyimobil.md/properties/900001");
        NormalizedListing l = normalizer.normalize(ad, LocalDate.of(2026, 10, 21));
        assertThat(l.propertyType()).isEqualTo("apartment");
        assertThat(l.sellerType()).isEqualTo("agency");
        assertThat(l.sellerName()).isEqualTo("OkeyImobil");
        assertThat(l.priceAmount()).isEqualByComparingTo("179000");
        assertThat(l.priceCurrency()).isEqualTo("EUR");
        assertThat(l.rooms()).isEqualTo(3); // as published, the living room included; not guessed into "2 + living"
        assertThat(l.hasLivingRoom()).isNull();
        assertThat(l.areaTotalM2()).isEqualByComparingTo("112");
        assertThat(l.floor()).isEqualTo(10);
        assertThat(l.floorsTotal()).isEqualTo(12);
        assertThat(l.condition()).isEqualTo("euro_renovation");
        assertThat(l.housingStock()).isEqualTo("secondary");
        assertThat(l.locality()).isEqualTo("Chișinău");
        assertThat(l.district()).isEqualTo("Botanica");
        assertThat(l.streetName()).isEqualTo("Grenoble");
        assertThat(l.phoneHashes()).hasSize(1); // "79000001", without the leading 0
        assertThat(l.amenities()).contains("autonomous_heating", "double_glazing", "elevator", "armored_door", "intercom");
        assertThat(l.issues()).isEmpty();
    }

    @Test
    void aTownWrittenAsTheAddressOfAnOtherTownsAd() {
        ObjectNode ad = (ObjectNode) Fixtures.JSON.readTree(Fixtures.bytes("okeyimobil", "apartment.json"));
        ad.put("address", "Strășeni");
        ((ObjectNode) ad.path("location")).put("name", "alte").put("region_id", 24);
        NormalizedListing l = normalizer.normalize(SOURCE.parse(SOURCE.toRaw(ad)), LocalDate.of(2026, 10, 21));
        assertThat(l.locality()).isEqualTo("Strășeni");
        assertThat(l.streetRaw()).isNull();
        assertThat(l.district()).isNull();
    }
}
