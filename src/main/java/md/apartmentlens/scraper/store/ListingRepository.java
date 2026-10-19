package md.apartmentlens.scraper.store;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import md.apartmentlens.scraper.normalize.NormalizedListing;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.core.support.AbstractSqlTypeValue;
import tools.jackson.databind.json.JsonMapper;

/** {@code ingest.listing}, {@code ingest.listing_raw} and {@code ingest.listing_snapshot}. */
public class ListingRepository {

    /** The stored state of an ad, enough to tell what changed. */
    public record Stored(long id, String status, byte[] contentSha256, int normalizerVersion,
                         BigDecimal priceAmount, String priceCurrency) {
    }

    /** An ad to check again: active, but not seen by the current pass; with its last price. */
    public record Unseen(long id, String externalId, BigDecimal priceAmount, String priceCurrency, BigDecimal priceEur) {
    }

    /** The latest raw content of an ad, for normalizing again. */
    public record Raw(long listingId, String externalId, Instant fetchedAt, byte[] sha256, byte[] bodyGzip) {
    }

    /** Normalized columns and how each is read from a {@link NormalizedListing}. */
    private static final Map<String, Function<NormalizedListing, Object>> COLUMNS = new LinkedHashMap<>();

    static {
        COLUMNS.put("property_type", NormalizedListing::propertyType);
        COLUMNS.put("seller_type", NormalizedListing::sellerType);
        COLUMNS.put("seller_name", NormalizedListing::sellerName);
        COLUMNS.put("seller_account", NormalizedListing::sellerAccount);
        COLUMNS.put("title", NormalizedListing::title);
        COLUMNS.put("description_ro", NormalizedListing::descriptionRo);
        COLUMNS.put("description_ru", NormalizedListing::descriptionRu);
        COLUMNS.put("price_amount", NormalizedListing::priceAmount);
        COLUMNS.put("price_currency", NormalizedListing::priceCurrency);
        COLUMNS.put("price_eur", NormalizedListing::priceEur);
        COLUMNS.put("price_per_m2_eur", NormalizedListing::pricePerM2Eur);
        COLUMNS.put("price_negotiable", NormalizedListing::priceNegotiable);
        COLUMNS.put("down_payment", NormalizedListing::downPayment);
        COLUMNS.put("rooms", NormalizedListing::rooms);
        COLUMNS.put("rooms_open_ended", NormalizedListing::roomsOpenEnded);
        COLUMNS.put("has_living_room", NormalizedListing::hasLivingRoom);
        COLUMNS.put("area_total_m2", NormalizedListing::areaTotalM2);
        COLUMNS.put("area_living_m2", NormalizedListing::areaLivingM2);
        COLUMNS.put("area_kitchen_m2", NormalizedListing::areaKitchenM2);
        COLUMNS.put("land_area_m2", NormalizedListing::landAreaM2);
        COLUMNS.put("floor", NormalizedListing::floor);
        COLUMNS.put("floor_kind", NormalizedListing::floorKind);
        COLUMNS.put("floors_total", NormalizedListing::floorsTotal);
        COLUMNS.put("bathrooms", NormalizedListing::bathrooms);
        COLUMNS.put("balconies", NormalizedListing::balconies);
        COLUMNS.put("ceiling_height_m", NormalizedListing::ceilingHeightM);
        COLUMNS.put("housing_stock", NormalizedListing::housingStock);
        COLUMNS.put("building_material", NormalizedListing::buildingMaterial);
        COLUMNS.put("building_series", NormalizedListing::buildingSeries);
        COLUMNS.put("condition", NormalizedListing::condition);
        COLUMNS.put("developer", NormalizedListing::developer);
        COLUMNS.put("parking", NormalizedListing::parking);
        COLUMNS.put("region", NormalizedListing::region);
        COLUMNS.put("locality", NormalizedListing::locality);
        COLUMNS.put("district", NormalizedListing::district);
        COLUMNS.put("region_code", NormalizedListing::regionCode);
        COLUMNS.put("locality_code", NormalizedListing::localityCode);
        COLUMNS.put("district_code", NormalizedListing::districtCode);
        COLUMNS.put("street_raw", NormalizedListing::streetRaw);
        COLUMNS.put("house_number_raw", NormalizedListing::houseNumberRaw);
        COLUMNS.put("street_type", NormalizedListing::streetType);
        COLUMNS.put("street_name", NormalizedListing::streetName);
        COLUMNS.put("street_match", NormalizedListing::streetMatch);
        COLUMNS.put("house_number", NormalizedListing::houseNumber);
        COLUMNS.put("location_source", NormalizedListing::locationSource);
        COLUMNS.put("location_precision", NormalizedListing::locationPrecision);
        COLUMNS.put("has_video", NormalizedListing::hasVideo);
    }

