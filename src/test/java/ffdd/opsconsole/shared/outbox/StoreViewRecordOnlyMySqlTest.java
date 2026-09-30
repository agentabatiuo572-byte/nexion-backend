package ffdd.opsconsole.shared.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.bi.domain.B3FunnelAnalytics;
import ffdd.opsconsole.bi.domain.L1KpiAnalytics;
import ffdd.opsconsole.bi.mapper.BiReportMapper;
import ffdd.opsconsole.platform.application.A4RuntimePolicyService;
import ffdd.opsconsole.platform.mapper.PlatformConfigItemMapper;
import ffdd.opsconsole.shared.outbox.mapper.EventOutboxMapper;
import java.sql.DriverManager;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.LocalCacheScope;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/** Actual outbox and BI SQL against owned fixtures; never the business database. */
class StoreViewRecordOnlyMySqlTest {
    private static final String PREFIX = "nexion_storeview_w02_it_";
    private static final String ENABLED = "NEXION_STORE_VIEW_OUTBOX_MYSQL_IT";
    private static final String REASON = "EVENT_RECORDED_NO_BUS_CONSUMER";

    @BeforeAll
    static void requiredSqlCannotSilentlySkip() {
        if (Boolean.getBoolean("nexion.storeview.mysql.required")) {
            assertThat(System.getenv(ENABLED)).as("required SQL must run, not skip").isEqualTo("true");
            assertThat(System.getenv("NEXION_ISOLATED_MYSQL_ENDPOINT")).isEqualTo("127.0.0.1:13306");
        }
    }

