package md.apartmentlens.scraper.normalize.address;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import md.apartmentlens.scraper.source.GeoPoint;

/**
 * About a million house numbers of Moldova with coordinates, from OpenStreetMap address points, each
 * linked to its street. Answers two questions: where is "Bucovinei 5", and which streets have
 * buildings right around a pin.
 *
 * <p>Kept in flat arrays (about 25 MB for the whole country): points sorted by street, then by
 * house number, plus a coarse grid for lookups by position.
 */
public final class AddressIndex {

    private static final double CELL_DEG = 0.005; // about 400 m by 550 m

    private final int[] street;
    private final String[] number;
    private final float[] lat;
    private final float[] lon;
    /** For each street, the first point and one past the last. */
    private final int[] streetStart;
    private final int[] streetEnd;
    private final Map<Long, int[]> grid;

    private AddressIndex(int[] street, String[] number, float[] lat, float[] lon, int streets) {
        this.street = street;
        this.number = number;
        this.lat = lat;
        this.lon = lon;
        this.streetStart = new int[streets];
        this.streetEnd = new int[streets];
        Arrays.fill(streetStart, -1);
        for (int i = 0; i < street.length; i++) {
            int s = street[i];
            if (streetStart[s] < 0) {
                streetStart[s] = i;
            }
            streetEnd[s] = i + 1;
        }
        Map<Long, List<Integer>> cells = new HashMap<>();
        for (int i = 0; i < street.length; i++) {
            cells.computeIfAbsent(cell(lat[i], lon[i]), k -> new ArrayList<>()).add(i);
        }
        this.grid = new HashMap<>(cells.size() * 2);
        cells.forEach((k, v) -> grid.put(k, v.stream().mapToInt(Integer::intValue).toArray()));
    }

    public static AddressIndex empty() {
        return new AddressIndex(new int[0], new String[0], new float[0], new float[0], 0);
    }

