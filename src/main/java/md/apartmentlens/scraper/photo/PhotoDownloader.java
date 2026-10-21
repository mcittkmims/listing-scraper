package md.apartmentlens.scraper.photo;

import java.io.IOException;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import md.apartmentlens.scraper.http.HttpStatusException;
import md.apartmentlens.scraper.http.PoliteHttpClient;
import md.apartmentlens.scraper.store.Bytes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Downloads the photos of the ads, each once: those not stored yet, the newest ads first. The sites' photo
 * servers are asked in parallel, each at its own polite pace. A file is stored under the hash of its content,
 * so the same photo from several sites is kept once, and a new image is announced to the vision workers.
 */
public class PhotoDownloader {

    private static final Logger log = LoggerFactory.getLogger(PhotoDownloader.class);

    /** Photos per server in one batch: about a minute's work at a polite pace. */
    static final int PER_SERVER = 100;
    static final int MAX_BYTES = 20 * 1024 * 1024;
    /** Failures in a row after which a server's other photos wait for the next batch. */
    static final int GIVE_UP_AFTER = 5;

    /** One photo's bytes from its link. */
    public interface Fetcher {
        byte[] get(String url) throws IOException;
    }

    /** What one run did. */
    public record Stats(int downloaded, int newImages, int failed) {
    }

    private final PhotoRepository photos;
    private final PhotoStore store;
    private final TransactionTemplate tx;
    private final Function<String, Fetcher> fetcherForHost;
    private final Clock clock;
    private final Map<String, Fetcher> fetchers = new ConcurrentHashMap<>();
    private volatile boolean stopping;

    public PhotoDownloader(PhotoRepository photos, PhotoStore store, TransactionTemplate tx,
                           Function<String, Fetcher> fetcherForHost, Clock clock) {
        this.photos = photos;
        this.store = store;
        this.tx = tx;
        this.fetcherForHost = fetcherForHost;
        this.clock = clock;
    }

    /** One polite HTTP client per photo server: each server gets its own pause between requests. */
    public static Function<String, Fetcher> politeFetchers(String userAgent, Duration interval) {
        return host -> {
            PoliteHttpClient http = new PoliteHttpClient(userAgent, interval, 2, Duration.ofSeconds(30));
            return url -> http.get(URI.create(url), Map.of("Accept", "image/*")).body();
        };
    }

    /** Downloads until nothing is left or {@code budget} has passed; a batch that started is finished. */
    public Stats run(Duration budget) {
        Instant until = clock.instant().plus(budget);
        AtomicInteger downloaded = new AtomicInteger();
        AtomicInteger newImages = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();
        while (!stopping && clock.instant().isBefore(until)) {
            List<PhotoRepository.Pending> batch = photos.pending(PER_SERVER);
            if (batch.isEmpty()) {
                break;
            }
            Map<String, List<PhotoRepository.Pending>> byHost = new LinkedHashMap<>();
            for (PhotoRepository.Pending p : batch) {
                byHost.computeIfAbsent(host(p.url()), h -> new ArrayList<>()).add(p);
            }
            List<Thread> threads = new ArrayList<>();
            byHost.forEach((host, list) -> threads.add(Thread.ofVirtual().name("photos-" + host).start(() -> {
                Fetcher fetcher = fetchers.computeIfAbsent(host, fetcherForHost);
                int failedInARow = 0;
                for (PhotoRepository.Pending p : list) {
                    if (stopping) {
                        return;
                    }
                    if (failedInARow >= GIVE_UP_AFTER) {
                        // The server is not answering: its other photos wait for the next batch.
                        log.warn("photos: {} failed {} times in a row; its other photos wait", host, failedInARow);
                        return;
                    }
                    switch (download(fetcher, p)) {
                        case NEW_IMAGE -> {
                            downloaded.incrementAndGet();
                            newImages.incrementAndGet();
                            failedInARow = 0;
                        }
                        case KNOWN_IMAGE -> {
                            downloaded.incrementAndGet();
                            failedInARow = 0;
                        }
                        case FAILED -> {
                            failed.incrementAndGet();
                            failedInARow++;
                        }
                    }
                }
            })));
            for (Thread t : threads) {
                try {
                    t.join();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    stopping = true;
                }
            }
            log.info("photos: {} downloaded ({} new images), {} failed so far", downloaded, newImages, failed);
        }
        return new Stats(downloaded.get(), newImages.get(), failed.get());
    }

    enum Outcome { NEW_IMAGE, KNOWN_IMAGE, FAILED }

    private Outcome download(Fetcher fetcher, PhotoRepository.Pending p) {
        try {
            byte[] content = fetcher.get(p.url());
            ImageType type = ImageType.of(content);
            if (type == null) {
                photos.failed(p, "not an image (" + content.length + " bytes)", false);
                return Outcome.FAILED;
            }
            if (content.length > MAX_BYTES) {
                photos.failed(p, "too large: " + content.length + " bytes", true);
                return Outcome.FAILED;
            }
            byte[] sha = Bytes.sha256(content);
            store.save(sha, type.extension(), content);
            Boolean isNew = tx.execute(s -> photos.stored(p, sha, content.length, type.contentType(),
                    PhotoStore.name(sha, type.extension())));
            return Boolean.TRUE.equals(isNew) ? Outcome.NEW_IMAGE : Outcome.KNOWN_IMAGE;
        } catch (HttpStatusException e) {
            // Gone at the source; anything else (an expired signature, a server error) is tried again later.
            photos.failed(p, e.getMessage(), e.status() == 404 || e.status() == 410);
            return Outcome.FAILED;
        } catch (IOException | RuntimeException e) {
            photos.failed(p, e.toString(), false);
            return Outcome.FAILED;
        }
    }

    /** Stops the run at the next photo. */
    public void stop() {
        stopping = true;
    }

    static String host(String url) {
        try {
            String host = URI.create(url).getHost();
            return host == null ? "" : host;
        } catch (IllegalArgumentException e) {
            return "";
        }
    }

    /** The image formats the sites serve, recognized by their first bytes, not by what the server claims. */
    enum ImageType {
        JPEG("jpg", "image/jpeg"),
        PNG("png", "image/png"),
        WEBP("webp", "image/webp"),
        GIF("gif", "image/gif"),
        AVIF("avif", "image/avif");

        private final String extension;
        private final String contentType;

        ImageType(String extension, String contentType) {
            this.extension = extension;
            this.contentType = contentType;
        }

        String extension() {
            return extension;
        }

        String contentType() {
            return contentType;
        }

        static ImageType of(byte[] b) {
            if (b.length < 12) {
                return null;
            }
            if ((b[0] & 0xff) == 0xff && (b[1] & 0xff) == 0xd8 && (b[2] & 0xff) == 0xff) {
                return JPEG;
            }
            if ((b[0] & 0xff) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G') {
                return PNG;
            }
            if (b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F' && b[8] == 'W' && b[9] == 'E' && b[10] == 'B'
                    && b[11] == 'P') {
                return WEBP;
            }
            if (b[0] == 'G' && b[1] == 'I' && b[2] == 'F' && b[3] == '8') {
                return GIF;
            }
            if (b[4] == 'f' && b[5] == 't' && b[6] == 'y' && b[7] == 'p' && b[8] == 'a' && b[9] == 'v' && b[10] == 'i') {
                return AVIF;
            }
            return null;
        }
    }
}
