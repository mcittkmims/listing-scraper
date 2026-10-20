package md.apartmentlens.scraper.normalize.address;

import java.util.Arrays;

/**
 * Street lines in the encoded polyline format (precision 1e-5, about a metre): compact text for the
 * gazetteer files. Several parts are separated by a space, which the encoding never uses (it writes
 * characters 63 to 126 only).
 */
public final class Polyline {

    private Polyline() {
    }

    /** Each part as lat, lon, lat, lon, ... */
    public static String encode(float[][] parts) {
        StringBuilder sb = new StringBuilder();
        for (float[] part : parts) {
            if (!sb.isEmpty()) {
                sb.append(' ');
            }
            long lat = 0;
            long lon = 0;
            for (int i = 0; i + 1 < part.length; i += 2) {
                long la = Math.round(part[i] * 1e5);
                long lo = Math.round(part[i + 1] * 1e5);
                value(sb, la - lat);
                value(sb, lo - lon);
                lat = la;
                lon = lo;
            }
        }
        return sb.toString();
    }

    public static float[][] decode(String s) {
        if (s == null || s.isEmpty()) {
            return new float[0][];
        }
        String[] texts = s.split(" ");
        float[][] parts = new float[texts.length][];
        for (int p = 0; p < texts.length; p++) {
            String t = texts[p];
            float[] buf = new float[t.length() * 2];
            int n = 0;
            int i = 0;
            long lat = 0;
            long lon = 0;
            while (i < t.length()) {
                long[] r = next(t, i);
                lat += r[0];
                i = (int) r[1];
                r = next(t, i);
                lon += r[0];
                i = (int) r[1];
                buf[n++] = lat / 1e5f;
                buf[n++] = lon / 1e5f;
            }
            parts[p] = Arrays.copyOf(buf, n);
        }
        return parts;
    }

    private static void value(StringBuilder sb, long v) {
        long x = v < 0 ? ~(v << 1) : v << 1;
        while (x >= 0x20) {
            sb.append((char) ((0x20 | (x & 0x1f)) + 63));
            x >>= 5;
        }
        sb.append((char) (x + 63));
    }

    private static long[] next(String s, int i) {
        long result = 0;
        int shift = 0;
        int b;
        do {
            b = s.charAt(i++) - 63;
            result |= (long) (b & 0x1f) << shift;
            shift += 5;
        } while (b >= 0x20);
        return new long[] {(result & 1) != 0 ? ~(result >> 1) : result >> 1, i};
    }

    /** Distance in km from a point to the nearest segment of the line (local flat approximation). */
    public static double distanceKm(float[][] parts, double lat, double lon) {
        double best = Double.POSITIVE_INFINITY;
        double k = Math.cos(Math.toRadians(lat));
        for (float[] part : parts) {
            if (part.length == 2) {
                best = Math.min(best, km(lat, lon, part[0], part[1], k));
                continue;
            }
            for (int i = 0; i + 3 < part.length; i += 2) {
                double ax = (part[i + 1] - lon) * k;
                double ay = part[i] - lat;
                double bx = (part[i + 3] - lon) * k;
                double by = part[i + 2] - lat;
                double dx = bx - ax;
                double dy = by - ay;
                double len = dx * dx + dy * dy;
                double t = len == 0 ? 0 : Math.max(0, Math.min(1, -(ax * dx + ay * dy) / len));
                double px = ax + t * dx;
                double py = ay + t * dy;
                best = Math.min(best, Math.toRadians(Math.hypot(px, py)) * 6371.0);
            }
        }
        return best;
    }

    private static double km(double lat, double lon, double lat2, double lon2, double k) {
        return Math.toRadians(Math.hypot((lon2 - lon) * k, lat2 - lat)) * 6371.0;
    }
}
