package md.apartmentlens.scraper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import md.apartmentlens.scraper.normalize.address.PlaceGazetteer;
import md.apartmentlens.scraper.normalize.address.StreetGazetteer;
import md.apartmentlens.scraper.source.RawAd;
import tools.jackson.databind.json.JsonMapper;

/** Synthetic source responses in src/test/resources (made-up ids and phone numbers). */
public final class Fixtures {

    public static final JsonMapper JSON = JsonMapper.builder().build();

    /** The bundled gazetteers, loaded once for all tests: each copy takes a few hundred MB. */
    private static final class Gazetteers {
        static final StreetGazetteer STREETS = StreetGazetteer.load();
        static final PlaceGazetteer PLACES = PlaceGazetteer.load();
    }

    public static StreetGazetteer streets() {
        return Gazetteers.STREETS;
    }

    public static PlaceGazetteer places() {
        return Gazetteers.PLACES;
    }

    private Fixtures() {
    }

    /** A response body as stored in src/test/resources/{dir}/{name}. */
    public static byte[] bytes(String dir, String name) {
        try (InputStream in = Fixtures.class.getResourceAsStream("/" + dir + "/" + name)) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static RawAd ninetyNine(String name) {
        try (InputStream in = Fixtures.class.getResourceAsStream("/ninetynine/" + name)) {
            byte[] body = in.readAllBytes();
            return new RawAd(JSON.readTree(body).path("id").asString(), body);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
