package md.apartmentlens.scraper.normalize.address;

import md.apartmentlens.scraper.source.GeoPoint;

/**
 * Pins that do not show the building. 999.md places the pin itself from the typed address (sellers
 * cannot move it): when it does not find the street, it uses the centre of the town or sector, so many
 * ads on different streets share one pin ({@link #isDefault}); when it finds the street but not the house
 * number, a point on the street ({@link #isStreetLevel}).
 */
@FunctionalInterface
public interface DefaultPins {

    DefaultPins NONE = pin -> false;

    boolean isDefault(GeoPoint pin);

    /**
     * True for a pin 999.md put on a street because it did not find the house number: one point used for
     * several different house numbers of the same street. It shows the street, not the building.
     */
    default boolean isStreetLevel(GeoPoint pin) {
        return false;
    }

    /** Pins are compared at five decimals, about one metre. */
    static long key(GeoPoint p) {
        long lat = Math.round(p.lat() * 100_000);
        long lon = Math.round(p.lon() * 100_000);
        return (lat << 32) ^ (lon & 0xffffffffL);
    }
}
