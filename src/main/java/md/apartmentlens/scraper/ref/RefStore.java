package md.apartmentlens.scraper.ref;

import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Writes the {@code ref} tables. Each import replaces one source's rows in a single transaction. */
public class RefStore {

    private final JdbcClient jdbc;
    private final JdbcTemplate template;

    public RefStore(JdbcClient jdbc, JdbcTemplate template) {
        this.jdbc = jdbc;
        this.template = template;
    }

    public void clear(String source) {
        for (String table : List.of("address_point", "street", "locality", "admin_area")) {
            jdbc.sql("delete from ref." + table + " where source = ?").param(source).update();
        }
    }

    public void adminArea(String source, String fullName, String name, String kind, String geoJson) {
        jdbc.sql("""
                        insert into ref.admin_area (source, full_name, name, kind, geom)
                        values (?, ?, ?, ?, st_multi(st_setsrid(st_geomfromgeojson(?), 4326))::geography)
                        on conflict (source, full_name) do nothing
                        """)
                .params(source, fullName, name, kind, geoJson).update();
    }

    public void locality(String source, CadastreNames.Locality l) {
        jdbc.sql("""
                        insert into ref.locality (source, full_name, name, kind, commune, district)
                        values (?, ?, ?, ?, ?, ?) on conflict (source, full_name) do nothing
                        """)
                .params(source, l.fullName(), l.name(), l.kind(), l.commune(), l.district()).update();
    }

    /** A street row; returns its id. */
    public long street(String source, String sourceId, String locality, String type, String nameRo, String nameRu,
                       List<String> aliases, String geoJson) {
        return jdbc.sql("""
                        insert into ref.street (source, source_id, locality, type, name_ro, name_ru, aliases, geom)
                        values (?, ?, ?, ?, ?, ?, ?,
                                case when cast(? as text) is null then null
                                     else st_multi(st_setsrid(st_geomfromgeojson(?), 4326))::geography end)
                        returning id
                        """)
                .params(source, sourceId, locality, type, nameRo, nameRu, aliases.toArray(new String[0]), geoJson, geoJson)
                .query(Long.class).single();
    }

    /** One address point to insert; {@code streetId} may be null. */
    public record Address(Long streetId, String locality, String streetText, String number, double lat, double lon) {
    }

    public void addresses(String source, List<Address> batch) {
        template.batchUpdate("""
                        insert into ref.address_point (source, street_id, locality, street_text, number, geom)
                        values (?, ?, ?, ?, ?, st_setsrid(st_makepoint(?, ?), 4326)::geography)
                        """, batch, 5_000, (ps, a) -> {
            ps.setString(1, source);
            if (a.streetId() == null) {
                ps.setNull(2, Types.BIGINT);
            } else {
                ps.setLong(2, a.streetId());
            }
            ps.setString(3, a.locality());
            ps.setString(4, a.streetText());
            ps.setString(5, a.number());
            ps.setDouble(6, a.lon());
            ps.setDouble(7, a.lat());
        });
    }

    /**
     * The cadastre writes one locality in several ways ("or. Căușeni", "r-nul Căușeni, or. Căușeni"). Variants
     * with the same name, kind and commune, at most 15 km apart, become the longest one.
     */
    public int mergeLocalityVariants(String source) {
        locate(source);
        int merged = jdbc.sql("""
                        create temporary table locality_merge on commit drop as
                        select s.full_name as variant, min(l.full_name) as canonical
                        from ref.locality s join ref.locality l
                          on l.source = s.source and l.name = s.name and l.kind is not distinct from s.kind
                         and l.commune is not distinct from s.commune
                         and (s.district is null or l.district is null or l.district = s.district)
                         and length(l.full_name) > length(s.full_name)
                         and st_dwithin(l.point, s.point, 15000)
                        where s.source = ?
                        group by s.full_name having count(distinct l.full_name) = 1
                        """).param(source).update();
        for (String table : List.of("address_point", "street")) {
            jdbc.sql("update ref." + table + " t set locality = m.canonical from locality_merge m "
                    + "where t.source = ? and t.locality = m.variant").param(source).update();
        }
        jdbc.sql("delete from ref.locality l using locality_merge m where l.source = ? and l.full_name = m.variant")
                .param(source).update();
        // The same street stored under two variants: keep one, move its addresses there.
        jdbc.sql("""
                        create temporary table street_merge on commit drop as
                        select id, min(id) over (partition by locality, type, lower(name_ro)) as keep
                        from ref.street where source = ?
                        """).param(source).update();
        jdbc.sql("update ref.address_point a set street_id = m.keep from street_merge m where a.street_id = m.id and m.id <> m.keep")
                .update();
        jdbc.sql("delete from ref.street s using street_merge m where s.id = m.id and m.id <> m.keep").update();
        return merged;
    }

    /**
     * Links each address without a street to the street of the same type and name within 1 km of it, the
     * nearest one. Needed where the address and the street name their locality differently.
     */
    public int linkAddressesByPosition(String source) {
        return jdbc.sql("""
                        update ref.address_point a set street_id = x.street_id
                        from (
                            select p.id, (select s.id from ref.street s
                                          where s.source = p.source and s.type = p.type
                                            and lower(unaccent(s.name_ro)) = lower(unaccent(p.name))
                                            and st_dwithin(s.geom, p.geom, 1000)
                                          order by s.geom::geometry <-> p.geom::geometry limit 1) as street_id
                            from (select id, source, geom,
                                         case split_part(street_text, ' ', 1)
                                             when 'str.' then 'street' when 'str-la' then 'lane' when 'bd.' then 'boulevard'
                                             when 'șos.' then 'highway' when 'dr.' then 'road' when 'fun.' then 'dead_end'
                                             when 'pas.' then 'passage' when 'p-ța' then 'square' when 'aleea' then 'alley'
                                         end as type,
                                         regexp_replace(street_text, '^\\S+\\s+', '') as name
                                  from ref.address_point
                                  where source = ? and street_id is null and street_text is not null) p
                        ) x
                        where a.id = x.id and x.street_id is not null
                        """).param(source).update();
    }

    private void locate(String source) {
        jdbc.sql("""
                        update ref.locality l set point = c.point
                        from (select locality, st_centroid(st_collect(geom::geometry))::geography as point
                              from ref.address_point where source = ? group by locality) c
                        where l.source = ? and l.full_name = c.locality
                        """).params(source, source).update();
        jdbc.sql("""
                        update ref.locality l set point = c.point
                        from (select locality, st_centroid(st_collect(geom::geometry))::geography as point
                              from ref.street where source = ? group by locality) c
                        where l.source = ? and l.full_name = c.locality and l.point is null
                        """).params(source, source).update();
    }

    /** Places each locality at the centre of its addresses, or else of its streets; marks the import time. */
    public void finish(String source, Instant at) {
        jdbc.sql("update ref.locality set point = null where source = ?").param(source).update();
        locate(source);
        jdbc.sql("update ref.source set imported_at = ? where code = ?").params(Timestamp.from(at), source).update();
    }

    public Map<String, Integer> counts(String source) {
        Map<String, Integer> out = new HashMap<>();
        for (String table : List.of("admin_area", "locality", "street", "address_point")) {
            out.put(table, jdbc.sql("select count(*) from ref." + table + " where source = ?").param(source)
                    .query(Integer.class).single());
        }
        return out;
    }
}
