package md.apartmentlens.scraper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import md.apartmentlens.scraper.source.RawAd;
import tools.jackson.databind.json.JsonMapper;

/** Synthetic source responses in src/test/resources (made-up ids and phone numbers). */
public final class Fixtures {

    public static final JsonMapper JSON = JsonMapper.builder().build();

    private Fixtures() {
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
