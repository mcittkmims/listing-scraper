package md.apartmentlens.scraper.source;

/**
 * One ad as a source's listing shows it. A source whose listing carries every field gives the whole ad;
 * a source whose listing shows only a summary gives that summary, and the ad itself is fetched with
 * {@link ListingSource#fetch} only when the summary changed or the stored copy is old.
 *
 * @param summary what the listing shows of the ad (volatile parts removed); null when {@code ad} is given
 * @param ad      the whole ad; null when only the summary is known
 */
public record Listed(String externalId, byte[] summary, RawAd ad) {

    public static Listed complete(RawAd ad) {
        return new Listed(ad.externalId(), null, ad);
    }

    public static Listed summary(String externalId, byte[] summary) {
        return new Listed(externalId, summary, null);
    }
}
