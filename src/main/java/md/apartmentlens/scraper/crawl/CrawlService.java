package md.apartmentlens.scraper.crawl;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;
import md.apartmentlens.scraper.normalize.ListingNormalizer;
import md.apartmentlens.scraper.normalize.NormalizedListing;
import md.apartmentlens.scraper.rates.ExchangeRateService;
import md.apartmentlens.scraper.source.ListingSource;
import md.apartmentlens.scraper.source.RawAd;
import md.apartmentlens.scraper.source.SourceAd;
import md.apartmentlens.scraper.store.Bytes;
import md.apartmentlens.scraper.store.CrawlRunRepository;
import md.apartmentlens.scraper.store.ListingRepository;
import md.apartmentlens.scraper.store.ListingRepository.Stored;
import md.apartmentlens.scraper.store.ListingRepository.Unseen;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * One pass over a source: every ad listed is stored or updated, its raw content kept when it changed,
 * and a price snapshot written when the price or status changed. After a complete pass, active ads that
 * were not seen are fetched one by one: still listed (missed while the listing shifted) or gone.
 *
 * <p>Each ad is handled in its own transaction, so one bad ad never stops a run.
 */
public class CrawlService implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(CrawlService.class);
    static final ZoneId MOLDOVA = ZoneId.of("Europe/Chisinau");

    /** Limits that protect against marking ads gone after a short or broken pass. */
    public record Safety(Duration staleRunAfter, double minSeenShare, double maxGoneShare) {

        public static final Safety DEFAULT = new Safety(Duration.ofHours(2), 0.9, 0.2);
    }

    private final CrawlRunRepository runs;
    private final ListingRepository listings;
    private final ListingNormalizer normalizer;
    private final ExchangeRateService rates;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final LongSupplier requestCount;
    private final Safety safety;
    private volatile boolean stopping;
    private final AtomicReference<CountDownLatch> running =
            new AtomicReference<>();

    public CrawlService(CrawlRunRepository runs, ListingRepository listings, ListingNormalizer normalizer,
                        ExchangeRateService rates, TransactionTemplate tx, Clock clock, LongSupplier requestCount,
                        Safety safety) {
        this.runs = runs;
        this.listings = listings;
        this.normalizer = normalizer;
        this.rates = rates;
        this.tx = tx;
        this.clock = clock;
        this.requestCount = requestCount;
        this.safety = safety;
    }

    /** Runs one pass; empty when another pass of the same source is still running. */
    public Optional<CrawlStats> crawl(ListingSource source) {
        short sourceId = runs.sourceId(source.code());
        Optional<Long> started = runs.start(sourceId, safety.staleRunAfter());
        if (started.isEmpty()) {
            log.warn("{}: another crawl is running, skipped", source.code());
            return Optional.empty();
        }
        Run run = new Run(source, sourceId, started.get(), clock.instant(), LocalDate.now(clock.withZone(MOLDOVA)));
        CountDownLatch done = new CountDownLatch(1);
        running.set(done);
        try {
            return Optional.of(run(run));
        } finally {
            running.set(null);
            done.countDown();
        }
    }

    private CrawlStats run(Run run) {
        ListingSource source = run.source;
        log.info("{}: crawl {} started", source.code(), run.id);
        rates.ensure(run.today);
        long requestsBefore = requestCount.getAsLong();
        String status = "completed";
        String error = null;
        try {
            run.stats.advertised(source.crawl(run::accept));
        } catch (IOException | RuntimeException e) {
            status = "partial";
            error = "listing not read to the end: " + e;
            log.error("{}: {}", source.code(), error, e);
        }
        if (status.equals("completed") && !stopping) {
            error = checkUnseen(run);
            if (error != null) {
                status = "partial";
            }
        }
        run.stats.requests(requestCount.getAsLong() - requestsBefore);
        runs.finish(run.id, status, run.stats, error);
        log.info("{}: crawl {} {}: {}", source.code(), run.id, status, run.stats);
        return run.stats;
    }

    /**
     * Stops a running crawl at the next ad and waits for it to record itself as partial, so a restart
     * can crawl again at once.
     */
    @Override
    public void close() {
        stopping = true;
        CountDownLatch done = running.get();
        if (done != null) {
            log.info("stopping the running crawl");
            try {
                done.await(20, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** Re-fetches active ads the pass did not see. Returns why it was not done, or null. */
    private String checkUnseen(Run run) {
        int seen = run.seen.size();
        Optional<Integer> last = runs.lastCompletedSeen(run.sourceId);
        if (last.isPresent() && seen < last.get() * safety.minSeenShare()) {
            return "saw " + seen + " ads, the last complete pass saw " + last.get() + ": gone check skipped";
        }
        if (run.stats.advertised() > 0 && seen < run.stats.advertised() * safety.minSeenShare()) {
            return "saw " + seen + " of " + run.stats.advertised() + " advertised ads: gone check skipped";
        }
        List<Unseen> unseen = listings.activeNotSeenSince(run.sourceId, run.startedAt);
        int active = listings.countActive(run.sourceId);
        if (active > 100 && unseen.size() > active * safety.maxGoneShare()) {
            return unseen.size() + " of " + active + " active ads not seen: gone check skipped";
        }
        log.info("{}: checking {} ads not seen in this pass", run.source.code(), unseen.size());
        for (Unseen u : unseen) {
            if (stopping) {
                return "stopped during the gone check";
            }
            try {
                Optional<RawAd> raw = run.source.fetch(u.externalId());
                if (raw.isPresent()) {
                    run.accept(raw.get());
                } else {
                    tx.executeWithoutResult(s -> {
                        Instant now = clock.instant();
                        listings.markGone(u.id(), now);
                        listings.insertSnapshot(u.id(), run.id, now, "gone", u.priceAmount(), u.priceCurrency(),
                                u.priceEur());
                    });
                    run.stats.countGone();
                }
            } catch (IOException | RuntimeException e) {
                run.stats.countError();
                log.warn("{} ad {}: could not check if it is still listed: {}", run.source.code(), u.externalId(),
                        e.toString());
            }
        }
        return null;
    }

    /** State of one pass. */
    private final class Run {

        final ListingSource source;
        final short sourceId;
        final long id;
        final Instant startedAt;
        final LocalDate today;
        final Set<String> seen = new HashSet<>();
        final CrawlStats stats = new CrawlStats();

        Run(ListingSource source, short sourceId, long id, Instant startedAt, LocalDate today) {
            this.source = source;
            this.sourceId = sourceId;
            this.id = id;
            this.startedAt = startedAt;
            this.today = today;
        }

        void accept(RawAd raw) {
            if (stopping) {
                throw new CancellationException("the service is stopping");
            }
            if (!seen.add(raw.externalId())) {
                return; // listed twice because the listing shifted while it was read
            }
            stats.countSeen();
            try {
                tx.executeWithoutResult(s -> store(raw));
            } catch (RuntimeException e) {
                stats.countError();
                log.warn("{} ad {}: not stored: {}", source.code(), raw.externalId(), e.toString(), e);
            }
        }

        private void store(RawAd raw) {
            byte[] sha = Bytes.sha256(source.stableContent(raw));
            SourceAd ad = source.parse(raw);
            Instant now = clock.instant();
            Optional<Stored> stored = listings.find(sourceId, ad.externalId());
            if (stored.isEmpty()) {
                NormalizedListing n = normalizer.normalize(ad, today);
                long listingId = listings.insert(sourceId, ad.externalId(), ad.url(), n, now, ad.postedAt(),
                        ad.republishedAt(), sha, ListingNormalizer.VERSION);
                listings.insertRaw(listingId, id, now, sha, Bytes.gzip(raw.body()));
                listings.insertSnapshot(listingId, id, now, "active", n.priceAmount(), n.priceCurrency(), n.priceEur());
                stats.countAdded();
                return;
            }
            Stored s = stored.get();
            boolean contentChanged = !Arrays.equals(s.contentSha256(), sha);
            boolean reappeared = s.status().equals("gone");
            if (!contentChanged && !reappeared && s.normalizerVersion() == ListingNormalizer.VERSION) {
                listings.touch(s.id(), now, ad.republishedAt());
                return;
            }
            NormalizedListing n = normalizer.normalize(ad, today);
            listings.update(s.id(), n, now, ad.postedAt(), ad.republishedAt(), sha, ListingNormalizer.VERSION);
            if (contentChanged) {
                listings.insertRaw(s.id(), id, now, sha, Bytes.gzip(raw.body()));
                stats.countChanged();
            }
            boolean priceChanged = !sameAmount(s.priceAmount(), n.priceAmount())
                    || !Objects.equals(s.priceCurrency(), n.priceCurrency());
            if (priceChanged || reappeared) {
                listings.insertSnapshot(s.id(), id, now, "active", n.priceAmount(), n.priceCurrency(), n.priceEur());
                if (priceChanged) {
                    stats.countPriceChange();
                }
            }
        }
    }

    static boolean sameAmount(BigDecimal a, BigDecimal b) {
        return a == null ? b == null : b != null && a.compareTo(b) == 0;
    }
}
