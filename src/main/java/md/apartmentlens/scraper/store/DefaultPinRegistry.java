package md.apartmentlens.scraper.store;

import java.util.HashSet;
import java.util.Set;
import md.apartmentlens.scraper.normalize.address.DefaultPins;
import md.apartmentlens.scraper.source.GeoPoint;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Default pins found in the stored ads: one coordinate (to a metre) used by at least {@value #MIN_ADS}
 * active ads on at least {@value #MIN_STREETS} different streets, none of which has half of them (a real
 * building is pinned on one or two streets); and street-level pins, one coordinate used for at least
 * {@value #MIN_NUMBERS} different house numbers of the same street.
 */
public class DefaultPinRegistry implements DefaultPins {

    static final int MIN_ADS = 5;
    static final int MIN_STREETS = 3;

    static final int MIN_NUMBERS = 3;

    private final JdbcClient jdbc;
    private volatile Set<Long> keys = Set.of();
    private volatile Set<Long> streetKeys = Set.of();

    public DefaultPinRegistry(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean isDefault(GeoPoint pin) {
        return keys.contains(DefaultPins.key(pin));
    }

    @Override
    public boolean isStreetLevel(GeoPoint pin) {
        return streetKeys.contains(DefaultPins.key(pin));
    }

    public int size() {
        return keys.size();
    }

    public int streetLevelSize() {
        return streetKeys.size();
    }

    /** Reloads the set from the database; returns the pins that became or stopped being default. */
    public Set<Long> refresh() {
        Set<Long> fresh = new HashSet<>(jdbc.sql("""
                        with p as (
                            select round(st_y(pin::geometry) * 100000)::bigint as lat,
                                   round(st_x(pin::geometry) * 100000)::bigint as lon,
                                   -- what sellers wrote, not a street derived from the pin itself
                                   case when street_match = 'from_pin' then null else street_name end as street_name
                            from ingest.listing where pin is not null and pin_radius_m is null and status = 'active'),
                        s as (select lat, lon, street_name, count(*) as n from p group by lat, lon, street_name)
                        select lat, lon from s group by lat, lon
                        having sum(n) >= ? and count(street_name) >= ? and max(n) * 2 <= sum(n)
                        """)
                .params(MIN_ADS, MIN_STREETS)
                .query((rs, i) -> (rs.getLong(1) << 32) ^ (rs.getLong(2) & 0xffffffffL))
                .list());
        // One point for at least MIN_NUMBERS different house numbers of one street (12/2 and 12/5 are one building).
        Set<Long> street = new HashSet<>(jdbc.sql("""
                        with p as (
                            select round(st_y(pin::geometry) * 100000)::bigint as lat,
                                   round(st_x(pin::geometry) * 100000)::bigint as lon,
                                   street_name, substring(house_number from '^\\d+') as base
                            from ingest.listing
                            where pin is not null and pin_radius_m is null and status = 'active' and street_name is not null
                              and street_match not in ('from_pin', 'unresolved'))
                        select lat, lon from p group by lat, lon, street_name
                        having count(distinct coalesce(base, '')) >= ?
                        """)
                .param(MIN_NUMBERS)
                .query((rs, i) -> (rs.getLong(1) << 32) ^ (rs.getLong(2) & 0xffffffffL))
                .list());
        street.removeAll(fresh);
        Set<Long> changed = symmetricDifference(fresh, keys);
        changed.addAll(symmetricDifference(street, streetKeys));
        keys = Set.copyOf(fresh);
        streetKeys = Set.copyOf(street);
        return changed;
    }

    private static Set<Long> symmetricDifference(Set<Long> a, Set<Long> b) {
        Set<Long> out = new HashSet<>(a);
        out.addAll(b);
        Set<Long> both = new HashSet<>(a);
        both.retainAll(b);
        out.removeAll(both);
        return out;
    }
}
