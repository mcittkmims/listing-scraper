package md.apartmentlens.scraper.http;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.GZIPInputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * HTTP for crawling: one request at a time with a minimum pause between requests, retries with
 * growing back-off on network errors, 429 and 5xx (respecting Retry-After), and gzip.
 */
public class PoliteHttpClient {

    private static final Logger log = LoggerFactory.getLogger(PoliteHttpClient.class);

    private final HttpClient client;
    private final String userAgent;
    private final Duration minInterval;
    private final int maxAttempts;
    private final Duration requestTimeout;
    private final AtomicLong requests = new AtomicLong();
    private long lastRequestNanos;

    public PoliteHttpClient(String userAgent, Duration minInterval, int maxAttempts, Duration requestTimeout) {
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        this.userAgent = userAgent;
        this.minInterval = minInterval;
        this.maxAttempts = maxAttempts;
        this.requestTimeout = requestTimeout;
    }

    /** The response of a successful request. */
    public record Response(int status, byte[] body, HttpHeaders headers) {

        public Optional<String> header(String name) {
            return headers.firstValue(name);
        }
    }

    public Response post(URI uri, Map<String, String> headers, byte[] body) throws IOException {
        return send(uri, headers, HttpRequest.BodyPublishers.ofByteArray(body), "POST", maxAttempts);
    }

    public Response get(URI uri, Map<String, String> headers) throws IOException {
        return send(uri, headers, HttpRequest.BodyPublishers.noBody(), "GET", maxAttempts);
    }

    /**
     * With fewer attempts than the default, for a request whose failure costs little: one ad's page, which
     * the next pass asks for again, unlike a listing page whose loss spoils the pass.
     */
    public Response get(URI uri, Map<String, String> headers, int attempts) throws IOException {
        return send(uri, headers, HttpRequest.BodyPublishers.noBody(), "GET", Math.min(attempts, maxAttempts));
    }

    /** Total requests sent, including retries. */
    public long requestCount() {
        return requests.get();
    }

    private Response send(URI uri, Map<String, String> headers, HttpRequest.BodyPublisher body, String method,
                          int attempts) throws IOException {
        IOException last = null;
        for (int attempt = 1; attempt <= attempts; attempt++) {
            pause();
            HttpRequest.Builder b = HttpRequest.newBuilder(uri)
                    .timeout(requestTimeout)
                    .header("User-Agent", userAgent)
                    .header("Accept-Encoding", "gzip")
                    .method(method, body);
            headers.forEach(b::header);
            Duration wait = backoff(attempt);
            try {
                requests.incrementAndGet();
                HttpResponse<byte[]> r = complete(b.build());
                byte[] bytes = decode(r);
                int status = r.statusCode();
                if (status >= 200 && status < 300) {
                    return new Response(status, bytes, r.headers());
                }
                if (status == 429 || status >= 500) {
                    wait = retryAfter(r).orElse(wait);
                    last = new HttpStatusException(status, uri);
                    log.warn("{} {} -> HTTP {} (attempt {}/{}), retrying in {}s", method, uri, status, attempt,
                            attempts, wait.toSeconds());
                } else {
                    throw new HttpStatusException(status, uri);
                }
            } catch (HttpStatusException e) {
                if (e.status() != 429 && e.status() < 500) {
                    throw e;
                }
            } catch (IOException e) {
                last = e;
                log.warn("{} {} failed (attempt {}/{}): {}, retrying in {}s", method, uri, attempt, attempts,
                        e.toString(), wait.toSeconds());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted", e);
            }
            if (attempt < attempts) {
                sleep(wait);
            }
        }
        throw last;
    }

    /**
     * The whole response within the request timeout. The client's own timeout ends with the headers, so a server
     * that stalls while sending the body would otherwise hold the request until the connection drops.
     */
    private HttpResponse<byte[]> complete(HttpRequest request) throws IOException, InterruptedException {
        CompletableFuture<HttpResponse<byte[]>> future = client.sendAsync(request, HttpResponse.BodyHandlers.ofByteArray());
        try {
            return future.get(requestTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new HttpTimeoutException("no complete response within " + requestTimeout.toSeconds() + "s");
        } catch (ExecutionException e) {
            if (e.getCause() instanceof IOException io) {
                throw io;
            }
            throw new IOException(e.getCause());
        }
    }

    private static byte[] decode(HttpResponse<byte[]> r) throws IOException {
        boolean gzip = r.headers().firstValue("Content-Encoding").map(v -> v.equalsIgnoreCase("gzip")).orElse(false);
        if (!gzip) {
            return r.body();
        }
        try (InputStream in = new GZIPInputStream(new ByteArrayInputStream(r.body()))) {
            return in.readAllBytes();
        }
    }

    private synchronized void pause() throws IOException {
        long now = System.nanoTime();
        long waitNanos = lastRequestNanos + minInterval.toNanos() - now;
        if (lastRequestNanos != 0 && waitNanos > 0) {
            sleep(Duration.ofNanos(waitNanos));
        }
        lastRequestNanos = System.nanoTime();
    }

    private static Duration backoff(int attempt) {
        return Duration.ofSeconds(Math.min(300, 5L * (1L << (attempt - 1)))); // 5, 10, 20, 40 ... s
    }

    private static Optional<Duration> retryAfter(HttpResponse<?> r) {
        return r.headers().firstValue("Retry-After").flatMap(v -> {
            try {
                return Optional.of(Duration.ofSeconds(Math.min(600, Long.parseLong(v.strip()))));
            } catch (NumberFormatException e) {
                return Optional.empty();
            }
        });
    }

    private static void sleep(Duration d) throws IOException {
        try {
            Thread.sleep(d);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted", e);
        }
    }
}
