package md.apartmentlens.scraper.crawl;

import java.util.List;
import java.util.Set;
import md.apartmentlens.scraper.source.ListingSource;
import md.apartmentlens.scraper.store.CrawlRunRepository;
import md.apartmentlens.scraper.store.DefaultPinRegistry;
import md.apartmentlens.scraper.store.ListingRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * After a crawl, finds the default pins again (new ads can make a pin default) and normalizes the ads
 * on pins that became or stopped being default, so their locations follow. Pins are counted over every
 * source, so the ads of every source on a changed pin are normalized again, each by its own parser.
 */
public class DefaultPinReview {

    private static final Logger log = LoggerFactory.getLogger(DefaultPinReview.class);

    private final DefaultPinRegistry registry;
    private final ListingRepository listings;
    private final RenormalizeService renormalize;
    private final CrawlRunRepository runs;
    private final List<ListingSource> sources;

    public DefaultPinReview(DefaultPinRegistry registry, ListingRepository listings, RenormalizeService renormalize,
                            CrawlRunRepository runs, List<ListingSource> sources) {
        this.registry = registry;
        this.listings = listings;
        this.renormalize = renormalize;
        this.runs = runs;
        this.sources = sources;
    }

    /** @param crawled the source whose crawl may have changed the pins */
    public void review(ListingSource crawled) {
        Set<Long> changed = registry.refresh();
        if (changed.isEmpty()) {
            return;
        }
        int done = 0;
        for (ListingSource source : sources) {
            done += renormalize.renormalize(source, listings.idsWithPin(changed, runs.sourceId(source.code())));
        }
        log.info("after {}: {} default and {} street-level pins now known ({} changed); {} ads on them normalized again",
                crawled.code(), registry.size(), registry.streetLevelSize(), changed.size(), done);
    }
}