    /**
     * Reads md-addresses.tsv.gz. House numbers are cleaned with the same rules as sellers' numbers, so
     * "12 a" in the map and "12A" in an ad compare equal; points without a readable number are dropped.
     */
    static AddressIndex parse(InputStream in, int streets) throws IOException {
        BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8), 1 << 16);
        int cap = 1 << 20;
        int[] street = new int[cap];
        String[] number = new String[cap];
        float[] lat = new float[cap];
        float[] lon = new float[cap];
        int n = 0;
        Map<String, String> clean = new HashMap<>();
        String line;
        boolean header = true;
        while ((line = r.readLine()) != null) {
            if (line.startsWith("#")) {
                continue;
            }
            if (header) {
                header = false;
                continue;
            }
            int a = line.indexOf('\t');
            int b = line.indexOf('\t', a + 1);
            int c = line.indexOf('\t', b + 1);
            String value = clean.computeIfAbsent(line.substring(a + 1, b), k -> {
                HouseNumbers.Result h = HouseNumbers.parse(k);
                return h.kind() == HouseNumbers.Kind.NUMBER ? h.value() : "";
            });
            if (value.isEmpty()) {
                continue;
            }
            if (n == cap) {
                cap *= 2;
                street = Arrays.copyOf(street, cap);
                number = Arrays.copyOf(number, cap);
                lat = Arrays.copyOf(lat, cap);
                lon = Arrays.copyOf(lon, cap);
            }
            street[n] = Integer.parseInt(line, 0, a, 10);
            number[n] = value;
            lat[n] = Float.parseFloat(line.substring(b + 1, c));
            lon[n] = Float.parseFloat(line.substring(c + 1));
            n++;
        }
        street = Arrays.copyOf(street, n);
        number = Arrays.copyOf(number, n);
        lat = Arrays.copyOf(lat, n);
        lon = Arrays.copyOf(lon, n);
        sortNumbersWithinStreets(street, number, lat, lon);
        return new AddressIndex(street, number, lat, lon, streets);
    }

    /** The file lists each street's points together; within a street they are sorted by the cleaned number. */
    private static void sortNumbersWithinStreets(int[] street, String[] number, float[] lat, float[] lon) {
        int from = 0;
        while (from < street.length) {
            int to = from;
            while (to < street.length && street[to] == street[from]) {
                to++;
            }
            if (to - from > 1) {
                Integer[] order = new Integer[to - from];
                for (int i = 0; i < order.length; i++) {
                    order[i] = from + i;
                }
                Arrays.sort(order, (x, y) -> number[x].compareTo(number[y]));
                String[] nums = new String[order.length];
                float[] la = new float[order.length];
                float[] lo = new float[order.length];
                for (int i = 0; i < order.length; i++) {
                    nums[i] = number[order[i]];
                    la[i] = lat[order[i]];
                    lo[i] = lon[order[i]];
                }
                System.arraycopy(nums, 0, number, from, nums.length);
                System.arraycopy(la, 0, lat, from, la.length);
                System.arraycopy(lo, 0, lon, from, lo.length);
            }
            from = to;
        }
    }

    public int size() {
        return street.length;
    }

    /** The building with this exact house number on the street. */
    public Optional<GeoPoint> find(int streetIndex, String houseNumber) {
        if (houseNumber == null || streetIndex >= streetStart.length || streetStart[streetIndex] < 0) {
            return Optional.empty();
        }
        int i = Arrays.binarySearch(number, streetStart[streetIndex], streetEnd[streetIndex], houseNumber);
        return i >= 0 ? Optional.of(new GeoPoint(lat[i], lon[i])) : Optional.empty();
    }

    public boolean hasAddresses(int streetIndex) {
        return streetIndex < streetStart.length && streetStart[streetIndex] >= 0;
    }

    /** Distance in km from a point to the nearest building of the street; infinite when it has none. */
    public double distanceKm(int streetIndex, GeoPoint p) {
        if (!hasAddresses(streetIndex)) {
            return Double.POSITIVE_INFINITY;
        }
        double best = Double.POSITIVE_INFINITY;
        for (int i = streetStart[streetIndex]; i < streetEnd[streetIndex]; i++) {
            best = Math.min(best, km(p, i));
        }
        return best;
    }

    /** The streets that have a building within {@code radiusKm} of the point, nearest first. */
    public Set<Integer> streetsAround(GeoPoint p, double radiusKm) {
        record Hit(int street, double km) {
        }
        List<Hit> hits = new ArrayList<>();
        int cells = (int) Math.ceil(radiusKm / 0.35);
        int ci = (int) Math.floor(p.lat() / CELL_DEG);
        int cj = (int) Math.floor(p.lon() / CELL_DEG);
        for (int di = -cells; di <= cells; di++) {
            for (int dj = -cells; dj <= cells; dj++) {
                int[] list = grid.get(key(ci + di, cj + dj));
                if (list == null) {
                    continue;
                }
                for (int i : list) {
                    double d = km(p, i);
                    if (d <= radiusKm) {
                        hits.add(new Hit(street[i], d));
                    }
                }
            }
        }
        hits.sort((x, y) -> Double.compare(x.km(), y.km()));
        Set<Integer> out = new LinkedHashSet<>();
        hits.forEach(h -> out.add(h.street()));
        return out;
    }

    private double km(GeoPoint p, int i) {
        double dLat = Math.toRadians(lat[i] - p.lat());
        double dLon = Math.toRadians(lon[i] - p.lon()) * Math.cos(Math.toRadians(p.lat()));
        return 6371.0 * Math.hypot(dLat, dLon);
    }

    private static long cell(double lat, double lon) {
        return key((int) Math.floor(lat / CELL_DEG), (int) Math.floor(lon / CELL_DEG));
    }

    private static long key(int a, int b) {
        return ((long) a << 32) ^ (b & 0xffffffffL);
    }
}
