package md.apartmentlens.scraper.normalize.address;

import md.apartmentlens.scraper.source.GeoPoint;

/**
 * Pins that are not the seller's own: when a seller does not move the map, 999.md saves its centre for
 * the chosen town or sector, so many ads on different streets share one pin. Such a pin says nothing
 * about the building and is not used as the location.
 */
@FunctionalInterface
public interface DefaultPins {

    DefaultPins NONE = pin -> false;

    boolean isDefault(GeoPoint pin);

    /** Pins are compared at five decimals, about one metre. */
    static long key(GeoPoint p) {
        long lat = Math.round(p.lat() * 100_000);
        long lon = Math.round(p.lon() * 100_000);
        return (lat << 32) ^ (lon & 0xffffffffL);
    }
}
