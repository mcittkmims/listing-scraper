package md.apartmentlens.scraper.normalize.address;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.Test;

class PolylineTest {

    @Test
    void roundTripWithSeveralParts() {
        float[][] line = {{47.06786f, 28.84016f, 47.06900f, 28.84200f}, {46.98000f, 28.85500f, 46.98100f, 28.85700f}};
        float[][] back = Polyline.decode(Polyline.encode(line));
        assertThat(back).hasNumberOfRows(2);
        assertThat(back[0][2]).isCloseTo(47.069f, within(0.00002f));
        assertThat(back[1][3]).isCloseTo(28.857f, within(0.00002f));
    }

    @Test
    void distanceToTheLineNotToItsCorners() {
        float[][] line = {{47.0f, 28.80f, 47.0f, 28.90f}}; // east-west, about 7.6 km long
        assertThat(Polyline.distanceKm(line, 47.0, 28.85)).isLessThan(0.001); // on it, mid-way
        assertThat(Polyline.distanceKm(line, 47.001, 28.85)).isCloseTo(0.111, within(0.002)); // 111 m north
    }
}
