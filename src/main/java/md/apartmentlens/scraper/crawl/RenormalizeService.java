package md.apartmentlens.scraper.crawl;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import md.apartmentlens.scraper.normalize.ListingNormalizer;
import md.apartmentlens.scraper.normalize.NormalizedListing;
import md.apartmentlens.scraper.rates.ExchangeRateService;
import md.apartmentlens.scraper.source.ListingSource;
import md.apartmentlens.scraper.source.RawAd;
import md.apartmentlens.scraper.source.SourceAd;
import md.apartmentlens.scraper.store.Bytes;
import md.apartmentlens.scraper.store.CrawlRunRepository;
import md.apartmentlens.scraper.store.ListingRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Normalizes stored ads again from their raw content, without crawling: after the normalizer's rules
 * change ({@link ListingNormalizer#VERSION}), or for every ad on request. Price history is not touched.
 */
public class RenormalizeService {

    private static final Logger log = LoggerFactory.getLogger(RenormalizeService.class);
    private static final int BATCH = 200;

    private final CrawlRunRepository runs;
    private final ListingRepository listings;
    private final ListingNormalizer normalizer;
    private final ExchangeRateService rates;
    private final TransactionTemplate tx;

    public RenormalizeService(CrawlRunRepository runs, ListingRepository listings, ListingNormalizer normalizer,
                              ExchangeRateService rates, TransactionTemplate tx) {
        this.runs = runs;
        this.listings = listings;
        this.normalizer = normalizer;
        this.rates = rates;
        this.tx = tx;
    }

    /** @return the number of ads normalized again */
    public int renormalize(ListingSource source, boolean all) {
        short sourceId = runs.sourceId(source.code());
        Set<LocalDate> ratesChecked = new HashSet<>();
        long afterId = 0;
        int[] counts = new int[2];
        while (true) {
            List<ListingRepository.Raw> batch = listings.toRenormalize(sourceId, ListingNormalizer.VERSION, all, afterId, BATCH);
            if (batch.isEmpty()) {
                break;
            }
            apply(source, batch, ratesChecked, counts);
            afterId = batch.getLast().listingId();
            log.info("{}: {} ads normalized again so far", source.code(), counts[0]);
        }
        log.info("{}: normalized {} ads again with version {}, {} failed", source.code(), counts[0],
                ListingNormalizer.VERSION, counts[1]);
        return counts[0];
    }

    /** Normalizes these ads again, e.g. after their pin turned out to be a default one. */
    public int renormalize(ListingSource source, List<Long> ids) {
        Set<LocalDate> ratesChecked = new HashSet<>();
        int[] counts = new int[2];
        for (int from = 0; from < ids.size(); from += BATCH) {
            apply(source, listings.rawOf(ids.subList(from, Math.min(ids.size(), from + BATCH))), ratesChecked, counts);
        }
        return counts[0];
    }

    private void apply(ListingSource source, List<ListingRepository.Raw> batch, Set<LocalDate> ratesChecked,
                       int[] counts) {
        for (ListingRepository.Raw raw : batch) {
            try {
                LocalDate day = raw.fetchedAt().atZone(CrawlService.MOLDOVA).toLocalDate();
                if (ratesChecked.add(day)) {
                    rates.ensure(day);
                }
                SourceAd ad = source.parse(new RawAd(raw.externalId(), Bytes.gunzip(raw.bodyGzip())));
                NormalizedListing n = normalizer.normalize(ad, day);
                tx.executeWithoutResult(s -> listings.update(raw.listingId(), n, null, ad.postedAt(),
                        ad.republishedAt(), raw.sha256(), ListingNormalizer.VERSION));
                counts[0]++;
            } catch (RuntimeException e) {
                counts[1]++;
                log.warn("{} ad {}: not normalized again: {}", source.code(), raw.externalId(), e.toString());
            }
        }
    }
}
