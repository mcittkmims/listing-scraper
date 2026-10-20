package md.apartmentlens.scraper.store;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import md.apartmentlens.scraper.crawl.CrawlStats;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;

/** {@code ingest.source} and {@code ingest.crawl_run}. */
public class CrawlRunRepository {

    private final JdbcClient jdbc;

    public CrawlRunRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public short sourceId(String code) {
        return jdbc.sql("select id from ingest.source where code = ?").param(code).query(Short.class).optional()
                .orElseThrow(() -> new IllegalStateException("source " + code + " is missing from ingest.source"));
    }

    /**
     * Starts a run, or returns empty when another run of the source is still going. A run left "running"
     * for longer than {@code staleAfter} (the process died) is closed as failed first.
     */
    public Optional<Long> start(short sourceId, Duration staleAfter) {
        jdbc.sql("""
                        update ingest.crawl_run set status = 'failed', finished_at = now(), error = 'abandoned'
                        where source_id = ? and status = 'running' and started_at < ?
                        """)
                .params(sourceId, Timestamp.from(Instant.now().minus(staleAfter))).update();
        try {
            return Optional.of(jdbc.sql("insert into ingest.crawl_run (source_id) values (?) returning id")
                    .param(sourceId).query(Long.class).single());
        } catch (DuplicateKeyException e) {
            return Optional.empty();
        }
    }

    public Instant startedAt(long runId) {
        return jdbc.sql("select started_at from ingest.crawl_run where id = ?").param(runId)
                .query(Timestamp.class).single().toInstant();
    }

    public void finish(long runId, String status, CrawlStats stats, String error) {
        jdbc.sql("""
                        update ingest.crawl_run set status = ?, finished_at = now(), advertised_total = ?,
                               requests = ?, ads_seen = ?, ads_new = ?, ads_changed = ?, price_changes = ?,
                               ads_gone = ?, errors = ?, error = ?
                        where id = ?
                        """)
                .params(status, stats.advertised() < 0 ? null : stats.advertised(), stats.requests(), stats.seen(),
                        stats.added(), stats.changed(), stats.priceChanges(), stats.gone(), stats.errors(), error, runId)
                .update();
    }

    /** Ads seen by the last completed run, to tell a short pass from ads really going away. */
    public Optional<Integer> lastCompletedSeen(short sourceId) {
        return jdbc.sql("""
                        select ads_seen from ingest.crawl_run where source_id = ? and status = 'completed'
                        order by started_at desc limit 1
                        """)
                .param(sourceId).query(Integer.class).optional();
    }

    /** When the last run that ended (completed or partial) started. */
    public Optional<Instant> lastFinishedStart(short sourceId) {
        return jdbc.sql("""
                        select started_at from ingest.crawl_run where source_id = ? and status in ('completed', 'partial')
                        order by started_at desc limit 1
                        """)
                .param(sourceId).query(Timestamp.class).optional().map(Timestamp::toInstant);
    }
}
