package md.apartmentlens.scraper.normalize;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import md.apartmentlens.scraper.Fixtures;
import md.apartmentlens.scraper.normalize.address.DefaultPins;
import md.apartmentlens.scraper.normalize.address.PlaceGazetteer;
import md.apartmentlens.scraper.normalize.address.StreetGazetteer;
import md.apartmentlens.scraper.source.ninetynine.NineNineSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** A whole 999.md ad through the parser and every normalizer. */
class ListingNormalizerTest {

    static ListingNormalizer normalizer;
    static final NineNineSource SOURCE = new NineNineSource(null, JsonMapper.builder().build(), 0);

    @BeforeAll
    static void setUp() {
        normalizer = new ListingNormalizer(
                new PriceNormalizer((currency, date) -> Optional.of(switch (currency) {
                    case "EUR" -> new BigDecimal("20.0000");
                    case "USD" -> new BigDecimal("18.0000");
                    default -> BigDecimal.ONE;
                })),
                PlaceGazetteer.load(), StreetGazetteer.load(),
                new PhoneNormalizer("test-key-0123456789abcdef"), DefaultPins.NONE);
    }

    @Test
    void apartment() throws IOException {
        NormalizedListing l = normalizer.normalize(SOURCE.parse(Fixtures.ninetyNine("apartment.json")),
                LocalDate.of(2026, 10, 19));
        assertThat(l.propertyType()).isEqualTo("apartment");
        assertThat(l.sellerType()).isEqualTo("owner");
        assertThat(l.priceEur()).isEqualByComparingTo("99990");
        assertThat(l.rooms()).isEqualTo(2);
        assertThat(l.hasLivingRoom()).isTrue();
        assertThat(l.areaTotalM2()).isEqualByComparingTo("66.38"); // precise value from the description
        assertThat(l.pricePerM2Eur()).isEqualByComparingTo("1506.33");
        assertThat(l.floor()).isEqualTo(7);
        assertThat(l.floorsTotal()).isEqualTo(9);
        assertThat(l.housingStock()).isEqualTo("new_build");
        assertThat(l.condition()).isEqualTo("white_shell");
        assertThat(l.buildingMaterial()).isEqualTo("monolith");
        assertThat(l.buildingSeries()).isEqualTo("individual");
        assertThat(l.parking()).isEqualTo("underground");
        assertThat(l.bathrooms()).isEqualTo(1);
        assertThat(l.balconies()).isZero();
        assertThat(l.ceilingHeightM()).isEqualByComparingTo("2.70");
        assertThat(l.region()).isEqualTo("Chișinău");
        assertThat(l.locality()).isEqualTo("Chișinău");
        assertThat(l.district()).isEqualTo("Poșta Veche");
        assertThat(l.streetType()).isEqualTo("street");
        assertThat(l.streetName()).isEqualTo("Ceucari");
        assertThat(l.houseNumber()).isEqualTo("1");
        assertThat(l.locationSource()).isEqualTo("pin");
        assertThat(l.locationPrecision()).isEqualTo("building");
        assertThat(l.phoneHashes()).hasSize(1); // the same number twice
        assertThat(l.amenities()).containsExactly("autonomous_heating", "elevator");
        assertThat(l.issues()).isEmpty();
    }

    @Test
    void house() throws IOException {
        NormalizedListing l = normalizer.normalize(SOURCE.parse(Fixtures.ninetyNine("house.json")),
                LocalDate.of(2026, 10, 19));
        assertThat(l.propertyType()).isEqualTo("townhouse");
        assertThat(l.sellerType()).isEqualTo("agency");
        assertThat(l.sellerName()).isEqualTo("Test Agency SRL");
        assertThat(l.priceCurrency()).isEqualTo("USD");
        assertThat(l.priceEur()).isEqualByComparingTo("76500");
        assertThat(l.rooms()).isEqualTo(4);
        assertThat(l.landAreaM2()).isEqualByComparingTo("600");
        assertThat(l.floor()).isNull();
        assertThat(l.floorsTotal()).isEqualTo(2);
        assertThat(l.houseNumber()).isNull(); // "---": hidden
        assertThat(l.houseNumberRaw()).isEqualTo("---");
        // No pin: the street's centre if the street is known in Ialoveni, else the town centre.
        assertThat(l.locationSource()).isIn("street", "place");
        assertThat(l.point()).isNotNull();
        // Living area 120 m² in a 100 m² house contradicts itself and is dropped.
        assertThat(l.areaLivingM2()).isNull();
        assertThat(l.issues()).extracting(Issue::reason).contains("larger_than_total");
    }
}