    /** Columns that need an SQL expression around their parameter. */
    private static final String SPECIAL_COLUMNS = "geom, phone_hashes, photo_keys, amenities, issues";
    private static final String GEOM_VALUE = "case when cast(:lat as double precision) is null then null else "
            + "st_setsrid(st_makepoint(cast(:lon as double precision), cast(:lat as double precision)), 4326)::geography end";
    private static final String SPECIAL_VALUES = GEOM_VALUE + ", :phone_hashes, :photo_keys, :amenities, cast(:issues as jsonb)";

    private final JdbcClient jdbc;
    private final JsonMapper json;

    public ListingRepository(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public Optional<Stored> find(short sourceId, String externalId) {
        return jdbc.sql("""
                        select id, status, content_sha256, normalizer_version, price_amount, price_currency
                        from ingest.listing where source_id = ? and external_id = ?
                        """)
                .params(sourceId, externalId)
                .query((rs, i) -> new Stored(rs.getLong(1), rs.getString(2), rs.getBytes(3), rs.getInt(4),
                        rs.getBigDecimal(5), rs.getString(6)))
                .optional();
    }

    public long insert(short sourceId, String externalId, String url, NormalizedListing l, Instant seenAt,
                       Instant postedAt, Instant republishedAt, byte[] contentSha256, int normalizerVersion) {
        MapSqlParameterSource p = params(l)
                .addValue("source_id", sourceId)
                .addValue("external_id", externalId)
                .addValue("url", url)
                .addValue("seen", Timestamp.from(seenAt))
                .addValue("posted_at", timestamp(postedAt))
                .addValue("republished_at", timestamp(republishedAt))
                .addValue("content_sha256", contentSha256)
                .addValue("normalizer_version", normalizerVersion);
        String columns = String.join(", ", COLUMNS.keySet());
        String values = COLUMNS.keySet().stream().map(c -> ":" + c).collect(Collectors.joining(", "));
        return jdbc.sql("insert into ingest.listing (source_id, external_id, url, status, first_seen_at, last_seen_at, "
                        + "posted_at, republished_at, content_sha256, normalizer_version, normalized_at, " + columns + ", "
                        + SPECIAL_COLUMNS + ") values (:source_id, :external_id, :url, 'active', :seen, :seen, "
                        + ":posted_at, :republished_at, :content_sha256, :normalizer_version, now(), " + values + ", "
                        + SPECIAL_VALUES + ") returning id")
                .paramSource(p).query(Long.class).single();
    }

    /** Replaces the normalized fields; also marks the ad active and seen when {@code seenAt} is given. */
    public void update(long id, NormalizedListing l, Instant seenAt, Instant postedAt, Instant republishedAt,
                       byte[] contentSha256, int normalizerVersion) {
        MapSqlParameterSource p = params(l)
                .addValue("id", id)
                .addValue("seen", timestamp(seenAt))
                .addValue("posted_at", timestamp(postedAt))
                .addValue("republished_at", timestamp(republishedAt))
                .addValue("content_sha256", contentSha256)
                .addValue("normalizer_version", normalizerVersion);
        String sets = COLUMNS.keySet().stream().map(c -> c + " = :" + c).collect(Collectors.joining(", "));
        jdbc.sql("update ingest.listing set " + sets + ", geom = " + GEOM_VALUE
                        + ", phone_hashes = :phone_hashes, photo_keys = :photo_keys, amenities = :amenities, "
                        + "issues = cast(:issues as jsonb), content_sha256 = :content_sha256, "
                        + "normalizer_version = :normalizer_version, normalized_at = now(), "
                        + "posted_at = coalesce(:posted_at, posted_at), "
                        + "republished_at = coalesce(:republished_at, republished_at), "
                        + "last_seen_at = coalesce(cast(:seen as timestamptz), last_seen_at), "
                        + "status = case when cast(:seen as timestamptz) is null then status else 'active' end, "
                        + "gone_at = case when cast(:seen as timestamptz) is null then gone_at else null end "
                        + "where id = :id")
                .paramSource(p).update();
    }

    /** The ad was seen again unchanged. */
    public void touch(long id, Instant seenAt, Instant republishedAt) {
        jdbc.sql("""
                        update ingest.listing set last_seen_at = ?, republished_at = coalesce(?, republished_at),
                               status = 'active', gone_at = null
                        where id = ?
                        """)
                .params(Timestamp.from(seenAt), timestamp(republishedAt), id).update();
    }

    public void markGone(long id, Instant at) {
        jdbc.sql("update ingest.listing set status = 'gone', gone_at = ? where id = ? and status = 'active'")
                .params(Timestamp.from(at), id).update();
    }

    /** Stores raw content unless the same content is already stored for the ad. */
    public void insertRaw(long listingId, long crawlRunId, Instant fetchedAt, byte[] sha256, byte[] bodyGzip) {
        jdbc.sql("""
                        insert into ingest.listing_raw (listing_id, crawl_run_id, fetched_at, sha256, body_gzip)
                        values (?, ?, ?, ?, ?) on conflict (listing_id, sha256) do nothing
                        """)
                .params(listingId, crawlRunId, Timestamp.from(fetchedAt), sha256, bodyGzip).update();
    }

    public void insertSnapshot(long listingId, long crawlRunId, Instant at, String status, BigDecimal amount,
                               String currency, BigDecimal eur) {
        jdbc.sql("""
                        insert into ingest.listing_snapshot (listing_id, crawl_run_id, captured_at, status,
                                                             price_amount, price_currency, price_eur)
                        values (?, ?, ?, ?, ?, ?, ?)
                        """)
                .params(listingId, crawlRunId, Timestamp.from(at), status, amount, currency, eur).update();
    }

    /** Active ads of a source not seen since {@code since}. */
    public List<Unseen> activeNotSeenSince(short sourceId, Instant since) {
        return jdbc.sql("""
                        select id, external_id, price_amount, price_currency, price_eur from ingest.listing
                        where source_id = ? and status = 'active' and last_seen_at < ? order by id
                        """)
                .params(sourceId, Timestamp.from(since))
                .query((rs, i) -> new Unseen(rs.getLong(1), rs.getString(2), rs.getBigDecimal(3), rs.getString(4),
                        rs.getBigDecimal(5)))
                .list();
    }

    public int countActive(short sourceId) {
        return jdbc.sql("select count(*) from ingest.listing where source_id = ? and status = 'active'")
                .param(sourceId).query(Integer.class).single();
    }

    /** Ads normalized by an older version (or all, when {@code all}), after {@code afterId}, with their latest raw content. */
    public List<Raw> toRenormalize(short sourceId, int version, boolean all, long afterId, int limit) {
        return jdbc.sql("""
                        select l.id, l.external_id, r.fetched_at, r.sha256, r.body_gzip
                        from ingest.listing l
                        join lateral (select fetched_at, sha256, body_gzip from ingest.listing_raw
                                      where listing_id = l.id order by fetched_at desc limit 1) r on true
                        where l.source_id = ? and l.id > ? and (? or l.normalizer_version < ?)
                        order by l.id limit ?
                        """)
                .params(sourceId, afterId, all, version, limit)
                .query((rs, i) -> new Raw(rs.getLong(1), rs.getString(2), rs.getTimestamp(3).toInstant(), rs.getBytes(4),
                        rs.getBytes(5)))
                .list();
    }

    private MapSqlParameterSource params(NormalizedListing l) {
        MapSqlParameterSource p = new MapSqlParameterSource();
        COLUMNS.forEach((column, getter) -> p.addValue(column, getter.apply(l)));
        p.addValue("lat", l.point() == null ? null : l.point().lat(), Types.DOUBLE);
        p.addValue("lon", l.point() == null ? null : l.point().lon(), Types.DOUBLE);
        p.addValue("phone_hashes", array("bytea", l.phoneHashes().toArray(new byte[0][])), Types.ARRAY);
        p.addValue("photo_keys", array("text", l.photoKeys().toArray(new String[0])), Types.ARRAY);
        p.addValue("amenities", array("text", l.amenities().toArray(new String[0])), Types.ARRAY);
        p.addValue("issues", json.writeValueAsString(l.issues()));
        return p;
    }

    private static AbstractSqlTypeValue array(String type, Object[] values) {
        return new AbstractSqlTypeValue() {
            @Override
            protected Object createTypeValue(Connection con, int sqlType, String typeName) throws SQLException {
                return con.createArrayOf(type, values);
            }
        };
    }

    private static Timestamp timestamp(Instant i) {
        return i == null ? null : Timestamp.from(i);
    }
}
