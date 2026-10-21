package md.apartmentlens.scraper.source.realist;

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

/** Realist ad pages (synthetic, in the site's markup) through the extract, the parser and the normalizer. */
class RealistSourceTest {

    static final RealistSource SOURCE = new RealistSource(null, Fixtures.JSON);
    static ListingNormalizer normalizer;

    @BeforeAll
    static void setUp() {
        normalizer = new ListingNormalizer(new PriceNormalizer((currency, date) -> Optional.of(BigDecimal.ONE)),
                Fixtures.places(), Fixtures.streets(), new PhoneNormalizer("test-key-0123456789abcdef"),
                DefaultPins.NONE);
    }

    static SourceAd ad(String name, String id) {
        String html = new String(Fixtures.bytes("realist", name), StandardCharsets.UTF_8);
        RawAd raw = new RawAd(id, Fixtures.JSON.writeValueAsBytes(SOURCE.extract(Jsoup.parse(html, RealistSource.SITE))));
        return SOURCE.parse(raw);
    }

    @Test
    void anApartment() {
        SourceAd ad = ad("apartment.html", "900001");
        assertThat(ad.externalId()).isEqualTo("900001");
        assertThat(ad.phones()).containsExactly("+37369000003"); // the agent's, not the office's
        assertThat(ad.pinRadiusM()).isEqualTo(RealistParser.FUZZY_RADIUS_M); // the site moves fuzzy pins
        NormalizedListing l = normalizer.normalize(ad, LocalDate.of(2026, 10, 21));
        assertThat(l.propertyType()).isEqualTo("apartment");
        assertThat(l.priceAmount()).isEqualByComparingTo("160000");
        assertThat(l.rooms()).isEqualTo(3);
        assertThat(l.areaTotalM2()).isEqualByComparingTo("66.23");
        assertThat(l.floor()).isEqualTo(2);
        assertThat(l.floorsTotal()).isEqualTo(12);
        assertThat(l.housingStock()).isEqualTo("new_build");
        assertThat(l.locality()).isEqualTo("Chișinău");
        assertThat(l.district()).isEqualTo("Botanica");
        assertThat(l.streetName()).isEqualTo("Grenoble");
        assertThat(l.locationPrecision()).isEqualTo("street"); // the fuzzy pin is not the building
        assertThat(l.photoKeys()).hasSize(2);
        assertThat(l.issues()).isEmpty();
    }

    @Test
    void aHouseWithItsPlotInAriLabelledMp() {
        SourceAd ad = ad("house.html", "900002");
        assertThat(ad.pinRadiusM()).isNull(); // data-fuzzy="False": the pin is on the property
        NormalizedListing l = normalizer.normalize(ad, LocalDate.of(2026, 10, 21));
        assertThat(l.propertyType()).isEqualTo("duplex");
        assertThat(l.landAreaM2()).isEqualByComparingTo("300"); // "3 mp" is 3 ari
        assertThat(l.locality()).isEqualTo("Tohatin");
        assertThat(l.streetName()).isEqualTo("Sfânta Vineri");
    }

    @Test
    void theStreetIsThePartOfTheTitleWithAStreetType() {
        assertThat(RealistParser.street("Vânzare, apartament, 2 camere, strada Grenoble, Botanica")).isEqualTo("strada Grenoble");
        assertThat(RealistParser.street("Vânzare, casă, 5 camere, Poiana Silvică")).isNull();
    }
}
