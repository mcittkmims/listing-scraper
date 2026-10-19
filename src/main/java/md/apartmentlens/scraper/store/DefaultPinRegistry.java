package md.apartmentlens.scraper.store;

import java.util.HashSet;
import java.util.Set;
import md.apartmentlens.scraper.normalize.address.DefaultPins;
import md.apartmentlens.scraper.source.GeoPoint;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Default pins found in the stored ads: one coordinate (to a metre) used by at least {@value #MIN_ADS}
 * active ads on at least {@value #MIN_STREETS} different streets, none of which has half of them.
 * A real building is pinned on one or two streets; a residential complex has one main street.
 */
public class DefaultPinRegistry implements DefaultPins {

    static final int MIN_ADS = 5;
    static final int MIN_STREETS = 3;

    private final JdbcClient jdbc;
    private volatile Set<Long> keys = Set.of();

    public DefaultPinRegistry(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean isDefault(GeoPoint pin) {
        return keys.contains(DefaultPins.key(pin));
    }

    public int size() {
        return keys.size();
    }

    /** Reloads the set from the database; returns the pins that became or stopped being default. */
    public Set<Long> refresh() {
        Set<Long> fresh = new HashSet<>(jdbc.sql("""
                        with p as (
                            select round(st_y(pin::geometry) * 100000)::bigint as lat,
                                   round(st_x(pin::geometry) * 100000)::bigint as lon,
                                   street_name
                            from ingest.listing where pin is not null and status = 'active'),
                        s as (select lat, lon, street_name, count(*) as n from p group by lat, lon, street_name)
                        select lat, lon from s group by lat, lon
                        having sum(n) >= ? and count(street_name) >= ? and max(n) * 2 <= sum(n)
                        """)
                .params(MIN_ADS, MIN_STREETS)
                .query((rs, i) -> (rs.getLong(1) << 32) ^ (rs.getLong(2) & 0xffffffffL))
                .list());
        Set<Long> changed = new HashSet<>(fresh);
        changed.addAll(keys);
        Set<Long> both = new HashSet<>(fresh);
        both.retainAll(keys);
        changed.removeAll(both);
        keys = Set.copyOf(fresh);
        return changed;
    }
}
