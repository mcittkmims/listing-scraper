package md.apartmentlens.scraper;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import md.apartmentlens.scraper.crawl.CrawlService;
import md.apartmentlens.scraper.crawl.DefaultPinReview;
import md.apartmentlens.scraper.photo.PhotoDownloader;
import md.apartmentlens.scraper.source.ListingSource;
import md.apartmentlens.scraper.store.CrawlRunRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Runs every source on the schedule, one after another, then downloads new photos, while the scraper runs as a service. */
@Component
@EnableScheduling
@ConditionalOnProperty(name = "scraper.mode", havingValue = "daemon", matchIfMissing = true)
class CrawlScheduler {

    private static final Logger log = LoggerFactory.getLogger(CrawlScheduler.class);

    private final List<ListingSource> sources;
    private final CrawlService crawls;
    private final DefaultPinReview pinReview;
    private final CrawlRunRepository runs;
    private final ScraperProperties properties;
    private final Clock clock;
    private final PhotoDownloader photos;

    CrawlScheduler(List<ListingSource> sources, CrawlService crawls, DefaultPinReview pinReview,
                   CrawlRunRepository runs, ScraperProperties properties, Clock clock, PhotoDownloader photos) {
        this.photos = photos;
        this.sources = sources;
        this.crawls = crawls;
        this.pinReview = pinReview;
        this.runs = runs;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(cron = "${scraper.schedule:0 0 */4 * * *}", zone = "Europe/Chisinau")
    void scheduled() {
        crawlAll();
    }

    /** After a restart, catch up instead of waiting for the next scheduled time. */
    @EventListener(ApplicationReadyEvent.class)
    void onStart() {
        if (!properties.crawlOnStart()) {
            return;
        }
        Instant threshold = clock.instant().minus(properties.startupGap());
        boolean due = sources.stream().anyMatch(s -> runs.lastFinishedStart(runs.sourceId(s.code()))
                .map(t -> t.isBefore(threshold)).orElse(true));
        if (due) {
            Thread.ofVirtual().name("crawl-on-start").start(this::crawlAll);
        }
    }

    synchronized void crawlAll() {
        for (ListingSource source : sources) {
            try {
                crawls.crawl(source).ifPresent(stats -> pinReview.review(source));
            } catch (RuntimeException e) {
                log.error("{}: crawl failed: {}", source.code(), e.toString(), e);
            }
        }
        try {
            PhotoDownloader.Stats stats = photos.run(properties.photoBudget());
            log.info("photos after the crawl: {}", stats);
        } catch (RuntimeException e) {
            log.error("photo downloads failed: {}", e.toString(), e);
        }
    }
}
