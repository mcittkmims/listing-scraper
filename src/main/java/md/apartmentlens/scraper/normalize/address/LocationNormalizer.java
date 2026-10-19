package md.apartmentlens.scraper.normalize.address;

import md.apartmentlens.scraper.normalize.Issues;
import md.apartmentlens.scraper.normalize.address.PlaceGazetteer.Place;
import md.apartmentlens.scraper.source.GeoPoint;

/**
 * The ad's map point and how much to trust it.
 *
 * <ul>
 *   <li>{@code building}: the seller's pin, with a house number given</li>
 *   <li>{@code street}: the seller's pin without a house number, or the recognized street's centre</li>
 *   <li>{@code district}: the centre of the city district</li>
 *   <li>{@code locality}: the centre of the town or village</li>
 * </ul>
 *
 * A pin outside Moldova, or far from the ad's locality (a default pin left by the seller), is dropped.
 */
public final class LocationNormalizer {

    public record Location(GeoPoint point, String source, String precision) {

        static final Location NONE = new Location(null, null, null);
    }

    static final double MIN_LAT = 45.4;
    static final double MAX_LAT = 48.6;
    static final double MIN_LON = 26.6;
    static final double MAX_LON = 30.2;

    /** Returns the pin if it can be used; reports it otherwise. */
    public GeoPoint checkPin(GeoPoint pin, Place locality, Issues issues) {
        if (pin == null) {
            return null;
        }
        if (pin.lat() < MIN_LAT || pin.lat() > MAX_LAT || pin.lon() < MIN_LON || pin.lon() > MAX_LON) {
            issues.add("pin", pin.lat() + "," + pin.lon(), "outside_moldova");
            return null;
        }
        if (locality != null) {
            double km = pin.kmTo(locality.point());
            if (km > 2 * locality.radiusKm() + 5) {
                issues.add("pin", pin.lat() + "," + pin.lon() + " " + Math.round(km) + " km from " + locality.nameRo(),
                        "far_from_locality");
                return null;
            }
        }
        return pin;
    }

    public Location locate(GeoPoint pin, AddressNormalizer.Address address, Place district, Place locality,
                           Issues issues) {
        if (pin != null) {
            String precision = address.houseNumber() != null ? "building" : "street";
            if (address.street() != null) {
                double km = Math.max(0, pin.kmTo(address.street().point()) - address.street().extentKm());
                if (km > AddressNormalizer.PIN_RADIUS_KM) {
                    issues.add("pin", Math.round(km * 10) / 10.0 + " km from " + address.streetName(), "far_from_street");
                    precision = "street";
                }
            }
            return new Location(pin, "pin", precision);
        }
        if (address.street() != null) {
            return new Location(address.street().point(), "street", "street");
        }
        if (district != null) {
            return new Location(district.point(), "place", "district");
        }
        if (locality != null) {
            return new Location(locality.point(), "place", "locality");
        }
        return Location.NONE;
    }
}
