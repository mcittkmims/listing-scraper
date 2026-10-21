package md.apartmentlens.scraper.normalize.address;

import md.apartmentlens.scraper.normalize.Issues;
import md.apartmentlens.scraper.normalize.address.PlaceGazetteer.Place;
import md.apartmentlens.scraper.source.GeoPoint;

/**
 * The ad's map point and how much to trust it, from the most to the least precise source:
 *
 * <ul>
 *   <li>the seller's pin, with a house number given: {@code building}</li>
 *   <li>the map's position of the street and house number (no pin, a default pin, or a pin that is
 *       not on that street): {@code building}</li>
 *   <li>the seller's pin without a house number, or the recognized street's centre: {@code street}</li>
 *   <li>the centre of the city district: {@code district}</li>
 *   <li>the centre of the town or village: {@code locality}</li>
 * </ul>
 *
 * A pin outside Moldova, far from the ad's locality, or shared by many ads as a default is not used.
 */
public final class LocationNormalizer {

    public record Location(GeoPoint point, String source, String precision) {

        static final Location NONE = new Location(null, null, null);
    }

    static final double MIN_LAT = 45.4;
    static final double MAX_LAT = 48.6;
    static final double MIN_LON = 26.6;
    static final double MAX_LON = 30.2;
    /** A pin farther than this from the building at the given address is not on that building. */
    static final double PIN_TO_BUILDING_KM = 0.3;
    /** A pin placed only near the property, at most this far off, still beats a district or town centre. */
    static final int NEAR_PIN_MAX_M = 1000;

    private final StreetGazetteer streets;
    private final DefaultPins defaultPins;

    public LocationNormalizer(StreetGazetteer streets, DefaultPins defaultPins) {
        this.streets = streets;
        this.defaultPins = defaultPins;
    }

    /** Returns the pin if it can be used; reports it otherwise. */
    public GeoPoint checkPin(GeoPoint pin, Place locality, Issues issues) {
        if (pin == null) {
            return null;
        }
        if (pin.lat() < MIN_LAT || pin.lat() > MAX_LAT || pin.lon() < MIN_LON || pin.lon() > MAX_LON) {
            issues.add("pin", pin.lat() + "," + pin.lon(), "outside_moldova");
            return null;
        }
        if (defaultPins.isDefault(pin)) {
            issues.add("pin", pin.lat() + "," + pin.lon(), "default_position");
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

    /** True when the street has buildings right around the pin. */
    private boolean onStreet(GeoPoint pin, StreetGazetteer.Street street) {
        return street != null && streets.streetsAround(pin, 0.06).stream()
                .anyMatch(s -> s.index() == street.index() || s.name().equals(street.name()));
    }

    /**
     * The location with a pin the site placed only near the property ({@code radiusM} off at most): used
     * instead of a district or town centre, never instead of a building or street.
     */
    public Location near(Location location, GeoPoint pin, int radiusM) {
        boolean precise = "building".equals(location.precision()) || "street".equals(location.precision());
        if (precise || radiusM > NEAR_PIN_MAX_M) {
            return location;
        }
        return new Location(pin, "pin", "district");
    }

    public Location locate(GeoPoint pin, AddressNormalizer.Address address, Place district, Place locality,
                           Issues issues) {
        GeoPoint building = address.building();
        if (pin != null && defaultPins.isStreetLevel(pin)) {
            // 999.md found the street but not the house number: the building of the address, if known.
            issues.add("pin", pin.lat() + "," + pin.lon(), "street_level");
            if (building != null) {
                return new Location(building, "address", "building");
            }
            return new Location(pin, "pin", "street");
        }
        if (pin != null) {
            String precision = address.houseNumber() != null ? "building" : "street";
            if (building != null && pin.kmTo(building) > PIN_TO_BUILDING_KM) {
                String raw = Math.round(pin.kmTo(building) * 1000) + " m from " + address.streetName() + " "
                        + address.houseNumber();
                if (!onStreet(pin, address.street())) {
                    // Not even on the street the seller wrote: the address is more likely than the pin.
                    issues.add("pin", raw, "far_from_address");
                    return new Location(building, "address", "building");
                }
                // On the right street but far from that number: the pin or the number is off, so the
                // building is not known for sure.
                issues.add("pin", raw, "off_house_number");
                precision = "street";
            }
            if (address.street() != null) {
                double km = streets.distanceKm(address.street(), pin);
                if (km > AddressNormalizer.PIN_RADIUS_KM) {
                    issues.add("pin", Math.round(km * 10) / 10.0 + " km from " + address.streetName(), "far_from_street");
                    precision = "street";
                }
            }
            return new Location(pin, "pin", precision);
        }
        if (building != null) {
            return new Location(building, "address", "building");
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
