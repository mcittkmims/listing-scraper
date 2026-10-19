package md.apartmentlens.scraper.source;

import java.io.IOException;
import java.util.Optional;
import java.util.function.Consumer;

/** A website ads are collected from. One implementation per source; all share the normalizer. */
public interface ListingSource {

    /** The code in {@code ingest.source}, e.g. "999md". */
    String code();

    /**
     * Goes through every ad the source currently lists for sale in the categories collected, calling
     * {@code sink} for each. Ads may repeat when the listing shifts while it is read.
     *
     * @return the number of ads the source says it has, or -1 when it does not say
     * @throws IOException when the listing cannot be read to the end
     */
    int crawl(Consumer<RawAd> sink) throws IOException;

    /**
     * Fetches one ad again: present while it is still listed for sale, empty once it is gone.
     *
     * @throws IOException when the source cannot be asked, so nothing can be concluded
     */
    Optional<RawAd> fetch(String externalId) throws IOException;

    /** Reads the stored content of an ad. */
    SourceAd parse(RawAd raw);

    /** The part of the content that matters for change detection (volatile fields removed). */
    byte[] stableContent(RawAd raw);
}
