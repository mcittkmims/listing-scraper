package md.apartmentlens.scraper.photo;

import java.util.HexFormat;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;

/** {@code media.image}, {@code media.source_photo} and the jobs that announce new images. */
public class PhotoRepository {

    /** Attempts before a photo is given up. */
    public static final int MAX_ATTEMPTS = 5;

    /** A photo to download: its key at the source and a link that works now. */
    public record Pending(short sourceId, String photoKey, String url) {
    }

    private final JdbcClient jdbc;

    public PhotoRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Photos not downloaded yet that an active ad shows: up to {@code perServer} for each photo server, so all
     * servers can be asked at once, the newest ads first; each with the link of the ad seen most recently (the
     * freshest signature where links expire). A failed photo waits 1, 2, 4... hours.
     */
    public List<Pending> pending(int perServer) {
        return jdbc.sql("""
                        select source_id, photo_key, url from (
                        select sp.source_id, sp.photo_key, x.url,
                               row_number() over (partition by substring(x.url from '^https?://([^/]+)')
                                                  order by x.listing_id desc) as n
                        from media.source_photo sp
                        join lateral (
                            select coalesce(nullif(l.photo_urls[li.position + 1], ''),
                                            case when sp.photo_key like 'http%' then sp.photo_key end) as url,
                                   l.id as listing_id
                            from media.listing_image li join ingest.listing l on l.id = li.listing_id
                            where li.source_id = sp.source_id and li.photo_key = sp.photo_key and l.status = 'active'
                            order by l.last_seen_at desc limit 1) x on true
                        where sp.image_id is null and sp.attempts < ? and x.url is not null
                          and (sp.last_attempt_at is null
                               or sp.last_attempt_at < now() - interval '1 hour' * power(2, sp.attempts - 1))
                        ) p where n <= ?
                        """)
                .params(MAX_ATTEMPTS, perServer)
                .query((rs, i) -> new Pending(rs.getShort(1), rs.getString(2), rs.getString(3)))
                .list();
    }

    /**
     * The photo is downloaded: its image (one per content, however many photos show it) is linked to it, and a
     * new image is announced to the vision workers.
     *
     * @return true when the image is new
     */
    public boolean stored(Pending photo, byte[] sha256, int bytes, String contentType, String path) {
        Long created = jdbc.sql("""
                        insert into media.image (sha256, bytes, content_type) values (?, ?, ?)
                        on conflict (sha256) do nothing returning id
                        """)
                .params(sha256, bytes, contentType).query(Long.class).optional().orElse(null);
        long imageId = created != null ? created
                : jdbc.sql("select id from media.image where sha256 = ?").param(sha256).query(Long.class).single();
        jdbc.sql("""
                        update media.source_photo
                        set image_id = ?, attempts = attempts + 1, last_attempt_at = now(), last_error = null
                        where source_id = ? and photo_key = ?
                        """)
                .params(imageId, photo.sourceId(), photo.photoKey()).update();
        if (created != null) {
            jdbc.sql("insert into ops.job (kind, payload) values ('image.fingerprint', cast(? as jsonb))")
                    .param("{\"image_id\": " + imageId + ", \"sha256\": \"" + HexFormat.of().formatHex(sha256)
                            + "\", \"path\": \"" + path + "\"}")
                    .update();
        }
        return created != null;
    }

    /** The download failed; a photo that is gone at the source ({@code permanent}) is not tried again. */
    public void failed(Pending photo, String error, boolean permanent) {
        jdbc.sql("""
                        update media.source_photo
                        set attempts = case when ? then ? else attempts + 1 end, last_attempt_at = now(), last_error = ?
                        where source_id = ? and photo_key = ?
                        """)
                .params(permanent, MAX_ATTEMPTS, error.length() > 500 ? error.substring(0, 500) : error,
                        photo.sourceId(), photo.photoKey())
                .update();
    }
}
