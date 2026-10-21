package md.apartmentlens.scraper.normalize.address;

import static org.assertj.core.api.Assertions.assertThat;

import md.apartmentlens.scraper.Fixtures;
import md.apartmentlens.scraper.normalize.Issues;
import md.apartmentlens.scraper.normalize.address.PlaceGazetteer.Place;
import md.apartmentlens.scraper.source.GeoPoint;
import org.junit.jupiter.api.Test;

class LocationNormalizerTest {

    static final StreetGazetteer STREETS = Fixtures.streets();
    final LocationNormalizer locations = new LocationNormalizer(STREETS, pin -> pin.lat() == 47.0275 && pin.lon() == 28.8317);
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
    void defaultPinsAreNotUsed() {
        Issues issues = new Issues();
        assertThat(locations.checkPin(new GeoPoint(47.0275, 28.8317), chisinau, issues)).isNull();
        assertThat(issues.list().getFirst().reason()).isEqualTo("default_position");
    }

    @Test
    void theBuildingOfTheAddressWhenThePinIsMissingOrFarFromIt() {
        GeoPoint building = new GeoPoint(47.06907, 28.89136);
        var address = new AddressNormalizer.Address("street", "Bucovina", "exact", "5", null, building);
        var noPin = locations.locate(null, address, null, chisinau, new Issues());
        assertThat(noPin).isEqualTo(new LocationNormalizer.Location(building, "address", "building"));
        Issues issues = new Issues();
        var farPin = locations.locate(new GeoPoint(47.0275, 28.8317), address, null, chisinau, issues);
        assertThat(farPin.source()).isEqualTo("address");
        assertThat(issues.list().getFirst().reason()).isEqualTo("far_from_address");
        var nearPin = locations.locate(new GeoPoint(47.06910, 28.89140), address, null, chisinau, new Issues());
        assertThat(nearPin.source()).isEqualTo("pin");
    }

    @Test
    void aPinThatOnlyShowsTheStreet() {
        GeoPoint streetPin = new GeoPoint(46.98810, 28.85863);
        LocationNormalizer withStreetPins = new LocationNormalizer(STREETS, new DefaultPins() {
            @Override
            public boolean isDefault(GeoPoint pin) {
                return false;
            }

            @Override
            public boolean isStreetLevel(GeoPoint pin) {
                return pin.equals(streetPin);
            }
        });
        GeoPoint building = new GeoPoint(46.9850, 28.8600);
        var known = new AddressNormalizer.Address("boulevard", "Dacia", "exact", "47/2", null, building);
        assertThat(withStreetPins.locate(streetPin, known, null, chisinau, new Issues()))
                .isEqualTo(new LocationNormalizer.Location(building, "address", "building"));
        var unknown = new AddressNormalizer.Address("boulevard", "Dacia", "exact", "999", null, null);
        assertThat(withStreetPins.locate(streetPin, unknown, null, chisinau, new Issues()).precision()).isEqualTo("street");
    }

    @Test
    void precision() {
        GeoPoint pin = new GeoPoint(47.0581, 28.8480);
        var withNumber = new AddressNormalizer.Address("street", "Ceucari", "exact", "1", null, null);
        var withoutNumber = new AddressNormalizer.Address("street", "Ceucari", "exact", null, null, null);
        assertThat(locations.locate(pin, withNumber, null, chisinau, new Issues()).precision()).isEqualTo("building");
        assertThat(locations.locate(pin, withoutNumber, null, chisinau, new Issues()).precision()).isEqualTo("street");
        var none = locations.locate(null, AddressNormalizer.Address.NONE, null, chisinau, new Issues());
        assertThat(none.precision()).isEqualTo("locality");
        assertThat(none.point()).isEqualTo(chisinau.point());
    }
}
