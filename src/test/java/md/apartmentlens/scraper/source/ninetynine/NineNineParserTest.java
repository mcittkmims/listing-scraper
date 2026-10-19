package md.apartmentlens.scraper.source.ninetynine;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.time.Instant;
import md.apartmentlens.scraper.Fixtures;
import md.apartmentlens.scraper.source.Coded;
import md.apartmentlens.scraper.source.GeoPoint;
import md.apartmentlens.scraper.source.RawAd;
import md.apartmentlens.scraper.source.SourceAd;
import md.apartmentlens.scraper.source.SourceCategory;
import org.junit.jupiter.api.Test;

class NineNineParserTest {

    final NineNineSource source = new NineNineSource(null, Fixtures.JSON, 0);

    static RawAd fixture(String name) {
        return Fixtures.ninetyNine(name);
    }

    @Test
    void apartment() throws IOException {
        SourceAd ad = source.parse(fixture("apartment.json"));
        assertThat(ad.externalId()).isEqualTo("100000001");
        assertThat(ad.url()).isEqualTo("https://999.md/ro/100000001");
        assertThat(ad.category()).isEqualTo(SourceCategory.APARTMENTS);
        assertThat(ad.offerType()).isEqualTo("Vând");
        assertThat(ad.sellerType()).isEqualTo("Persoană fizică");
        assertThat(ad.sellerName()).isNull();
        assertThat(ad.price().amount()).isEqualByComparingTo("99990");
        assertThat(ad.price().currency()).isEqualTo("UNIT_EUR");
        assertThat(ad.price().downPayment()).isEqualByComparingTo("15000");
        assertThat(ad.rooms()).isEqualTo("Apartament cu 2 camere");
        assertThat(ad.areaTotal().value()).isEqualByComparingTo("67");
        assertThat(ad.floor()).isEqualTo("7");
        assertThat(ad.region()).isEqualTo(new Coded(12900, "Chișinău mun."));
        assertThat(ad.district()).isEqualTo(new Coded(15670, "Poșta Veche"));
        assertThat(ad.street()).isEqualTo("ул. Чеукарь");
        assertThat(ad.houseNumber()).isEqualTo("1 ");
        assertThat(ad.pin()).isEqualTo(new GeoPoint(47.06786, 28.84016));
        assertThat(ad.phones()).containsExactly("37360000001", "37360000001");
        assertThat(ad.photoKeys()).hasSize(2);
        assertThat(ad.hasVideo()).isFalse();
        assertThat(ad.amenities()).containsExactlyInAnyOrder("autonomous_heating", "elevator");
        assertThat(ad.postedAt()).isEqualTo(Instant.parse("2026-10-19T14:56:53Z"));
    }

    @Test
    void house() throws IOException {
        SourceAd ad = source.parse(fixture("house.json"));
        assertThat(ad.category()).isEqualTo(SourceCategory.HOUSES);
        assertThat(ad.rooms()).isEqualTo("4");
        assertThat(ad.propertyType()).isEqualTo("Townhouse");
        assertThat(ad.landArea().unit()).isEqualTo("UNIT_ARE");
        assertThat(ad.pin()).isNull(); // the map was opened but no pin placed
        assertThat(ad.sellerName()).isEqualTo("Test Agency SRL");
        assertThat(ad.hasVideo()).isTrue();
        assertThat(ad.amenities()).containsExactlyInAnyOrder("garage", "gas");
        assertThat(ad.condition()).isEqualTo("Euroreparație");
    }

    @Test
    void bumpingAnAdDoesNotCountAsAChange() throws IOException {
        RawAd original = fixture("apartment.json");
        String bumped = new String(original.body(), java.nio.charset.StandardCharsets.UTF_8)
                .replace("\"reseted\": \"2026-10-19T14:56:53Z\"", "\"reseted\": \"2026-10-20T08:00:00Z\"");
        RawAd later = new RawAd(original.externalId(), bumped.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThat(source.stableContent(later)).isEqualTo(source.stableContent(original));
    }
}
