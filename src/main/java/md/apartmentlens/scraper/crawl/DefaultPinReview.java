package md.apartmentlens.scraper.crawl;

import java.util.List;
import java.util.Set;
import md.apartmentlens.scraper.source.ListingSource;
import md.apartmentlens.scraper.store.DefaultPinRegistry;
import md.apartmentlens.scraper.store.ListingRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * After a crawl, finds the default pins again (new ads can make a pin default) and normalizes the ads
 * on pins that became or stopped being default, so their locations follow.
 */
public class DefaultPinReview {

    private static final Logger log = LoggerFactory.getLogger(DefaultPinReview.class);

    private final DefaultPinRegistry registry;
    private final ListingRepository listings;
    private final RenormalizeService renormalize;

    public DefaultPinReview(DefaultPinRegistry registry, ListingRepository listings, RenormalizeService renormalize) {
        this.registry = registry;
        this.listings = listings;
        this.renormalize = renormalize;
    }

    public void review(ListingSource source) {
        Set<Long> changed = registry.refresh();
        if (changed.isEmpty()) {
            return;
        }
        List<Long> ids = listings.idsWithPin(changed);
        int done = renormalize.renormalize(source, ids);
        log.info("{}: {} default pins now known ({} changed); {} ads on them normalized again", source.code(),
                registry.size(), changed.size(), done);
    }
}
