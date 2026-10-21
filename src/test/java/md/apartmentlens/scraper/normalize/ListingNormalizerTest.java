package md.apartmentlens.scraper.normalize;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import md.apartmentlens.scraper.Fixtures;
import md.apartmentlens.scraper.normalize.address.DefaultPins;
import md.apartmentlens.scraper.source.Coded;
import md.apartmentlens.scraper.source.GeoPoint;
import md.apartmentlens.scraper.source.RawPrice;
import md.apartmentlens.scraper.source.SourceAd;
import md.apartmentlens.scraper.source.SourceCategory;
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
                Fixtures.places(), Fixtures.streets(),
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

    @Test
    void aPinPlacedOnlyNearThePropertyNeverPicksTheStreetOrTheBuilding() {
        GeoPoint nearDacia = new GeoPoint(46.9895, 28.8570);
        // With an address, the address places the ad; the pin is not taken as the building.
        NormalizedListing withAddress = normalizer.normalize(agencyAd("bd. Dacia", "47/2", nearDacia, 300),
                LocalDate.of(2026, 10, 19));
        assertThat(withAddress.streetName()).isEqualTo("Dacia");
        assertThat(withAddress.locationSource()).isEqualTo("address");
        assertThat(withAddress.locationPrecision()).isEqualTo("building");
        assertThat(withAddress.pinRadiusM()).isEqualTo(300);

        // Without a street, no street is taken from the pin; the pin places the ad roughly.
        NormalizedListing withoutStreet = normalizer.normalize(agencyAd(null, null, nearDacia, 300),
                LocalDate.of(2026, 10, 19));
        assertThat(withoutStreet.streetName()).isNull();
        assertThat(withoutStreet.locationSource()).isEqualTo("pin");
        assertThat(withoutStreet.locationPrecision()).isEqualTo("district");

        // A circle wider than a kilometre is no better than the town centre.
        NormalizedListing wide = normalizer.normalize(agencyAd(null, null, nearDacia, 5000),
                LocalDate.of(2026, 10, 19));
        assertThat(wide.locationSource()).isEqualTo("place");
    }

    private static SourceAd agencyAd(String street, String number, GeoPoint pin, int radiusM) {
        return SourceAd.builder().externalId("1").url("https://example.md/1").category(SourceCategory.APARTMENTS)
                .offerType("Vânzare").rooms("2").price(new RawPrice(new BigDecimal("80000"), "EUR", null, null, null))
                .region(new Coded(null, "Chișinău")).locality(new Coded(null, "Chișinău"))
                .street(street).houseNumber(number).pin(pin).pinRadiusM(radiusM).build();
    }
}
