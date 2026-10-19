package md.apartmentlens.scraper.source;

/**
 * One ad exactly as a source returned it (JSON or HTML bytes), kept so normalization can be re-run.
 *
 * @param externalId the ad id at the source
 * @param body       the content as received
 */
public record RawAd(String externalId, byte[] body) {
}
