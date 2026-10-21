package md.apartmentlens.scraper.source.proimobil;

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
import md.apartmentlens.scraper.normalize.address.PlaceGazetteer;
import md.apartmentlens.scraper.normalize.address.StreetGazetteer;
import md.apartmentlens.scraper.source.RawAd;
import md.apartmentlens.scraper.source.SourceAd;
import md.apartmentlens.scraper.source.SourceCategory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;

/** Proimobil properties (synthetic, in the API's shape) through the source and the normalizer. */
class ProimobilSourceTest {

    static final ProimobilSource SOURCE = new ProimobilSource(null, Fixtures.JSON);
    static ListingNormalizer normalizer;

    @BeforeAll
    static void setUp() {
        normalizer = new ListingNormalizer(new PriceNormalizer((currency, date) -> Optional.of(BigDecimal.ONE)),
                PlaceGazetteer.load(), StreetGazetteer.load(), new PhoneNormalizer("test-key-0123456789abcdef"),
                DefaultPins.NONE);
    }

    static RawAd raw(String name) {
        return SOURCE.toRaw(Fixtures.JSON.readTree(Fixtures.bytes("proimobil", name)));
    }

    @Test
    void theOwnersPrivateContactAndAgentsInternalFieldsAreNeverStored() {
        String stored = new String(raw("apartment.json").body(), StandardCharsets.UTF_8);
        assertThat(stored).doesNotContain("Test Owner", "069000000", "private note", "propertyIds", "agent@example.md",
                "credentialId", "text of the 999.md ad");
        assertThat(stored).contains("060000001"); // the agent's published phone
    }

    @Test
    void anApartment() {
        RawAd raw = raw("apartment.json");
        assertThat(raw.externalId()).isEqualTo("00000000-0000-4000-8000-000000000001");
        SourceAd ad = SOURCE.parse(raw);
        assertThat(ad.url()).isEqualTo("https://proimobil.md/apartamente-in-chisinau/" + "vinzare-chisinau-botanica-dacia-"
                + ad.url().substring(ad.url().lastIndexOf('-') + 1));
        assertThat(ad.category()).isEqualTo(SourceCategory.APARTMENTS);
        assertThat(ad.crosslinks()).containsExactly("999md:100000101");
        assertThat(ad.phones()).containsExactly("060000001");
        assertThat(ad.photoKeys()).containsExactly("cover-1.jpeg", "photo-1-1.jpeg", "photo-1-2.jpeg");
        // Paragraphs are separated by a blank line, line breaks kept.
        assertThat(ad.descriptionRo()).isEqualTo("Vă propunem spre vânzare acest apartament cu 3 camere.\n\n"
                + "Bloc seria 143.\nReparație euro.");

        NormalizedListing l = normalizer.normalize(ad, LocalDate.of(2026, 10, 21));
        assertThat(l.propertyType()).isEqualTo("apartment");
        assertThat(l.sellerType()).isEqualTo("agency");
        assertThat(l.priceAmount()).isEqualByComparingTo("122900");
        assertThat(l.priceCurrency()).isEqualTo("EUR");
        assertThat(l.rooms()).isEqualTo(3);
        assertThat(l.areaTotalM2()).isEqualByComparingTo("66");
        assertThat(l.floor()).isEqualTo(9);
        assertThat(l.floorsTotal()).isEqualTo(10);
        assertThat(l.condition()).isEqualTo("euro_renovation");
        assertThat(l.buildingSeries()).isEqualTo("143");
        assertThat(l.housingStock()).isEqualTo("secondary");
        assertThat(l.parking()).isEqualTo("open");
        assertThat(l.locality()).isEqualTo("Chișinău");
        assertThat(l.district()).isEqualTo("Botanica");
        assertThat(l.streetName()).isEqualTo("Dacia");
        assertThat(l.locationPrecision()).isEqualTo("street"); // no house number, no pin
        assertThat(l.amenities()).containsExactly("armored_door", "autonomous_heating", "elevator", "furnished",
                "move_in_ready", "playground");
        assertThat(l.crosslinks()).containsExactly("999md:100000101");
        assertThat(l.issues()).isEmpty();
    }

    @Test
    void aHouseInAVillageTheAgencyWritesWhereTheStreetGoes() {
        // City and region "Criuleni" (the district), address "Hrușova" (the village).
        NormalizedListing l = normalizer.normalize(SOURCE.parse(raw("house.json")), LocalDate.of(2026, 10, 21));
        assertThat(l.propertyType()).isEqualTo("house");
        assertThat(l.locality()).isEqualTo("Hrușova");
        assertThat(l.streetName()).isNull();
        assertThat(l.landAreaM2()).isEqualByComparingTo("3000"); // "30 ar", although the API says m2
        assertThat(l.condition()).isEqualTo("habitable");
        assertThat(l.locationSource()).isEqualTo("place");
        assertThat(l.amenities()).contains("garage", "cellar", "sauna", "garden", "appliances");
    }

    @Test
    void viewsPromotionAndSignedImageLinksAreNotChanges() {
        ObjectNode ad = (ObjectNode) Fixtures.JSON.readTree(Fixtures.bytes("proimobil", "apartment.json"));
        byte[] before = SOURCE.stableContent(SOURCE.toRaw(ad));
        ad.put("views", 99).put("isHot", true).put("updatedAt", "2026-10-22T00:00:00.000Z");
        ((ObjectNode) ad.path("assets").path("image")).put("md", "https://images.proimobil.md/signed/other");
        assertThat(SOURCE.stableContent(SOURCE.toRaw(ad))).isEqualTo(before);
        ad.putObject("price").put("amount", 120000).put("currency", "€");
        assertThat(SOURCE.stableContent(SOURCE.toRaw(ad))).isNotEqualTo(before);
    }

    @Test
    void propertiesInRomaniaAreLeftOut() {
        ObjectNode ad = (ObjectNode) Fixtures.JSON.readTree(Fixtures.bytes("proimobil", "apartment.json"));
        assertThat(SOURCE.inMoldova(ad)).isTrue();
        ((ObjectNode) ad.path("_embedded").path("city")).put("surrogateId", 48); // București
        assertThat(SOURCE.inMoldova(ad)).isFalse();
        ((ObjectNode) ad.path("_embedded").path("city")).put("surrogateId", 99); // a city not known yet
        assertThat(SOURCE.inMoldova(ad)).isFalse();
    }
}
