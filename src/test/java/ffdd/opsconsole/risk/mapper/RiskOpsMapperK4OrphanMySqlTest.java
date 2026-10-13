package ffdd.opsconsole.risk.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.risk.application.K4ScoreBackfillTransactionExecutor;
import ffdd.opsconsole.risk.infrastructure.MybatisRiskOpsRepository;
import ffdd.opsconsole.shared.outbox.CanonicalEventSchemaMySqlFixture;
import ffdd.opsconsole.shared.seed.OpsReadTimeSeedPolicy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/** Executes production mapper SQL against synthetic rows in the existing fixture's private database. */
@EnabledIfEnvironmentVariable(named = "RISK_K4_ORPHAN_RUNTIME", matches = "1")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RiskOpsMapperK4OrphanMySqlTest {
    private static final List<String> TABLES = List.of("nx_user", "nx_admin_risk_score_user",
            "nx_admin_risk_score_override", "nx_admin_risk_score_contribution");
    private static final List<String> CLEANUPS = List.of("retireOrphanScoreUsers",
            "deactivateOrphanScoreOverrides", "retireOrphanScoreContributions");
    private static final long BUSINESS_TIME = 1791504000L;
    // Independent pre-change oracle: preserve the old matcher, qualification and changed columns.
    private static final List<String> LEGACY = List.of(
            """
            UPDATE nx_admin_risk_score_user s LEFT JOIN nx_user u
              ON CONCAT('U',LPAD(u.id,GREATEST(8,CHAR_LENGTH(CAST(u.id AS CHAR))),'0'))=s.user_no
             AND u.is_deleted=0 AND COALESCE(u.sandbox,0)=0
               SET s.is_deleted=1,s.updated_at=NOW()
             WHERE s.is_deleted=0 AND u.id IS NULL
            """,
            """
            UPDATE nx_admin_risk_score_override o LEFT JOIN nx_user u
              ON CONCAT('U',LPAD(u.id,GREATEST(8,CHAR_LENGTH(CAST(u.id AS CHAR))),'0'))=o.user_no
             AND u.is_deleted=0 AND COALESCE(u.sandbox,0)=0
               SET o.active=0,o.updated_at=NOW()
             WHERE o.active=1 AND o.is_deleted=0 AND u.id IS NULL
            """,
            """
            UPDATE nx_admin_risk_score_contribution c LEFT JOIN nx_user u
              ON CONCAT('U',LPAD(u.id,GREATEST(8,CHAR_LENGTH(CAST(u.id AS CHAR))),'0'))=c.user_no
             AND u.is_deleted=0 AND COALESCE(u.sandbox,0)=0
               SET c.is_deleted=1 WHERE c.is_deleted=0 AND u.id IS NULL
            """);
    private static final String LEGACY_ENSURE = """
            INSERT INTO nx_admin_risk_score_user
              (user_no,model_score,model_version,row_version,as_of,updated_text,is_deleted)
            SELECT CONCAT('U',LPAD(u.id,GREATEST(8,CHAR_LENGTH(CAST(u.id AS CHAR))),'0')),0,'pending',0,NOW(),'待首次评分',0
              FROM nx_user u WHERE u.is_deleted=0 AND COALESCE(u.sandbox,0)=0
            ON DUPLICATE KEY UPDATE is_deleted=0,updated_at=NOW()
            """;
    private final ObjectMapper json = new ObjectMapper();
    private final List<Map<String, Object>> stages = new ArrayList<>();
    private CanonicalEventSchemaMySqlFixture fixture;
    private JdbcTemplate jdbc;
    private RiskOpsMapper mapper;
    private K4ScoreBackfillTransactionExecutor transaction;
    private Path evidence;
    private String database;

    @BeforeAll
    void createOnlyAnOwnedFixture() throws Exception {
        String root = System.getenv("RISK_K4_ORPHAN_EVIDENCE_ROOT");
        assertThat(root).as("explicit fresh runtime evidence directory").isNotBlank();
        evidence = Path.of(root).toAbsolutePath().normalize().resolve("mapper-" + UUID.randomUUID());
        Files.createDirectories(evidence.getParent());
        Files.createDirectory(evidence);
        fixture = new CanonicalEventSchemaMySqlFixture(TABLES.toArray(String[]::new));
        jdbc = fixture.jdbc();
        database = jdbc.queryForObject("SELECT DATABASE()", String.class);
        assertThat(database).matches("nx_event_closure_test_[a-f0-9]{32}");
        mapper = fixture.mapper(RiskOpsMapper.class);
        transaction = new K4ScoreBackfillTransactionExecutor(new DataSourceTransactionManager(jdbc.getDataSource()));
        for (String table : TABLES) jdbc.execute("CREATE TABLE " + table + "_legacy LIKE " + table);
        writeJson("fixture.json", Map.of("database", database, "sourceRowsCopied", 0,
                "mode", "full",
                "mysqlVersion", jdbc.queryForObject("SELECT VERSION()", String.class),
                "createdAt", Instant.now().toString()));
    }

    @AfterAll
    void discardOnlyOwnedFixture() throws Exception {
        try {
            if (fixture != null) fixture.close();
        } finally {
            if (evidence != null) writeJson("stages.json", stages);
        }
    }

    @Test
    void fullSynchronizationMatchesLegacyForAllFieldsUnicodeQualificationAndHistoricalTimes() throws Exception {
        long started = System.nanoTime();
        boolean passed = false;
        try {
            reset();
            String sandboxNullable = jdbc.queryForObject("SELECT IS_NULLABLE FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=? AND TABLE_NAME='nx_user' AND COLUMN_NAME='sandbox'", String.class, database);
            assertThat(sandboxNullable).isIn("YES", "NO");
            writeJson("sandbox-boundary.json", Map.of("isNullable", sandboxNullable,
                    "nullSandboxSample", sandboxNullable.equals("YES") ? "included" : "not-tested-schema-not-null",
                    "ordinaryAccountId", 44));
            transaction.execute(() -> {
                fixedSession(jdbc);
                for (long id : new long[] {0, 1, 12, 9999999, 10000000, 100000000, -1, -12,
                        -9999999, -10000000, Long.MAX_VALUE, Long.MIN_VALUE, 41, 42, 43, 44}) {
                    user(jdbc, "", id, id == 42 ? 1 : 0, id == 43 ? 1 : 0);
                }
                if (sandboxNullable.equals("YES")) jdbc.update("UPDATE nx_user SET sandbox=NULL WHERE id=44");
                List<String> uniqueNumbers = List.of("U００００００００", "u00000001", "Ū00000012",
                        "U09999999", "U10000000", "Ｕ１００００００００", "U000000-1", "U00000-12",
                        "U-9999999", "U-10000000", "U9223372036854775807", "U-9223372036854775808",
                        "U00000042", "U00000043", "U00000044", "U000000001", "U1", "bad-format",
                        "U9223372036854775808", "U-9223372036854775809");
                for (int i = 0; i < uniqueNumbers.size(); i++) riskRows(uniqueNumbers.get(i), i, i % 7 == 0 ? 1 : 0);
                for (String number : List.of("U00000001", "u00000001", "Ū00000001", "U\u030400000001",
                        "Ｕ00000001", "U０００００００１", "U0000000１", "U00000001 ", "U00000001\t",
                        " U00000001", "U00000001\u200b", "U00000001x", "U0000000A", "U-0000001",
                        "U000000−1", "U+0000001", "", "U00000041", "U00000042", "U00000043")) {
                    contribution(number);
                }
                jdbc.update("UPDATE nx_admin_risk_score_override SET active=0 WHERE id=2");
                jdbc.update("UPDATE nx_admin_risk_score_contribution SET is_deleted=1 WHERE MOD(id,17)=0");
                for (String table : TABLES) {
                    jdbc.update("UPDATE " + table + " SET created_at='2001-02-03 04:05:06'");
                    if (!table.endsWith("contribution")) jdbc.update("UPDATE " + table + " SET updated_at='2002-03-04 05:06:07'");
                }
                cloneRows();
                return null;
            });
            writeDumps("static-before", "");
            transaction.execute(() -> {
                fixedSession(jdbc);
                jdbc.update(legacy(LEGACY.get(0)));
                jdbc.update(legacy(LEGACY_ENSURE));
                jdbc.update(legacy(LEGACY.get(1)));
                jdbc.update(legacy(LEGACY.get(2)));
                return null;
            });
            transaction.execute(() -> {
                fixedSession(jdbc);
                assertThat(jdbc.queryForObject("SELECT @@transaction_isolation", String.class)).isEqualTo("READ-COMMITTED");
                new MybatisRiskOpsRepository(mapper, OpsReadTimeSeedPolicy.disabledForDirectConstruction())
                        .synchronizeScoringUsers();
                return null;
            });
            assertAllFields("static");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_admin_risk_score_contribution WHERE user_no IN ('bad-format','U00000042','U00000043') AND is_deleted=0", Long.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT is_deleted FROM nx_admin_risk_score_contribution WHERE BINARY user_no=BINARY ?", Integer.class, "Ū00000001")).isZero();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_admin_risk_score_user WHERE user_no='U00000041' AND is_deleted=0", Long.class)).isEqualTo(1);
            passed = true;
        } finally {
            recordStage("all-four-statements-all-fields", started, passed);
        }
    }

    @Test
    void allThreeCleanupsMatchLegacyAfterConcurrentQualificationAndNewAccountCommits() throws Exception {
        long started = System.nanoTime();
        boolean passed = false;
        try {
            for (int cleanup = 0; cleanup < CLEANUPS.size(); cleanup++) {
                for (String scenario : List.of("deleted", "sandbox", "forward", "backward", "auto")) {
                    reset();
                    transaction.execute(() -> {
                        fixedSession(jdbc);
                        user(jdbc, "", 1L, 0, 0);
                        user(jdbc, "", 2L, 0, 0);
                        riskRows("U00000001", 0, 0);
                        riskRows("U00000002", 1, 0);
                        riskRows(scenario.equals("backward") ? "U00000000" : scenario.equals("auto") ? "U00000003" : "U00000099", 2, 0);
                        if (scenario.equals("auto")) riskRows("U00000004", 3, 0);
                        cloneRows();
                        return null;
                    });
                    race(cleanup, scenario, true);
                    race(cleanup, scenario, false);
                    assertAllFields(CLEANUPS.get(cleanup) + "-" + scenario);
                }
            }
            passed = true;
        } finally {
            recordStage("three-cleanups-five-concurrent-schedules", started, passed);
        }
    }

    private void race(int cleanup, String scenario, boolean old) throws Exception {
        String suffix = old ? "_legacy" : "";
        String label = CLEANUPS.get(cleanup) + "-" + scenario + (old ? "-old" : "-mapper");
        var worker = Executors.newSingleThreadExecutor();
        try (Connection account = jdbc.getDataSource().getConnection()) {
            account.setAutoCommit(false);
            account.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            var accountJdbc = new JdbcTemplate(new SingleConnectionDataSource(account, true));
            fixedSession(accountJdbc);
            long accountId = accountJdbc.queryForObject("SELECT CONNECTION_ID()", Long.class);
            if (scenario.equals("auto")) {
                autoUser(accountJdbc, suffix, "low");
                assertThat(accountJdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class)).isEqualTo(3);
                transaction.execute(() -> {
                    fixedSession(jdbc);
                    autoUser(jdbc, suffix, "high");
                    assertThat(jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class)).isEqualTo(4);
                    return null;
                });
            } else {
                String column = scenario.equals("deleted") ? "is_deleted" : scenario.equals("sandbox") ? "sandbox" : "nickname";
                accountJdbc.update("UPDATE nx_user" + suffix + " SET " + column + "=? WHERE id=?",
                        column.equals("nickname") ? "held" : 1, column.equals("nickname") ? 1 : 2);
            }
            var riskId = new java.util.concurrent.CompletableFuture<Long>();
            var pending = worker.submit(() -> transaction.execute(() -> {
                fixedSession(jdbc);
                riskId.complete(jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class));
                int affected = old ? jdbc.update(legacy(LEGACY.get(cleanup))) : switch (cleanup) {
                    case 0 -> mapper.retireOrphanScoreUsers();
                    case 1 -> mapper.deactivateOrphanScoreOverrides();
                    case 2 -> mapper.retireOrphanScoreContributions();
                    default -> throw new AssertionError("Unknown cleanup");
                };
                return affected;
            }));
            try {
                long requestId = riskId.get(5, TimeUnit.SECONDS);
                Map<String, Object> wait;
                try {
                    wait = observedWait(requestId, accountId, suffix);
                } catch (AssertionError missingWait) {
                    Map<String, Object> outcome = new LinkedHashMap<>(Map.of("requestConnectionId", requestId,
                            "accountConnectionId", accountId, "pendingDoneBeforeRelease", pending.isDone()));
                    account.rollback();
                    try { outcome.put("returnedAffectedRows", pending.get(15, TimeUnit.SECONDS)); }
                    catch (Exception pendingFailure) {
                        outcome.put("exceptionType", pendingFailure.getClass().getName());
                        missingWait.addSuppressed(pendingFailure);
                        if (pendingFailure instanceof InterruptedException) Thread.currentThread().interrupt();
                    }
                    outcome.put("pendingDoneAfterRelease", pending.isDone());
                    try { writeJson(label + "-missing-wait.json", outcome); }
                    catch (Exception evidenceFailure) { missingWait.addSuppressed(evidenceFailure); }
                    throw missingWait;
                }
                if (scenario.equals("auto")) {
                    assertThat(wait.get("requestType")).isEqualTo("RECORD");
                    assertThat(wait.get("objectName")).isEqualTo("nx_user" + suffix);
                    // The low account transaction inserted only actual auto ID 3; secondary-index tuples are not primary IDs.
                    wait.put("autoLowId", 3L);
                    wait.put("autoHighId", 4L);
                }
                writeJson(label + "-wait.json", wait);
                if (scenario.equals("forward") || scenario.equals("backward")) {
                    transaction.execute(() -> {
                        fixedSession(jdbc);
                        user(jdbc, suffix, scenario.equals("forward") ? 99L : 0L, 0, 0);
                        return null;
                    });
                }
                account.commit();
                int affected = pending.get(15, TimeUnit.SECONDS);
                Map<String, Object> completed = new LinkedHashMap<>(Map.of("requestConnectionId", requestId,
                        "accountConnectionId", accountId, "returnedAffectedRows", affected));
                if (scenario.equals("auto")) {
                    completed.put("autoLowId", wait.get("autoLowId"));
                    completed.put("autoHighId", wait.get("autoHighId"));
                }
                writeJson(label + "-completed.json", completed);
            } finally {
                account.rollback();
            }
        } finally {
            worker.shutdownNow();
            assertThat(worker.awaitTermination(15, TimeUnit.SECONDS)).isTrue();
        }
        writeDumps(label, suffix);
        String table = TABLES.get(cleanup + 1) + suffix;
        String changed = cleanup == 1 ? "active" : "is_deleted";
        String number = scenario.equals("auto") ? "U00000003" : scenario.equals("backward") ? "U00000000"
                : scenario.equals("forward") ? "U00000099" : "U00000002";
        int expected = scenario.equals("deleted") || scenario.equals("sandbox") ? 1 : 0;
        if (cleanup == 1) expected = 1 - expected;
        assertThat(jdbc.queryForObject("SELECT " + changed + " FROM " + table + " WHERE user_no=?", Integer.class, number)).isEqualTo(expected);
    }

    private Map<String, Object> observedWait(long riskId, long accountId, String suffix) throws Exception {
        String sql = """
                SELECT rt.trx_mysql_thread_id requestConnection,bt.trx_mysql_thread_id blockConnection,
                       w.REQUESTING_ENGINE_TRANSACTION_ID requestTransaction,w.BLOCKING_ENGINE_TRANSACTION_ID blockTransaction,
                       r.OBJECT_SCHEMA objectSchema,r.OBJECT_NAME objectName,r.INDEX_NAME requestKey,
                       r.LOCK_TYPE requestType,b.LOCK_TYPE blockType,r.LOCK_MODE requestMode,b.LOCK_MODE blockMode,r.LOCK_DATA record
                  FROM performance_schema.data_lock_waits w
                  JOIN performance_schema.data_locks r ON r.ENGINE_LOCK_ID=w.REQUESTING_ENGINE_LOCK_ID AND r.ENGINE=w.ENGINE
                  JOIN performance_schema.data_locks b ON b.ENGINE_LOCK_ID=w.BLOCKING_ENGINE_LOCK_ID AND b.ENGINE=w.ENGINE
                  JOIN information_schema.innodb_trx rt ON rt.trx_id=w.REQUESTING_ENGINE_TRANSACTION_ID
                  JOIN information_schema.innodb_trx bt ON bt.trx_id=w.BLOCKING_ENGINE_TRANSACTION_ID
                 WHERE r.OBJECT_SCHEMA=? AND r.OBJECT_NAME=?
                   AND rt.trx_mysql_thread_id=? AND bt.trx_mysql_thread_id=?
                """;
        assertThat(riskId).isNotEqualTo(accountId);
        // MySQL refreshes INNODB_TRX only after more than 100 ms without a cache read.
        for (int attempt = 0; attempt < 20; attempt++) {
            List<Map<String, Object>> waits = jdbc.queryForList(sql, database, "nx_user" + suffix, riskId, accountId);
            if (!waits.isEmpty()) return waits.get(0);
            Thread.sleep(125);
        }
        throw new AssertionError("No engine transaction to real connection lock wait observed");
    }

    private void reset() {
        assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class)).isEqualTo(database);
        for (String table : TABLES) for (String suffix : List.of("", "_legacy")) jdbc.execute("TRUNCATE TABLE " + table + suffix);
    }

    private void fixedSession(JdbcTemplate connection) {
        connection.execute("SET NAMES utf8mb4 COLLATE utf8mb4_0900_ai_ci");
        connection.execute("SET SESSION sql_mode=CONCAT(@@sql_mode,',NO_AUTO_VALUE_ON_ZERO')");
        connection.execute("SET timestamp=" + BUSINESS_TIME);
        connection.execute("SET SESSION innodb_lock_wait_timeout=10");
    }

    private void user(JdbcTemplate connection, String suffix, long id, int deleted, int sandbox) {
        String label = Long.toString(id, 36);
        connection.update("INSERT INTO nx_user" + suffix + " (id,country_code,phone,client_ip,password_hash,nickname,referral_code,is_deleted,sandbox) VALUES (?,'X',?,'127.0.0.1','synthetic-not-login','synthetic',?,?,?)",
                id, "synthetic-" + label, "synthetic-ref-" + label, deleted, sandbox);
    }

    private void autoUser(JdbcTemplate connection, String suffix, String label) {
        connection.update("INSERT INTO nx_user" + suffix + " (country_code,phone,client_ip,password_hash,nickname,referral_code) VALUES ('X',?,'127.0.0.1','synthetic-not-login','synthetic',?)", "auto-" + label, "auto-ref-" + label);
    }

    private void riskRows(String number, int index, int deleted) {
        jdbc.update("INSERT INTO nx_admin_risk_score_user (user_no,model_score,model_version,row_version,as_of,updated_text,is_deleted) VALUES (?,?,'synthetic',?,NOW(),'synthetic',?)", number, index % 99, index, deleted);
        jdbc.update("INSERT INTO nx_admin_risk_score_override (user_no,model_score,override_score,reason,operator,time_text,active,is_deleted) VALUES (?, ?,50,'synthetic','synthetic','synthetic',1,?)", number, index % 99, deleted);
        contribution(number);
    }

    private void contribution(String number) {
        jdbc.update("INSERT INTO nx_admin_risk_score_contribution (user_no,model_version,dim_key,name,hit,evidence,sub_score,weight_pct,points,sort_order,is_deleted) VALUES (?,1,'synthetic','synthetic',1,'synthetic',1,10,1,3,0)", number);
    }

    private void cloneRows() {
        for (String table : TABLES) {
            List<String> columns = jdbc.queryForList("SELECT COLUMN_NAME FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=? AND TABLE_NAME=? AND EXTRA NOT LIKE '%VIRTUAL GENERATED%' AND EXTRA NOT LIKE '%STORED GENERATED%' ORDER BY ORDINAL_POSITION", String.class, database, table);
            String names = columns.stream().map(column -> "`" + column + "`").collect(java.util.stream.Collectors.joining(","));
            jdbc.update("INSERT INTO " + table + "_legacy (" + names + ") SELECT " + names + " FROM " + table);
        }
    }

    private String legacy(String statement) {
        for (String table : TABLES) statement = statement.replace(table, table + "_legacy");
        return statement;
    }

    private void assertAllFields(String label) throws Exception {
        for (String table : TABLES) {
            String old = dump(table + "_legacy");
            String current = dump(table);
            Files.writeString(evidence.resolve(label + "-comparison-old-" + table + ".tsv"), old, StandardOpenOption.CREATE_NEW);
            Files.writeString(evidence.resolve(label + "-comparison-mapper-" + table + ".tsv"), current, StandardOpenOption.CREATE_NEW);
            assertThat(current).as("every field byte of %s: %s", table, label).isEqualTo(old);
        }
    }

    private void writeDumps(String label, String suffix) throws Exception {
        for (String table : TABLES) Files.writeString(evidence.resolve(label + "-" + table + ".tsv"), dump(table + suffix), StandardOpenOption.CREATE_NEW);
    }

    private String dump(String table) {
        return jdbc.query("SELECT * FROM " + table + " ORDER BY id", (ResultSet rows) -> {
            StringBuilder result = new StringBuilder();
            var metadata = rows.getMetaData();
            for (int column = 1; column <= metadata.getColumnCount(); column++) result.append(metadata.getColumnName(column)).append('\t');
            result.append('\n');
            while (rows.next()) {
                for (int column = 1; column <= metadata.getColumnCount(); column++) {
                    byte[] value = rows.getBytes(column);
                    result.append(value == null ? "NULL" : HexFormat.of().formatHex(value)).append('\t');
                }
                result.append('\n');
            }
            return result.toString();
        });
    }

    private void recordStage(String name, long started, boolean passed) throws Exception {
        Map<String, Object> stage = new LinkedHashMap<>();
        stage.put("name", name);
        stage.put("mode", "full");
        stage.put("verdict", passed ? "pass" : "fail");
        stage.put("completedAt", Instant.now().toString());
        stage.put("elapsedMilliseconds", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
        stages.add(stage);
        writeJson(name + ".json", stage);
    }

    private void writeJson(String name, Object value) throws Exception {
        Files.writeString(evidence.resolve(name), json.writerWithDefaultPrettyPrinter().writeValueAsString(value), StandardOpenOption.CREATE_NEW);
    }
}
