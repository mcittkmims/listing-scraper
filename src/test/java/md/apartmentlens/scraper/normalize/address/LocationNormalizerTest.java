package md.apartmentlens.scraper.normalize.address;

import static org.assertj.core.api.Assertions.assertThat;

import md.apartmentlens.scraper.normalize.Issues;
import md.apartmentlens.scraper.normalize.address.PlaceGazetteer.Place;
import md.apartmentlens.scraper.source.GeoPoint;
import org.junit.jupiter.api.Test;

class LocationNormalizerTest {

    final LocationNormalizer locations = new LocationNormalizer();
    final Place balti = new Place("Bălți", "Бельцы", "city", new GeoPoint(47.7615, 27.9293), 102_457);
    final Place chisinau = new Place("Chișinău", "Кишинёв", "city", new GeoPoint(47.0245, 28.8323), 756_900);

    @Test
    void defaultPinFarFromTheLocalityIsDropped() {
        // Ads in Bălți with the pin 999.md places by default in central Chișinău.
        Issues issues = new Issues();
        assertThat(locations.checkPin(new GeoPoint(47.02301, 28.83368), balti, issues)).isNull();
        assertThat(issues.list()).hasSize(1);
        assertThat(issues.list().getFirst().reason()).isEqualTo("far_from_locality");
    }

    @Test
    void pinOutsideMoldova() {
        Issues issues = new Issues();
        assertThat(locations.checkPin(new GeoPoint(44.43, 26.10), null, issues)).isNull();
        assertThat(issues.list().getFirst().reason()).isEqualTo("outside_moldova");
    }

    @Test
    void precision() {
        GeoPoint pin = new GeoPoint(47.0581, 28.8480);
        var withNumber = new AddressNormalizer.Address("street", "Ceucari", "exact", "1", null);
        var withoutNumber = new AddressNormalizer.Address("street", "Ceucari", "exact", null, null);
        assertThat(locations.locate(pin, withNumber, null, chisinau, new Issues()).precision()).isEqualTo("building");
        assertThat(locations.locate(pin, withoutNumber, null, chisinau, new Issues()).precision()).isEqualTo("street");
        var none = locations.locate(null, AddressNormalizer.Address.NONE, null, chisinau, new Issues());
        assertThat(none.precision()).isEqualTo("locality");
        assertThat(none.point()).isEqualTo(chisinau.point());
    }
}
