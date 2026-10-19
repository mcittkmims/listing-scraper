package md.apartmentlens.scraper.http;

import java.io.IOException;
import java.net.URI;

/** A response with an error status. */
public class HttpStatusException extends IOException {

    private final int status;

    public HttpStatusException(int status, URI uri) {
        super("HTTP " + status + " from " + uri);
        this.status = status;
    }

    public int status() {
        return status;
    }
}
