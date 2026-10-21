package md.apartmentlens.scraper.source.casamare;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** CasaMare ads (synthetic, in the API's shape) through the source and the normalizer. */
class CasaMareSourceTest {

    static final CasaMareSource SOURCE = new CasaMareSource(null, Fixtures.JSON);
    static ListingNormalizer normalizer;

    @BeforeAll
    static void setUp() {
        normalizer = new ListingNormalizer(new PriceNormalizer((currency, date) -> Optional.of(BigDecimal.ONE)),
                Fixtures.places(), Fixtures.streets(), new PhoneNormalizer("test-key-0123456789abcdef"),
                DefaultPins.NONE);
    }

    static JsonNode ad(String name) {
        return Fixtures.JSON.readTree(Fixtures.bytes("casamare", name));
    }

    @Test
    void theSellersEmailIsNeverStored() {
        RawAd raw = SOURCE.toRaw(ad("apartment.json"));
        assertThat(raw.externalId()).isEqualTo("900001");
        assertThat(new String(raw.body(), StandardCharsets.UTF_8)).doesNotContain("seller@example.md").contains("+37369000001");
    }

    @Test
    void anApartmentWithAnAddressAndAPin() {
        SourceAd ad = SOURCE.parse(SOURCE.toRaw(ad("apartment.json")));
        assertThat(ad.url()).startsWith("https://casamare.md/").endsWith("-900001/");
        assertThat(ad.photoKeys()).containsExactly("https://public-casamare.s3.eu-central-1.amazonaws.com/misc/test-1-1.jpg");
        // The signed link is kept too, for showing the photo while the signature is valid.
        assertThat(ad.photoUrls()).containsExactly(
                "https://public-casamare.s3.eu-central-1.amazonaws.com/misc/test-1-1.jpg?X-Amz-Signature=abc");
        NormalizedListing l = normalizer.normalize(ad, LocalDate.of(2026, 10, 21));
        assertThat(l.propertyType()).isEqualTo("apartment");
        assertThat(l.sellerType()).isEqualTo("owner");
        assertThat(l.priceAmount()).isEqualByComparingTo("59000");
        assertThat(l.rooms()).isEqualTo(1);
        assertThat(l.floor()).isEqualTo(5);
        assertThat(l.streetName()).isEqualTo("Dacia");
        assertThat(l.houseNumber()).isEqualTo("47/2"); // written after the street
        assertThat(l.locationPrecision()).isEqualTo("building");
        assertThat(l.descriptionRu()).isEqualTo("Продаю. Тестовое описание.");
        assertThat(l.phoneHashes()).hasSize(1);
    }

    @Test
    void adsAreReadAgainBeforeTheirSignedPhotoLinksExpire() {
        assertThat(SOURCE.detailMaxAge()).isLessThan(Duration.ofDays(7));
    }

    @Test
    void aHouseWithLandInAri() {
        NormalizedListing l = normalizer.normalize(SOURCE.parse(SOURCE.toRaw(ad("house.json"))), LocalDate.of(2026, 10, 21));
        assertThat(l.propertyType()).isEqualTo("house");
        assertThat(l.landAreaM2()).isEqualByComparingTo("1317"); // 13.17 ari, as the description says
        assertThat(l.locality()).isEqualTo("Ciorescu");
        assertThat(l.condition()).isEqualTo("no_renovation");
        assertThat(l.amenities()).contains("furnished", "gas", "plumbing", "sewerage", "cellar");
    }

    @Test
    void signedPhotoLinksAndViewsAreNotChanges() {
        ObjectNode item = (ObjectNode) ad("apartment.json");
        byte[] summary = SOURCE.summary(item);
        byte[] stable = SOURCE.stableContent(SOURCE.toRaw(item));
        ((ObjectNode) item.path("photos").get(0)).put("fallback",
                "https://public-casamare.s3.eu-central-1.amazonaws.com/misc/test-1-1.jpg?X-Amz-Signature=other");
        item.putObject("views").put("total", 50).put("today", 9);
        assertThat(SOURCE.summary(item)).isNotEqualTo(summary); // views are in the detail, not in the listing
        item.remove("views");
        assertThat(SOURCE.stableContent(SOURCE.toRaw(item))).isEqualTo(stable);
    }
}
