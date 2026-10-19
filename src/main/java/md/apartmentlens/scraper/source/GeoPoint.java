package md.apartmentlens.scraper.source;

/** A WGS 84 point. */
public record GeoPoint(double lat, double lon) {

    /** Distance in kilometres (equirectangular approximation, accurate at city scale). */
    public double kmTo(GeoPoint o) {
        double dLat = Math.toRadians(o.lat - lat);
        double dLon = Math.toRadians(o.lon - lon) * Math.cos(Math.toRadians((lat + o.lat) / 2));
        return 6371.0 * Math.hypot(dLat, dLon);
    }
}