    @Test
    void rejectsBusinessPortsAndUnownedSchemasBeforeConnecting() {
        String schema = PREFIX + "a".repeat(32);
        assertThat(url("127.0.0.1:13306", schema)).contains(":13306/" + schema + "?");
        for (String endpoint : new String[]{null, "", "localhost:13306", "127.0.0.1:3306",
                "127.0.0.1:013306", "127.0.0.1:13306/other", "127.0.0.1:13306?x=y"}) {
            assertThatThrownBy(() -> url(endpoint, schema)).isInstanceOf(IllegalArgumentException.class);
        }
        for (String invalid : new String[]{null, "mysql", "nexion", schema + "`", PREFIX + "not-a-uuid"}) {
            assertThatThrownBy(() -> url("127.0.0.1:13306", invalid)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    @EnabledIfEnvironmentVariable(named = ENABLED, matches = "true")
    void seventyFiveHistoricalViewsDrainWithoutChangingFactsOrBiAttribution() throws Exception {
        withFixture(f -> {
            for (long actor = 1; actor <= 5; actor++) {
                f.event("registered-" + actor, "auth.register_completed", "PUBLISHED", 0,
                        Map.of("user_id", actor, "locale", "zh", "ref", "direct"));
            }
            f.jdbc.update("UPDATE nx_event_outbox SET event_ts=DATE_SUB(NOW(3),INTERVAL 6 DAY) WHERE event_type='auth.register_completed'");
            for (int i = 0; i < 75; i++) {
                long actor = 1 + i % 5;
                var result = f.service.publishTrustedStoreView("session-" + i, "actor-" + actor, actor,
                        "P2", 3, "2026-W39", Map.of("user_id", 999999L, "anon_id", "actor-" + actor,
                                "session_id", "session-" + i, "platform", "h5", "locale", "zh", "ref", "direct"));
                assertThat(result.sampledIn()).isTrue();
                // The companion fact represents an already-ingested owned fixture.
                f.jdbc.update("INSERT INTO nx_behavior_event_fact VALUES(?,?,?,?)",
                        result.eventId(), "client-" + i, "actor-" + actor, "fingerprint-" + i);
            }
            f.jdbc.update("""
                    UPDATE nx_event_outbox SET created_at=DATE_SUB(NOW(),INTERVAL 5 DAY),
                      event_ts=DATE_SUB(NOW(3),INTERVAL 5 DAY),
                      payload=JSON_SET(payload,'$.ts',CAST(UNIX_TIMESTAMP(DATE_SUB(NOW(3),INTERVAL 5 DAY))*1000 AS UNSIGNED))
                     WHERE event_type='store.viewed'
                    """);
            List<Map<String, Object>> canonicalBefore = f.canonicalFacts();
            List<Map<String, Object>> behaviorBefore = f.jdbc.queryForList("SELECT * FROM nx_behavior_event_fact ORDER BY event_id");
            List<Map<String, Object>> l1Before = f.bi.selectL1EventFacts();
            List<Map<String, Object>> b3Before = f.bi.selectB3EventFacts();
            assertThat(l1Before).hasSize(80);
            assertThat(l1Before.stream().filter(row -> "store.viewed".equals(row.get("eventName"))))
                    .hasSize(75).allSatisfy(row -> assertThat(row.get("actorId")).isIn("1", "2", "3", "4", "5"));
            assertThat(f.backlog()).isEqualTo(75);
            Object l1Kpis = L1KpiAnalytics.calculate(l1Before, "7d", null, null, null, null).get("kpis");
            Map<String, Object> b3BeforeResult = B3FunnelAnalytics.calculate(b3Before, null, null, null);
            assertThat(((Map<?, ?>) b3BeforeResult.get("auxMetrics")).get("storeViewRate")).isEqualTo(100D);

            assertThat(f.service.retireRecordOnlyPending(25)).isEqualTo(25);
            assertThat(f.backlog()).isEqualTo(50);
            assertThat(f.service.retireRecordOnlyPending(25)).isEqualTo(25);
            assertThat(f.backlog()).isEqualTo(25);
            assertThat(f.service.retireRecordOnlyPending(25)).isEqualTo(25);
            assertThat(f.backlog()).isZero();
            assertThat(f.freshService().retireRecordOnlyPending(100)).isZero();

            assertThat(f.canonicalFacts()).isEqualTo(canonicalBefore);
            assertThat(f.jdbc.queryForList("SELECT * FROM nx_behavior_event_fact ORDER BY event_id")).isEqualTo(behaviorBefore);
            assertThat(f.bi.selectL1EventFacts()).isEqualTo(l1Before);
            assertThat(f.bi.selectB3EventFacts()).isEqualTo(b3Before);
            assertThat(L1KpiAnalytics.calculate(f.bi.selectL1EventFacts(), "7d", null, null, null, null).get("kpis"))
                    .isEqualTo(l1Kpis);
            assertThat(B3FunnelAnalytics.calculate(f.bi.selectB3EventFacts(), null, null, null).get("auxMetrics"))
                    .isEqualTo(b3BeforeResult.get("auxMetrics"));
            assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM nx_event_outbox WHERE event_type='store.viewed' AND status='RECORDED' AND last_error=? AND published_at IS NULL AND next_retry_at IS NULL", Integer.class, REASON)).isEqualTo(75);
            assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM nx_event_consumer_delivery", Integer.class)).isZero();
            System.out.println("store-view SQL evidence: historical=75; batches=25,25,25; A3=75->50->25->0; BI=80 unchanged; fakeStorePublished=0; receipts=0; serviceRecreatedRetired=0");
        });
    }

    @Test
    @EnabledIfEnvironmentVariable(named = ENABLED, matches = "true")
    void onlyOldPendingOrFailedStoreViewsRetireAndConsumersKeepTheirOwnVerdicts() throws Exception {
        withFixture(f -> {
            f.event("old-pending", "store.viewed", "PENDING", 0, Map.of("user_id", 1));
            f.event("old-failed", "store.viewed", "FAILED", 0, Map.of("user_id", 1));
            f.jdbc.update("UPDATE nx_event_outbox SET created_at=DATE_SUB(NOW(),INTERVAL 16 MINUTE),retry_count=3,next_retry_at=DATE_ADD(NOW(),INTERVAL 1 HOUR)");
            for (String type : List.of("app.page_viewed", "app.element_clicked", "checkout.completed",
                    "ADMIN_USER_PROFILE_VIEWED", "leadership_pool.settlement_blocked", "store.viewed.extra")) {
                f.event(type, type, "PENDING", 0, Map.of());
                f.jdbc.update("UPDATE nx_event_outbox SET created_at=DATE_SUB(NOW(),INTERVAL 5 DAY) WHERE event_id=?", type);
            }
            f.event("recent-view", "store.viewed", "PENDING", 0, Map.of());
            f.event("recent-risk", "risk.score_updated", "PENDING", 0, Map.of());
            f.jdbc.update("UPDATE nx_event_outbox SET created_at=DATE_SUB(NOW(),INTERVAL 14 MINUTE) WHERE event_id IN ('recent-view','recent-risk')");
            for (String status : List.of("DEAD", "PUBLISHED", "RECORDED", "PENDING_BINDING")) {
                f.event(status, "store.viewed", status, 0, Map.of());
                f.jdbc.update("UPDATE nx_event_outbox SET created_at=DATE_SUB(NOW(),INTERVAL 5 DAY) WHERE event_id=?", status);
            }
            f.event("deleted", "store.viewed", "PENDING", 1, Map.of());
            f.jdbc.update("UPDATE nx_event_outbox SET created_at=DATE_SUB(NOW(),INTERVAL 5 DAY) WHERE event_id='deleted'");
            List<Map<String, Object>> untouched = f.jdbc.queryForList("SELECT * FROM nx_event_outbox WHERE event_id NOT IN ('old-pending','old-failed') ORDER BY id");
            assertThat(EventOutboxService.RECORD_ONLY_GRACE_MINUTES).isEqualTo(15);
            assertThat(f.service.retireRecordOnlyPending(100)).isEqualTo(2);
            assertThat(f.jdbc.queryForList("SELECT * FROM nx_event_outbox WHERE event_id NOT IN ('old-pending','old-failed') ORDER BY id")).isEqualTo(untouched);
            assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM nx_event_outbox WHERE event_id IN ('old-pending','old-failed') AND status='RECORDED' AND retry_count=3 AND next_retry_at IS NULL AND published_at IS NULL AND last_error=?", Integer.class, REASON)).isEqualTo(2);
            assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM nx_event_consumer_delivery", Integer.class)).isZero();
            assertThat(f.freshService().retireRecordOnlyPending(100)).isZero();
            System.out.println("store-view SQL evidence: oldPendingAndFailed=2; graceMinutes=15; recent14MinutesUnchanged=true; consumerTypesUnchanged=true; terminalAndDeletedUnchanged=true; receipts=0");
        });
    }

    @Test
    @EnabledIfEnvironmentVariable(named = ENABLED, matches = "true")
    void largeRequestsStayBoundedAtTwoHundredAndDrainInOrder() throws Exception {
        withFixture(f -> {
            for (int i = 0; i < 205; i++) f.event("batch-" + i, "store.viewed", "PENDING", 0, Map.of());
            f.jdbc.update("UPDATE nx_event_outbox SET created_at=DATE_SUB(NOW(),INTERVAL 5 DAY)");
            assertThat(f.service.retireRecordOnlyPending(1000)).isEqualTo(200);
            assertThat(f.jdbc.queryForList("SELECT event_id FROM nx_event_outbox WHERE status='PENDING' ORDER BY id", String.class))
                    .containsExactly("batch-200", "batch-201", "batch-202", "batch-203", "batch-204");
            assertThat(f.service.retireRecordOnlyPending(100)).isEqualTo(5);
            assertThat(f.freshService().retireRecordOnlyPending(100)).isZero();
            assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM nx_event_outbox WHERE status='RECORDED' AND published_at IS NULL", Integer.class)).isEqualTo(205);
            assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM nx_event_consumer_delivery", Integer.class)).isZero();
            System.out.println("store-view SQL evidence: request1000=200; remaining=5; ordered=true; secondBatch=5; repeat=0; receipts=0");
        });
    }

    private static String url(String endpoint, String schema) {
        if (!"127.0.0.1:13306".equals(endpoint)) throw new IllegalArgumentException("isolated endpoint required");
        if (schema == null || (!schema.isEmpty() && !schema.matches(PREFIX + "[a-f0-9]{32}")))
            throw new IllegalArgumentException("owned UUID schema required");
        return "jdbc:mysql://" + endpoint + "/" + schema
                + "?useSSL=false&allowPublicKeyRetrieval=true&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true";
    }

    private static void withFixture(Scenario scenario) throws Exception {
        String schema = PREFIX + UUID.randomUUID().toString().replace("-", "");
        String endpoint = System.getenv("NEXION_ISOLATED_MYSQL_ENDPOINT");
        try (var connection = DriverManager.getConnection(url(endpoint, ""), "root", "")) {
            var dataSource = new SingleConnectionDataSource(connection, true);
            var jdbc = new JdbcTemplate(dataSource);
            assertThat(jdbc.queryForObject("SELECT @@port", Integer.class)).isEqualTo(13306);
            url(endpoint, schema);
            jdbc.execute("CREATE DATABASE " + schema + " CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
            try {
                jdbc.execute("USE " + schema);
                createTables(jdbc);
                var configuration = new Configuration(new Environment("isolated-store-view", new JdbcTransactionFactory(), dataSource));
                configuration.setMapUnderscoreToCamelCase(true);
                configuration.setLocalCacheScope(LocalCacheScope.STATEMENT);
                configuration.addMapper(EventOutboxMapper.class);
                configuration.addMapper(BiReportMapper.class);
                configuration.addMapper(PlatformConfigItemMapper.class);
                try (var session = new SqlSessionFactoryBuilder().build(configuration).openSession(true)) {
                    scenario.run(new Fixture(jdbc, session.getMapper(EventOutboxMapper.class), session.getMapper(BiReportMapper.class), session.getMapper(PlatformConfigItemMapper.class)));
                }
            } finally {
                assertThat(schema).matches(PREFIX + "[a-f0-9]{32}");
                jdbc.execute("DROP DATABASE " + schema);
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name=?", Integer.class, schema)).isZero();
                System.out.println("store-view SQL cleanup: ownedSchema=" + schema + "; remaining=0; port=13306");
            }
        }
    }

    private static void createTables(JdbcTemplate jdbc) {
        jdbc.execute("""
                CREATE TABLE nx_event_outbox(id BIGINT PRIMARY KEY AUTO_INCREMENT,event_id VARCHAR(64) UNIQUE,
                  aggregate_type VARCHAR(64),aggregate_id VARCHAR(100),event_type VARCHAR(100),event_name VARCHAR(100),
                  family_key VARCHAR(64),event_ts DATETIME(3),phase VARCHAR(16),account_age_months INT,cohort VARCHAR(32),
                  is_server_authoritative TINYINT,schema_revision INT,schema_registered TINYINT,analytics_event TINYINT,payload JSON,
                  status VARCHAR(32),retry_count INT,next_retry_at DATETIME,published_at DATETIME,last_error VARCHAR(512),
                  created_at DATETIME,updated_at DATETIME,is_deleted TINYINT)
                """);
        jdbc.execute("CREATE TABLE nx_event_schema_registry(id BIGINT PRIMARY KEY,event_name VARCHAR(100),family_key VARCHAR(64),current_revision INT,is_server_authoritative TINYINT,status VARCHAR(32),is_deleted TINYINT)");
        jdbc.execute("CREATE TABLE nx_admin_event_lifecycle(event_name VARCHAR(100),lifecycle_state VARCHAR(32),is_deleted TINYINT)");
        jdbc.execute("CREATE TABLE nx_event_schema_property(id BIGINT,schema_id BIGINT,property_name VARCHAR(100),property_type VARCHAR(32),required_field TINYINT,registry_revision INT,is_deleted TINYINT)");
        jdbc.execute("CREATE TABLE nx_event_consumer_delivery(event_id VARCHAR(64),consumer_group VARCHAR(100),status VARCHAR(32))");
        jdbc.execute("CREATE TABLE nx_behavior_event_fact(event_id VARCHAR(64) PRIMARY KEY,client_event_id VARCHAR(64) UNIQUE,actor_hash VARCHAR(64),fingerprint VARCHAR(64))");
        jdbc.execute("CREATE TABLE nx_audit_log(biz_no VARCHAR(100),is_deleted TINYINT)");
        jdbc.update("INSERT INTO nx_event_schema_registry VALUES(1,'store.viewed','conversion',285,0,'ACTIVE',0)");
        jdbc.update("INSERT INTO nx_admin_event_lifecycle VALUES('store.viewed','full',0)");
    }

    @FunctionalInterface
    private interface Scenario { void run(Fixture fixture) throws Exception; }

    private static final class Fixture {
        private final JdbcTemplate jdbc;
        private final EventOutboxMapper outbox;
        private final BiReportMapper bi;
        private final PlatformConfigItemMapper health;
        private final A4RuntimePolicyService policy = mock(A4RuntimePolicyService.class);
        private final EventOutboxService service;

        private Fixture(JdbcTemplate jdbc, EventOutboxMapper outbox, BiReportMapper bi, PlatformConfigItemMapper health) {
            this.jdbc = jdbc; this.outbox = outbox; this.bi = bi; this.health = health;
            when(policy.samplingPercent("conversion", false)).thenReturn(100);
            service = freshService();
        }

        private EventOutboxService freshService() {
            return new EventOutboxService(outbox, new ObjectMapper(), new OutboxProperties(), policy);
        }

        private long backlog() { return ((Number) health.selectA3EventBacklog().get("backlog")).longValue(); }

        private List<Map<String, Object>> canonicalFacts() {
            return jdbc.queryForList("""
                    SELECT event_id,aggregate_type,aggregate_id,event_type,event_name,family_key,event_ts,phase,
                      account_age_months,cohort,is_server_authoritative,schema_revision,schema_registered,analytics_event,
                      payload,retry_count,created_at,is_deleted FROM nx_event_outbox ORDER BY id
                    """);
        }

        private void event(String id, String type, String status, int deleted, Map<String, Object> payload) throws Exception {
            outbox.insertEvent(id, "APP_BEHAVIOR", "session-fixture", type, type, "conversion", "P2", 3,
                    "2026-W39", !"store.viewed".equals(type), 285, true, true, new ObjectMapper().writeValueAsString(payload));
            jdbc.update("UPDATE nx_event_outbox SET status=?,is_deleted=? WHERE event_id=?", status, deleted, id);
        }
    }
}
