package md.apartmentlens.scraper.crawl;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import javax.sql.DataSource;
import md.apartmentlens.scraper.Fixtures;
import md.apartmentlens.scraper.http.HttpStatusException;
import md.apartmentlens.scraper.normalize.ListingNormalizer;
import md.apartmentlens.scraper.normalize.PhoneNormalizer;
import md.apartmentlens.scraper.normalize.PriceNormalizer;
import md.apartmentlens.scraper.normalize.address.StreetGazetteer;
import md.apartmentlens.scraper.photo.FilePhotoStore;
import md.apartmentlens.scraper.photo.PhotoDownloader;
import md.apartmentlens.scraper.photo.PhotoDownloaderTestImages;
import md.apartmentlens.scraper.photo.PhotoRepository;
import md.apartmentlens.scraper.rates.BnmRateClient;
import md.apartmentlens.scraper.rates.ExchangeRateService;
import md.apartmentlens.scraper.source.Listed;
import md.apartmentlens.scraper.source.ListingSource;
import md.apartmentlens.scraper.source.RawAd;
import md.apartmentlens.scraper.source.SourceAd;
import md.apartmentlens.scraper.source.ninetynine.NineNineSource;
import md.apartmentlens.scraper.store.CrawlRunRepository;
import md.apartmentlens.scraper.store.DefaultPinRegistry;
import md.apartmentlens.scraper.store.ExchangeRateRepository;
import md.apartmentlens.scraper.store.ListingRepository;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Crawls against the real database image and migrations, connected as the scraper role (so the
 * role's permissions are tested too). The source is a fake that serves synthetic ads.
 */
class CrawlServiceIT {

    static final StreetGazetteer STREETS = Fixtures.streets();
    static PostgreSQLContainer db;
    static DataSource scraper;
    static JdbcClient admin;

    @BeforeAll
    static void startDatabase() {
        Path databaseDir = Path.of(System.getProperty("migrations.dir")).getParent();
        String image = new ImageFromDockerfile("apartment-lens/postgres-test", false)
                .withFileFromPath(".", databaseDir).get();
        db = new PostgreSQLContainer(DockerImageName.parse(image).asCompatibleSubstituteFor("postgres"));
        db.start();
        Flyway.configure()
                .dataSource(db.getJdbcUrl(), db.getUsername(), db.getPassword())
                .locations("filesystem:" + System.getProperty("migrations.dir"))
                .placeholders(Map.of("scraper_password", "scraper-test", "geodata_password", ""))
                .load().migrate();
        admin = JdbcClient.create(new DriverManagerDataSource(db.getJdbcUrl(), db.getUsername(), db.getPassword()));
        scraper = new DriverManagerDataSource(db.getJdbcUrl(), "scraper", "scraper-test");
    }

    @AfterAll
    static void stopDatabase() {
        if (db != null) {
            db.stop();
        }
    }

    final FakeSource source = new FakeSource();
    final MutableClock clock = new MutableClock(Instant.parse("2026-10-19T08:00:00Z"));
    CrawlService crawls;
    RenormalizeService renormalize;
    DefaultPinRegistry defaultPins;
    DefaultPinReview pinReview;

    @BeforeEach
    void setUp() {
        admin.sql("truncate ingest.listing, ingest.crawl_run, ingest.exchange_rate, media.source_photo, media.image, ops.job restart identity cascade").update();
        JdbcClient jdbc = JdbcClient.create(scraper);
        TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(scraper));
        ExchangeRateService rates = new ExchangeRateService(new ExchangeRateRepository(jdbc), new BnmRateClient(null) {
            @Override
            public Map<String, BigDecimal> rates(LocalDate date) {
                return new java.util.HashMap<>(Map.of("EUR", new BigDecimal("20.0000"), "USD", new BigDecimal("18.0000")));
            }
        });
        defaultPins = new DefaultPinRegistry(jdbc);
        ListingNormalizer normalizer = new ListingNormalizer(new PriceNormalizer(rates), Fixtures.places(),
                STREETS, new PhoneNormalizer("test-key-0123456789abcdef"), defaultPins);
        CrawlRunRepository runs = new CrawlRunRepository(jdbc);
        ListingRepository listings = new ListingRepository(jdbc, Fixtures.JSON);
        crawls = new CrawlService(runs, listings, normalizer, rates, tx, clock, () -> 0,
                new CrawlService.Safety(Duration.ofHours(12), 0.5, 1.0));
        renormalize = new RenormalizeService(runs, listings, normalizer, rates, tx);
        pinReview = new DefaultPinReview(defaultPins, listings, renormalize, runs, List.of(source));
        defaultPins.refresh();
    }

    @Test
    void newAdsAreStoredWithRawContentAndAFirstSnapshot() {
        source.put(Fixtures.ninetyNine("apartment.json"));
        source.put(Fixtures.ninetyNine("house.json"));

        CrawlStats stats = crawls.crawl(source).orElseThrow();

        assertThat(stats.seen()).isEqualTo(2);
        assertThat(stats.added()).isEqualTo(2);
        assertThat(stats.errors()).isZero();
        Map<String, Object> flat = admin.sql("""
                        select property_type, street_name, house_number, price_eur, area_total_m2, location_precision,
                               st_y(geom::geometry) as lat, cardinality(phone_hashes) as phones, status
                        from ingest.listing where external_id = '100000001'
                        """).query().singleRow();
        assertThat(flat).containsEntry("property_type", "apartment").containsEntry("street_name", "Ceucari")
                .containsEntry("house_number", "1").containsEntry("location_precision", "building")
                .containsEntry("phones", 1).containsEntry("status", "active");
        assertThat((BigDecimal) flat.get("price_eur")).isEqualByComparingTo("99990");
        assertThat((BigDecimal) flat.get("area_total_m2")).isEqualByComparingTo("66.38");
        assertThat((Double) flat.get("lat")).isEqualTo(47.06786);
        assertThat(count("ingest.listing_raw")).isEqualTo(2);
        assertThat(count("ingest.listing_snapshot")).isEqualTo(2);
        assertThat(admin.sql("select status from ingest.crawl_run").query(String.class).single()).isEqualTo("completed");
        assertThat(count("ingest.exchange_rate")).isEqualTo(2);
    }

    @Test
    void unchangedAdsOnlyUpdateLastSeen_bumpsAreNotChanges() {
        source.put(Fixtures.ninetyNine("apartment.json"));
        crawls.crawl(source);
        clock.advance(Duration.ofHours(4));
        source.replace("100000001", "\"reseted\": \"2026-10-19T14:56:53Z\"", "\"reseted\": \"2026-10-19T20:00:00Z\"");

        CrawlStats stats = crawls.crawl(source).orElseThrow();

        assertThat(stats.changed()).isZero();
        assertThat(count("ingest.listing_raw")).isEqualTo(1);
        assertThat(count("ingest.listing_snapshot")).isEqualTo(1);
        assertThat(admin.sql("select republished_at from ingest.listing").query(java.sql.Timestamp.class).single()
                .toInstant()).isEqualTo(Instant.parse("2026-10-19T20:00:00Z"));
        assertThat(admin.sql("select last_seen_at from ingest.listing").query(java.sql.Timestamp.class).single()
                .toInstant()).isEqualTo(clock.instant());
    }

    @Test
    void aPriceChangeAddsASnapshot() {
        source.put(Fixtures.ninetyNine("apartment.json"));
        crawls.crawl(source);
        clock.advance(Duration.ofHours(4));
        source.replace("100000001", "\"value\": 99990", "\"value\": 95000");

        CrawlStats stats = crawls.crawl(source).orElseThrow();

        assertThat(stats.changed()).isEqualTo(1);
        assertThat(stats.priceChanges()).isEqualTo(1);
        assertThat(admin.sql("select price_amount from ingest.listing_snapshot order by captured_at")
                .query(BigDecimal.class).list()).extracting(BigDecimal::intValue).containsExactly(99990, 95000);
        assertThat(count("ingest.listing_raw")).isEqualTo(2);
    }

    @Test
    void anAdListedBySummaryIsFetchedWhenTheSummaryChangesAndWhenTheCopyIsAWeekOld() {
        source.put(Fixtures.ninetyNine("apartment.json"));
        source.summaries.put("100000001", "2 rooms, 99990 EUR");
        crawls.crawl(source);
        assertThat(source.fetches).isEqualTo(1);

        // An edit the summary does not show waits for the weekly read...
        clock.advance(Duration.ofHours(4));
        source.replace("100000001", "\"value\": 99990", "\"value\": 95000");
        CrawlStats unchanged = crawls.crawl(source).orElseThrow();
        assertThat(source.fetches).isEqualTo(1);
        assertThat(unchanged.seen()).isEqualTo(1);
        assertThat(priceAmount()).isEqualTo(99990);

        // ...a changed summary is read at once...
        clock.advance(Duration.ofHours(4));
        source.summaries.put("100000001", "2 rooms, 95000 EUR");
        crawls.crawl(source);
        assertThat(source.fetches).isEqualTo(2);
        assertThat(priceAmount()).isEqualTo(95000);

        // ...and a copy over a week old is read again even when the summary is the same.
        source.replace("100000001", "\"value\": 95000", "\"value\": 90000");
        clock.advance(source.detailMaxAge());
        crawls.crawl(source);
        assertThat(source.fetches).isEqualTo(3);
        assertThat(priceAmount()).isEqualTo(90000);
        assertThat(admin.sql("select price_amount from ingest.listing_snapshot order by captured_at")
                .query(BigDecimal.class).list()).extracting(BigDecimal::intValue).containsExactly(99990, 95000, 90000);
    }

    private int priceAmount() {
        return admin.sql("select price_amount from ingest.listing").query(BigDecimal.class).single().intValue();
    }

    @Test
    void eachPhotoIsDownloadedOnceStoredOnceAndAnnouncedOnce(@TempDir Path photosDir) {
        source.put(Fixtures.ninetyNine("apartment.json")); // photos ...0a.jpg and ...0b.jpg
        crawls.crawl(source);
        assertThat(admin.sql("select photo_key from media.listing_image order by position").query(String.class).list())
                .containsExactly("0000000000000000000000000000000a.jpg", "0000000000000000000000000000000b.jpg");

        // Both photos of the ad are the same picture: two downloads, one image, one job.
        Map<String, byte[]> served = new java.util.HashMap<>();
        served.put(NINE_NINE_PHOTOS + "0000000000000000000000000000000a.jpg", PhotoDownloaderTestImages.jpeg(1));
        served.put(NINE_NINE_PHOTOS + "0000000000000000000000000000000b.jpg", PhotoDownloaderTestImages.jpeg(1));
        List<String> asked = new ArrayList<>();
        PhotoDownloader downloader = downloader(photosDir, served, asked);
        PhotoDownloader.Stats first = downloader.run(Duration.ofMinutes(5));
        assertThat(first).isEqualTo(new PhotoDownloader.Stats(2, 1, 0));
        assertThat(count("media.image")).isEqualTo(1);
        assertThat(admin.sql("select count(*) from ops.job where kind = 'image.fingerprint'").query(Integer.class).single())
                .isEqualTo(1);

        // Another ad showing the same photo of the same site is not downloaded again.
        source.put(copy("apartment.json", "100000301"));
        crawls.crawl(source);
        assertThat(downloader.run(Duration.ofMinutes(5))).isEqualTo(new PhotoDownloader.Stats(0, 0, 0));
        assertThat(asked).hasSize(2);
    }

    @Test
    void aPhotoGoneAtTheSourceIsNotAskedForAgain_otherFailuresAreRetriedLater(@TempDir Path photosDir) {
        source.put(Fixtures.ninetyNine("apartment.json"));
        crawls.crawl(source);
        List<String> asked = new ArrayList<>();
        PhotoDownloader downloader = new PhotoDownloader(new PhotoRepository(JdbcClient.create(scraper)),
                new FilePhotoStore(photosDir), new TransactionTemplate(new DataSourceTransactionManager(scraper)),
                host -> url -> {
                    asked.add(url);
                    throw url.endsWith("a.jpg") ? new HttpStatusException(404, java.net.URI.create(url))
                            : new HttpStatusException(403, java.net.URI.create(url)); // an expired signature
                }, Clock.systemUTC());
        assertThat(downloader.run(Duration.ofMinutes(5))).isEqualTo(new PhotoDownloader.Stats(0, 0, 2));
        assertThat(admin.sql("select photo_key || ':' || attempts from media.source_photo order by photo_key")
                .query(String.class).list())
                .containsExactly("0000000000000000000000000000000a.jpg:" + PhotoRepository.MAX_ATTEMPTS,
                        "0000000000000000000000000000000b.jpg:1");
        // Neither is asked for again at once: the gone one never, the other after a pause.
        assertThat(downloader.run(Duration.ofMinutes(5))).isEqualTo(new PhotoDownloader.Stats(0, 0, 0));
        assertThat(asked).hasSize(2);
    }

    static final String NINE_NINE_PHOTOS = "https://i.simpalsmedia.com/999.md/BoardImages/900x900/";

    private PhotoDownloader downloader(Path dir, Map<String, byte[]> served, List<String> asked) {
        return new PhotoDownloader(new PhotoRepository(JdbcClient.create(scraper)), new FilePhotoStore(dir),
                new TransactionTemplate(new DataSourceTransactionManager(scraper)),
                host -> url -> {
                    asked.add(url);
                    return served.get(url);
                }, Clock.systemUTC());
    }

    @Test
    void adsThatDisappearAreCheckedThenMarkedGone_andCanComeBack() {
        source.put(Fixtures.ninetyNine("apartment.json"));
        source.put(Fixtures.ninetyNine("house.json"));
        crawls.crawl(source);
        clock.advance(Duration.ofHours(4));

        // Missing from the listing but still online: fetched again, stays active.
        source.hideFromListing("100000002");
        CrawlStats missed = crawls.crawl(source).orElseThrow();
        assertThat(missed.gone()).isZero();
        assertThat(status("100000002")).isEqualTo("active");

        // Removed from the site: gone, with a snapshot.
        clock.advance(Duration.ofHours(4));
        source.remove("100000002");
        CrawlStats removed = crawls.crawl(source).orElseThrow();
        assertThat(removed.gone()).isEqualTo(1);
        assertThat(status("100000002")).isEqualTo("gone");
        assertThat(admin.sql("""
                        select s.status from ingest.listing_snapshot s join ingest.listing l on l.id = s.listing_id
                        where l.external_id = '100000002' order by s.captured_at desc limit 1
                        """).query(String.class).single()).isEqualTo("gone");

        // Listed again later.
        clock.advance(Duration.ofHours(4));
        source.put(Fixtures.ninetyNine("house.json"));
        crawls.crawl(source);
        assertThat(status("100000002")).isEqualTo("active");
        assertThat(admin.sql("select gone_at from ingest.listing where external_id = '100000002'")
                .query(java.sql.Timestamp.class).optional()).isEmpty();
    }

    @Test
    void aShortPassMarksNothingGone() {
        for (int i = 0; i < 10; i++) {
            source.put(copy("apartment.json", "10000010" + i));
        }
        crawls.crawl(source);
        clock.advance(Duration.ofHours(4));
        for (int i = 0; i < 8; i++) {
            source.remove("10000010" + i); // 2 of 10 seen: below the 50 % this test allows
        }

        crawls.crawl(source);

        assertThat(admin.sql("select count(*) from ingest.listing where status = 'gone'").query(Integer.class).single()).isZero();
        assertThat(admin.sql("select status from ingest.crawl_run order by id desc limit 1").query(String.class).single())
                .isEqualTo("partial");
    }

    @Test
    void renormalizeUsesTheStoredRawContent() {
        source.put(Fixtures.ninetyNine("apartment.json"));
        crawls.crawl(source);
        admin.sql("update ingest.listing set street_name = 'wrong', normalizer_version = 0").update();

        int done = renormalize.renormalize(source, false);

        assertThat(done).isEqualTo(1);
        assertThat(admin.sql("select street_name from ingest.listing").query(String.class).single()).isEqualTo("Ceucari");
        assertThat(admin.sql("select normalizer_version from ingest.listing").query(Integer.class).single())
                .isEqualTo(ListingNormalizer.VERSION);
        assertThat(count("ingest.listing_snapshot")).isEqualTo(1);
    }

    @Test
    void onlyOneRunPerSourceAtATime() {
        admin.sql("insert into ingest.crawl_run (source_id) values (1)").update();
        assertThat(crawls.crawl(source)).isEmpty();
    }

    @Test
    void stoppingTheServiceEndsTheRunSoTheNextCanStart() throws Exception {
        java.util.concurrent.CountDownLatch firstStored = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch stopRequested = new java.util.concurrent.CountDownLatch(1);
        FakeSource slow = new FakeSource() {
            @Override
            public int crawl(Consumer<Listed> sink) {
                sink.accept(Listed.complete(copy("apartment.json", "100000201")));
                firstStored.countDown();
                try {
                    stopRequested.await();
                } catch (InterruptedException e) {
                    throw new IllegalStateException(e);
                }
                sink.accept(Listed.complete(copy("apartment.json", "100000202")));
                return 2;
            }
        };
        Thread crawl = Thread.ofVirtual().start(() -> crawls.crawl(slow));
        firstStored.await();
        Thread stop = Thread.ofVirtual().start(crawls::close);
        Thread.sleep(200);
        stopRequested.countDown();
        stop.join();
        crawl.join();

        assertThat(admin.sql("select status from ingest.crawl_run").query(String.class).single()).isEqualTo("partial");
        assertThat(count("ingest.listing")).isEqualTo(1);
    }

    @Test
    void aPinSharedByAdsOnManyStreetsIsADefaultAndNotUsed() {
        // 999.md's default position in central Chișinău, kept by sellers who did not move the map.
        String[] streets = {"str. Ismail", "bd. Dacia", "str. Columna", "str. Alba Iulia", "bd. Moscova", "str. Grenoble"};
        for (int i = 0; i < streets.length; i++) {
            String body = new String(copy("apartment.json", "10000030" + i).body(), StandardCharsets.UTF_8)
                    .replace("\"lat\": 47.06786, \"lon\": 28.84016", "\"lat\": 47.02754, \"lon\": 28.83165")
                    .replace("ул. Чеукарь", streets[i]);
            source.put(new RawAd("10000030" + i, body.getBytes(StandardCharsets.UTF_8)));
        }
        source.put(Fixtures.ninetyNine("apartment.json")); // its own pin, on Ceucari

        crawls.crawl(source);
        assertThat(count("ingest.listing where pin is not null")).isEqualTo(7);
        assertThat(defaultPins.size()).isZero(); // not known yet: found after the pass

        pinReview.review(source);

        assertThat(defaultPins.size()).isEqualTo(1);
        assertThat(count("ingest.listing where location_source = 'pin'")).isEqualTo(1);
        assertThat(admin.sql("select st_y(pin::geometry) from ingest.listing where external_id = '100000300'")
                .query(Double.class).single()).isEqualTo(47.02754); // the seller's pin is still kept
        assertThat(count("ingest.listing where issues @> '[{\"reason\": \"default_position\"}]'")).isEqualTo(6);
    }

    static RawAd copy(String fixture, String id) {
        RawAd raw = Fixtures.ninetyNine(fixture);
        String body = new String(raw.body(), StandardCharsets.UTF_8).replaceFirst("\"id\": \"\\d+\"", "\"id\": \"" + id + "\"");
        return new RawAd(id, body.getBytes(StandardCharsets.UTF_8));
    }

    String status(String externalId) {
        return admin.sql("select status from ingest.listing where external_id = ?").param(externalId)
                .query(String.class).single();
    }

    int count(String table) {
        return admin.sql("select count(*) from " + table).query(Integer.class).single();
    }

    /** A 999.md-shaped source served from memory. */
    static class FakeSource implements ListingSource {

        private final NineNineSource parser = new NineNineSource(null, Fixtures.JSON, 0);
        private final Map<String, RawAd> online = new LinkedHashMap<>();
        private final List<String> hidden = new ArrayList<>();
        /** Ads listed only by this summary, as some sites list them. */
        final Map<String, String> summaries = new LinkedHashMap<>();
        int fetches;

        void put(RawAd ad) {
            online.put(ad.externalId(), ad);
            hidden.remove(ad.externalId());
        }

        void remove(String id) {
            online.remove(id);
            hidden.remove(id);
        }

        void hideFromListing(String id) {
            hidden.add(id);
        }

        void replace(String id, String from, String to) {
            RawAd ad = online.get(id);
            String body = new String(ad.body(), StandardCharsets.UTF_8);
            assertThat(body).contains(from);
            online.put(id, new RawAd(id, body.replace(from, to).getBytes(StandardCharsets.UTF_8)));
        }

        @Override
        public String code() {
            return "999md";
        }

        @Override
        public int crawl(Consumer<Listed> sink) {
            online.values().stream().filter(a -> !hidden.contains(a.externalId()))
                    .map(a -> summaries.containsKey(a.externalId())
                            ? Listed.summary(a.externalId(), summaries.get(a.externalId()).getBytes(StandardCharsets.UTF_8))
                            : Listed.complete(a))
                    .forEach(sink);
            return online.size();
        }

        @Override
        public Optional<RawAd> fetch(String externalId) {
            fetches++;
            return Optional.ofNullable(online.get(externalId));
        }

        @Override
        public SourceAd parse(RawAd raw) {
            return parser.parse(raw);
        }

        @Override
        public byte[] stableContent(RawAd raw) {
            return parser.stableContent(raw);
        }
    }

    static final class MutableClock extends Clock {

        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return Clock.fixed(now, zone);
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
