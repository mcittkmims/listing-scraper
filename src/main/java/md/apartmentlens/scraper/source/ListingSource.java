package md.apartmentlens.scraper.source;

import java.io.IOException;
import java.time.Duration;
import java.util.Optional;
import java.util.function.Consumer;

/** A website ads are collected from. One implementation per source; all share the normalizer. */
public interface ListingSource {

    /** The code in {@code ingest.source}, e.g. "999md". */
    String code();

    /**
     * Goes through every ad the source currently lists for sale in the categories collected, calling
     * {@code sink} for each: the whole ad, or the listing's summary of it (see {@link Listed}). Ads may
     * repeat when the listing shifts while it is read.
     *
     * @return the number of ads the source says it has, or -1 when it does not say
     * @throws IOException when the listing cannot be read to the end
     */
    int crawl(Consumer<Listed> sink) throws IOException;

    /** Attempts for one ad's page: a page that keeps failing is asked for again by the next pass. */
    int AD_ATTEMPTS = 2;

    /**
     * Fetches one whole ad: present while it is listed for sale, empty once it is gone.
     *
     * @throws IOException when the source cannot be asked, so nothing can be concluded
     */
    Optional<RawAd> fetch(String externalId) throws IOException;

    /**
     * For a source that lists summaries: how old a stored ad may get before it is read again although its
     * summary did not change. Ads are read again between 70% and 100% of this age, spread by ad.
     */
    default Duration detailMaxAge() {
        return Duration.ofDays(10);
    }

    /** Reads the stored content of an ad. */
    SourceAd parse(RawAd raw);

    /** The part of the content that matters for change detection (volatile fields removed). */
    byte[] stableContent(RawAd raw);
}
