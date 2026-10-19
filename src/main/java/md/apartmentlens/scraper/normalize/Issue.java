package md.apartmentlens.scraper.normalize;

/**
 * A value the normalizer could not use. The field stays empty and the raw value is kept here,
 * so new rules can be written from real examples.
 *
 * @param field  the listing field, e.g. "floor"
 * @param raw    the value as published
 * @param reason a short code, e.g. "unparsed", "implausible", "conflict"
 */
public record Issue(String field, String raw, String reason) {
}
