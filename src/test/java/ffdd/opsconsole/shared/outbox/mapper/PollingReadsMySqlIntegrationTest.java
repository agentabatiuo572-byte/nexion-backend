package ffdd.opsconsole.shared.outbox.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import ffdd.opsconsole.home.mapper.DevelopmentHomeSettlementMapper;
import ffdd.opsconsole.shared.outbox.EventOutboxMessage;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

/** Real SQL equivalence and migration recovery in a disposable database, never application rows. */
@EnabledIfEnvironmentVariable(named = "NEXION_TEST_DB_PASSWORD", matches = ".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PollingReadsMySqlIntegrationTest {
    private static final String MIGRATION = "scripts/migrations/20260914_polling_read_indexes.sql";
    private static final String TYPE_INDEX = "idx_outbox_canonical_type";
    private static final String NAME_INDEX = "idx_outbox_canonical_name";
    private static final String TASK_INDEX = "idx_task_development_count";
    private Connection connection;
    private JdbcTemplate jdbc;
    private String database;
    private boolean created;
    private Configuration configuration;
    private EventOutboxMapper outbox;
    private DevelopmentHomeSettlementMapper tasks;

    @BeforeAll
    void createOnlyAnOwnedDatabase() throws Exception {
        connection = DriverManager.getConnection(System.getenv().getOrDefault("NEXION_TEST_DB_URL",
                        "jdbc:mysql://127.0.0.1:3306/nexion?useUnicode=true&characterEncoding=utf8"
                                + "&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true"),
                System.getenv().getOrDefault("NEXION_TEST_DB_USERNAME", "root"),
                System.getenv("NEXION_TEST_DB_PASSWORD"));
        SingleConnectionDataSource dataSource = new SingleConnectionDataSource(connection, true);
        jdbc = new JdbcTemplate(dataSource);
        database = "nx_polling_test_" + UUID.randomUUID().toString().replace("-", "");
        assertThat(database).matches("nx_polling_test_[a-f0-9]{32}");
        jdbc.execute("CREATE DATABASE `" + database + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
        created = true;
        jdbc.execute("USE `" + database + "`");
        configuration = new Configuration(new Environment("polling-reads-test",
                new SpringManagedTransactionFactory(), dataSource));
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(EventOutboxMapper.class);
        configuration.addMapper(DevelopmentHomeSettlementMapper.class);
        SqlSessionTemplate session = new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(configuration));
        outbox = session.getMapper(EventOutboxMapper.class);
        tasks = session.getMapper(DevelopmentHomeSettlementMapper.class);
    }

    @BeforeEach
    void resetOnlyOwnedTables() {
        assertThat(database).matches("nx_polling_test_[a-f0-9]{32}");
        assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class)).isEqualTo(database);
        jdbc.execute("DROP TABLE IF EXISTS nx_event_outbox, nx_compute_task, nx_user");
        jdbc.execute("CREATE TABLE nx_user (id BIGINT PRIMARY KEY, sandbox TINYINT, is_deleted TINYINT)");
        jdbc.execute("""
                CREATE TABLE nx_compute_task (
                  id BIGINT PRIMARY KEY, user_id BIGINT, user_device_id BIGINT, task_no VARCHAR(64),
                  status VARCHAR(32), source_environment VARCHAR(24), is_deleted TINYINT)
                """);
        jdbc.execute("""
                CREATE TABLE nx_event_outbox (
                  id BIGINT PRIMARY KEY, event_id VARCHAR(96), aggregate_type VARCHAR(64), aggregate_id VARCHAR(96),
                  event_type VARCHAR(96), event_name VARCHAR(128), family_key VARCHAR(64), event_ts DATETIME(3),
                  phase VARCHAR(32), account_age_months INT, cohort VARCHAR(16), is_server_authoritative TINYINT,
                  schema_revision INT, schema_registered TINYINT, analytics_event TINYINT, payload LONGTEXT,
                  status VARCHAR(32), retry_count INT, next_retry_at DATETIME, published_at DATETIME,
                  last_error VARCHAR(255), created_at DATETIME, updated_at DATETIME, is_deleted TINYINT)
                """);
    }

    @AfterAll
    void discardOnlyOwnedDatabase() throws Exception {
        if (connection == null) return;
        try {
            if (created) {
                assertThat(database).matches("nx_polling_test_[a-f0-9]{32}");
                jdbc.execute("DROP DATABASE `" + database + "`");
            }
        } finally {
            connection.close();
        }
    }

    @Test
    void canonicalReadsPreserveOrderDeduplicationRetryAndAllScopeBoundaries() throws Exception {
        migrate();
        LocalDateTime past = LocalDateTime.now().minusDays(1);
        LocalDateTime future = LocalDateTime.now().plusDays(1);
        event(1, "ORDER.PAID", "legacy", "PENDING", null, 0);
        event(2, "legacy", "Order.Paid", "FAILED", past, 0);
        event(3, "order.paid", "ORDER.PAID", "FAILED", null, 0);
        event(4, "order.paid", null, "PENDING", future, 0);
        event(5, null, "order.paid", "PUBLISHED", past, 0);
        event(6, "order.paid", "order.paid", "DEAD", past, 0);
        event(7, "order.paid", null, "PENDING", past, 1);
        event(8, null, "order.paid", "PENDING", null, 0);
        event(9, null, null, "PENDING", null, 0);
        event(10, "unrelated", "unrelated", "PENDING", null, 0);
        for (int id = 11; id <= 80; id++) {
            event(id, id % 3 == 0 ? "order.paid" : "legacy", "order.paid", "PENDING", past, 0);
        }
        for (long cursor : List.of(0L, 1L, 2L, 7L, 20L, 79L, 100L)) {
            for (int limit : List.of(0, 1, 2, 5, 50, 100)) assertLegacy("OrDeR.PaId", cursor, limit);
        }
        assertThat(ids(outbox.listPendingByCanonicalType("order.paid", 0, 4))).containsExactly(1L, 2L, 3L, 8L);
        assertLegacy(null, 0, 50);
        assertLegacy("missing", 0, 50);
        // Retrying an old row remains visible to the scheduler's cursor-zero head pass.
        jdbc.update("UPDATE nx_event_outbox SET next_retry_at=? WHERE id=4", past);
        assertThat(ids(outbox.listPendingByCanonicalType("order.paid", 0, 4))).containsExactly(1L, 2L, 3L, 4L);
        EventOutboxMessage message = outbox.listPendingByCanonicalType("order.paid", 0, 1).get(0);
        assertThat(message.getEventId()).isEqualTo("event-1");
        assertThat(message.getPayload()).isEqualTo("{\"fixture\":1}");
        assertThat(message.getRetryCount()).isEqualTo(2);
        assertThat(message.getSchemaRegistered()).isTrue();
        assertThat(message.getServerAuthoritative()).isTrue();
        assertThat(message.getAggregateId()).isEqualTo("aggregate-1");
    }

    @Test
    void lowerComparisonAlsoPreservesCaseSensitiveColumnSemantics() throws Exception {
        jdbc.execute("ALTER TABLE nx_event_outbox MODIFY event_type VARCHAR(96) COLLATE utf8mb4_bin,"
                + " MODIFY event_name VARCHAR(128) COLLATE utf8mb4_bin");
        migrate();
        event(1, "ORDER.PAID", null, "PENDING", null, 0);
        event(2, null, "OrDeR.PaId", "FAILED", null, 0);
        event(3, "órder.paid", null, "PENDING", null, 0);
        assertLegacy("order.paid", 0, 50);
        assertThat(ids(outbox.listPendingByCanonicalType("order.paid", 0, 50))).containsExactly(1L, 2L);
    }

    @Test
    void seededPopulationMatchesLegacyAcrossAsymmetricBranchesAndCursors() throws Exception {
        migrate();
        Random random = new Random(20260914);
        String[] names = {"order.paid", "ORDER.PAID", "unrelated", "checkout.started", null};
        String[] statuses = {"PENDING", "FAILED", "PUBLISHED", "DEAD", null};
        for (int id = 1; id <= 400; id++) {
            event(id, names[random.nextInt(names.length)], names[random.nextInt(names.length)],
                    statuses[random.nextInt(statuses.length)], random.nextBoolean() ? null
                            : LocalDateTime.now().plusDays(random.nextBoolean() ? 1 : -1), random.nextInt(7) == 0 ? 1 : 0);
        }
        for (String canonical : List.of("order.paid", "checkout.started", "missing")) {
            for (long cursor : List.of(0L, 50L, 199L, 390L)) {
                for (int limit : List.of(1, 7, 50, 500)) assertLegacy(canonical, cursor, limit);
            }
        }
    }

    @Test
    void developmentCountKeepsUserDeviceEnvironmentAndPrefixIsolation() throws Exception {
        migrate();
        jdbc.update("INSERT INTO nx_user VALUES (42,0,0),(43,1,0),(44,0,1),(45,NULL,0)");
        jdbc.update("""
                INSERT INTO nx_compute_task VALUES
                (1,42,811,'DEV-TASK-1','COMPLETED','PRODUCTION',0),
                (2,42,811,'dev-task-2','completed','production',0),
                (3,42,812,'DEV-TASK-3','COMPLETED','PRODUCTION',0),
                (4,42,811,'DEV-TASK-4','RUNNING','PRODUCTION',0),
                (5,42,811,'DEV-TASK-5','COMPLETED','SANDBOX',0),
                (6,42,811,'DEV-TASK-6','COMPLETED','PRODUCTION',1),
                (7,42,811,'OTHER-7','COMPLETED','PRODUCTION',0),
                (8,43,811,'DEV-TASK-8','COMPLETED','PRODUCTION',0),
                (9,44,811,'DEV-TASK-9','COMPLETED','PRODUCTION',0),
                (10,45,811,'DEV-TASK-10','COMPLETED','PRODUCTION',0),
                (11,99,811,'DEV-TASK-11','COMPLETED','PRODUCTION',0)
                """);
        assertThat(tasks.developmentCompletedTaskCount(42L, 811L)).isEqualTo(2);
        assertThat(tasks.developmentCompletedTaskCount(42L, 812L)).isEqualTo(1);
        for (long user : List.of(43L, 44L, 45L, 99L)) {
            assertThat(tasks.developmentCompletedTaskCount(user, 811L)).isZero();
        }
        String plan = explain(DevelopmentHomeSettlementMapper.class, "developmentCompletedTaskCount",
                Map.of("userId", 42L, "userDeviceId", 811L));
        assertThat(plan).contains(TASK_INDEX).contains("\"using_index\": true");
    }

    @Test
    void wideTaskNumbersAndCompetingDeviceIndexKeepTheCountCovered() throws Exception {
        jdbc.execute("ALTER TABLE nx_compute_task MODIFY task_no VARCHAR(512), ADD payload VARCHAR(1024)");
        jdbc.execute("CREATE INDEX idx_task_device_latest_client ON nx_compute_task(user_device_id,is_deleted,id)");
        migrate();
        jdbc.update("INSERT INTO nx_user VALUES (42,0,0)");
        jdbc.execute("SET SESSION cte_max_recursion_depth=4000");
        jdbc.execute("""
                INSERT INTO nx_compute_task(id,user_id,user_device_id,task_no,status,source_environment,is_deleted,payload)
                WITH RECURSIVE ids(n) AS (SELECT 1 UNION ALL SELECT n+1 FROM ids WHERE n<3000)
                SELECT n,42,811,CONCAT(IF(n<=2500,'DEV-TASK-','OTHER-'),RPAD(n,220,'x')),
                       'COMPLETED','PRODUCTION',0,REPEAT('x',1024) FROM ids
                """);
        jdbc.execute("ANALYZE TABLE nx_compute_task");
        long legacy = jdbc.queryForObject("""
                SELECT COUNT(*) FROM nx_compute_task t
                JOIN nx_user u ON u.id=t.user_id AND u.sandbox=0 AND u.is_deleted=0
                WHERE t.user_id=42 AND t.user_device_id=811 AND t.task_no LIKE 'DEV-TASK-%'
                AND t.status='COMPLETED' AND t.source_environment='PRODUCTION' AND t.is_deleted=0
                """, Long.class);
        assertThat(legacy).isEqualTo(2500);
        assertThat(tasks.developmentCompletedTaskCount(42L, 811L)).isEqualTo(legacy);
        String plan = explain(DevelopmentHomeSettlementMapper.class, "developmentCompletedTaskCount",
                Map.of("userId", 42L, "userDeviceId", 811L));
        assertThat(plan).contains("\"key\": \"" + TASK_INDEX + "\"").contains("\"using_index\": true");
    }

    @Test
    void selectiveCanonicalPlansUseBothGeneratedColumnIndexes() throws Exception {
        migrate();
        jdbc.execute("SET SESSION cte_max_recursion_depth=4000");
        jdbc.execute("""
                INSERT INTO nx_event_outbox(id,event_type,event_name,status,is_deleted,payload)
                WITH RECURSIVE ids(n) AS (SELECT 1 UNION ALL SELECT n+1 FROM ids WHERE n<3000)
                SELECT n,'unrelated','unrelated','PENDING',0,REPEAT('x',200) FROM ids
                """);
        event(3001, "order.paid", null, "PENDING", null, 0);
        event(3002, null, "ORDER.PAID", "PENDING", null, 0);
        jdbc.execute("ANALYZE TABLE nx_event_outbox");
        String plan = explain(EventOutboxMapper.class, "listPendingByCanonicalType",
                Map.of("canonicalType", "order.paid", "afterId", 0L, "limit", 50));
        assertThat(plan).contains("\"key\": \"" + TYPE_INDEX + "\"")
                .contains("\"key\": \"" + NAME_INDEX + "\"");
        assertLegacy("order.paid", 0, 50);
    }

    @Test
    void migrationIsIdempotentAndResumesAfterPartialDdl() throws Exception {
        int originalTimeout = jdbc.queryForObject("SELECT @@session.lock_wait_timeout", Integer.class);
        migrate();
        migrate();
        jdbc.execute("ALTER TABLE nx_event_outbox DROP INDEX " + NAME_INDEX);
        jdbc.execute("ALTER TABLE nx_event_outbox DROP COLUMN canonical_event_name");
        migrate();
        assertThat(jdbc.queryForObject("SELECT @@session.lock_wait_timeout", Integer.class)).isEqualTo(originalTimeout);
        assertThat(jdbc.queryForObject("SELECT IS_FREE_LOCK(@polling_read_lock_name)", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE()"
                + " AND index_name IN ('" + TASK_INDEX + "','" + TYPE_INDEX + "','" + NAME_INDEX + "')", Integer.class))
                .isEqualTo(16);
    }

    @Test
    void wrongSameNameIndexFailsBeforeAnyOtherIndexIsCreatedAndCanRecover() throws Exception {
        for (String wrongKey : List.of("(event_name)", "((UPPER(event_type)),is_deleted,status,id,next_retry_at)")) {
            jdbc.execute("CREATE INDEX " + TYPE_INDEX + " ON nx_event_outbox " + wrongKey);
            assertThatThrownBy(this::migrate).hasStackTraceContaining("POLLING_READ_INDEX_SHAPE_INVALID_" + TYPE_INDEX);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE()"
                    + " AND index_name IN ('" + TASK_INDEX + "','" + NAME_INDEX + "')", Integer.class)).isZero();
            jdbc.execute("SELECT RELEASE_LOCK(@polling_read_lock_name)");
            jdbc.execute("ALTER TABLE nx_event_outbox DROP INDEX " + TYPE_INDEX);
        }
        migrate();
        assertLegacy("missing", 0, 50);
    }

    @Test
    void incompatibleGeneratedColumnFailsBeforeDdlAndRecovers() throws Exception {
        for (String definition : List.of("VARCHAR(96)",
                "VARCHAR(96) AS (UPPER(event_type)) VIRTUAL",
                "VARCHAR(96) COLLATE utf8mb4_bin AS (LOWER(event_type)) VIRTUAL",
                "VARCHAR(80) AS (LOWER(event_type)) VIRTUAL")) {
            jdbc.execute("ALTER TABLE nx_event_outbox ADD COLUMN canonical_event_type " + definition);
            assertThatThrownBy(this::migrate)
                    .hasStackTraceContaining("POLLING_READ_COLUMN_SHAPE_INVALID_canonical_event_type");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE()"
                    + " AND index_name IN ('" + TASK_INDEX + "','" + TYPE_INDEX + "','" + NAME_INDEX + "')", Integer.class))
                    .isZero();
            jdbc.execute("SELECT RELEASE_LOCK(@polling_read_lock_name)");
            jdbc.execute("ALTER TABLE nx_event_outbox DROP COLUMN canonical_event_type");
        }
        migrate();
    }

    /**
     * Executes the record-only retirement against real MySQL. The render test in
     * {@code EventOutboxRetirementSqlTest} only proves the tags are parsed; it
     * cannot prove MySQL accepts the statement, and the coverage that existed
     * when the backlog was reported mocked the service so the SQL never ran at
     * all. This runs the exact mapper method the scheduler calls.
     */
    @Test
    void recordOnlyRetirementActuallyRetiresEligibleRowsInMysql() {
        String covered = "JANUS_STRATEGY_PUBLISH";
        String unlisted = "ORDER.PAID";
        event(1, covered, null, "PENDING", null, 0);
        event(2, covered, null, "FAILED", null, 0);
        event(3, covered, null, "PENDING", null, 0);
        event(4, unlisted, null, "PENDING", null, 0);
        event(5, covered, null, "PUBLISHED", null, 0);
        event(6, covered, null, "PENDING", null, 1);
        // The helper writes created_at=NOW(), which is inside the 15-minute grace
        // window. Backdate every candidate except id=3, which must stay too recent.
        jdbc.update("UPDATE nx_event_outbox SET created_at = DATE_SUB(NOW(), INTERVAL 30 MINUTE) WHERE id <> 3");

        int retired = outbox.retireRecordOnlyPending(List.of(covered), 15, 100,
                "EVENT_RECORDED_NO_BUS_CONSUMER", "RECORDED", "PENDING", "FAILED");

        assertThat(retired).isEqualTo(2);
        assertThat(jdbc.queryForList("SELECT id FROM nx_event_outbox WHERE status='RECORDED' ORDER BY id", Long.class))
                .containsExactly(1L, 2L);
        assertThat(jdbc.queryForObject("SELECT last_error FROM nx_event_outbox WHERE id=1", String.class))
                .isEqualTo("EVENT_RECORDED_NO_BUS_CONSUMER");
        assertThat(jdbc.queryForObject("SELECT next_retry_at FROM nx_event_outbox WHERE id=1", LocalDateTime.class))
                .isNull();
        // The unlisted, the too-recent and the already-published rows are untouched.
        assertThat(jdbc.queryForList("SELECT id FROM nx_event_outbox WHERE status IN ('PENDING','PUBLISHED') ORDER BY id", Long.class))
                .containsExactly(3L, 4L, 5L, 6L);
    }

    private void migrate() throws Exception {
        ScriptUtils.executeSqlScript(connection, new FileSystemResource(MIGRATION));
    }

    private void event(long id, String type, String name, String status, LocalDateTime retry, int deleted) {
        jdbc.update("""
                INSERT INTO nx_event_outbox(id,event_id,aggregate_type,aggregate_id,event_type,event_name,
                  family_key,event_ts,phase,account_age_months,cohort,is_server_authoritative,schema_revision,
                  schema_registered,analytics_event,payload,status,retry_count,next_retry_at,created_at,updated_at,is_deleted)
                VALUES (?,?,'order',?, ?,?,'order',NOW(3),'server',2,'test',1,3,1,1,?,?,2,?,NOW(),NOW(),?)
                """, id, "event-" + id, "aggregate-" + id, type, name, "{\"fixture\":" + id + "}", status, retry, deleted);
    }

    private void assertLegacy(String canonical, long afterId, int limit) {
        List<Long> expected = jdbc.queryForList("""
                SELECT id FROM nx_event_outbox WHERE is_deleted=0
                  AND (LOWER(event_type)=LOWER(?) OR LOWER(event_name)=LOWER(?))
                  AND status IN ('PENDING','FAILED') AND (next_retry_at IS NULL OR next_retry_at<=NOW())
                  AND id>? ORDER BY id ASC LIMIT ?
                """, Long.class, canonical, canonical, afterId, limit);
        assertThat(ids(outbox.listPendingByCanonicalType(canonical, afterId, limit))).containsExactlyElementsOf(expected);
    }

    private List<Long> ids(List<EventOutboxMessage> messages) {
        return messages.stream().map(EventOutboxMessage::getId).toList();
    }

    private String explain(Class<?> mapper, String method, Map<String, Object> parameters) {
        BoundSql bound = configuration.getMappedStatement(mapper.getName() + "." + method)
                .getBoundSql(new HashMap<>(parameters));
        Object[] values = bound.getParameterMappings().stream().map(p -> parameters.get(p.getProperty())).toArray();
        return jdbc.queryForObject("EXPLAIN FORMAT=JSON " + bound.getSql(), String.class, values);
    }
}
