package md.apartmentlens.scraper;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Scraper settings ({@code scraper.*} in application.yml, or SCRAPER_* environment variables).
 *
 * @param phoneHashKey     secret key for phone hashes; never change it once data exists
 * @param userAgent        how the scraper identifies itself to websites
 * @param requestInterval  minimum pause between two requests to a website
 * @param maxAttempts      attempts per request before giving up
 * @param requestTimeout   timeout of one request
 * @param schedule         when crawls run (cron, Moldovan time)
 * @param crawlOnStart     crawl right after startup when the last crawl is older than {@code startupGap}
 * @param startupGap       see {@code crawlOnStart}
 * @param maxPages         stop each category after this many pages; 0 reads everything (for testing only)
 * @param minSeenShare     a pass that saw fewer ads than this share of the last one marks nothing gone
 * @param maxGoneShare     more unseen ads than this share of active ones mark nothing gone
 */
@ConfigurationProperties("scraper")
public record ScraperProperties(
        String phoneHashKey,
        @DefaultValue("apartment-lens-scraper/1.0 (+https://github.com/mcittkmims/apartment-lens)") String userAgent,
        @DefaultValue("1500ms") Duration requestInterval,
        @DefaultValue("6") int maxAttempts,
        @DefaultValue("60s") Duration requestTimeout,
        @DefaultValue("0 0 */4 * * *") String schedule,
        @DefaultValue("true") boolean crawlOnStart,
        @DefaultValue("3h") Duration startupGap,
        @DefaultValue("0") int maxPages,
        @DefaultValue("0.9") double minSeenShare,
        @DefaultValue("0.2") double maxGoneShare) {
}
