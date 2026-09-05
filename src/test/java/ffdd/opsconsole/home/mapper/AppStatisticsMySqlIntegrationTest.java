package ffdd.opsconsole.home.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import ffdd.opsconsole.shared.canonical.mapper.CanonicalStateMapper;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Random;
import java.util.UUID;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

/**
 * Real-MySQL mapper regressions. Every fixture lives in one UUID-scoped database which is created
 * and dropped by this test class; the application database is never used for fixture rows.
 */
@EnabledIfEnvironmentVariable(named = "NEXION_TEST_DB_PASSWORD", matches = ".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AppStatisticsMySqlIntegrationTest {
    private static final long USER = 42L;
    private static final String PRODUCTION = "PRODUCTION";
    private static final LocalDateTime END = LocalDateTime.of(2026, 8, 31, 12, 0);
    private static final LocalDateTime DAY = END.toLocalDate().atStartOfDay();
    private static final LocalDateTime YESTERDAY = DAY.minusDays(1);
    private static final LocalDateTime WEEK = DAY.minusDays(6);
    private static final LocalDateTime MONTH = DAY.withDayOfMonth(1);
    private static final LocalDateTime ALL = LocalDateTime.of(1970, 1, 1, 0, 0);
    private static final String INDEX_MIGRATION = "scripts/migrations/20260831_app_statistics_read_indexes.sql";

    private Connection connection;
    private JdbcTemplate jdbc;
    private String fixtureDatabase;
    private boolean fixtureDatabaseCreated;
    private AppHomeOverviewMapper home;
    private CanonicalStateMapper canonical;
    private long nextReceiptId = 1;
    private long nextTaskId = 1;

    @BeforeAll
    void createOneOwnedFixtureDatabaseForThisSuite() throws Exception {
        connection = DriverManager.getConnection(System.getenv().getOrDefault("NEXION_TEST_DB_URL",
                        "jdbc:mysql://127.0.0.1:3306/nexion?useUnicode=true&characterEncoding=utf8"
                                + "&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true"),
                System.getenv().getOrDefault("NEXION_TEST_DB_USERNAME", "root"),
                System.getenv("NEXION_TEST_DB_PASSWORD"));
        SingleConnectionDataSource dataSource = new SingleConnectionDataSource(connection, true);
        jdbc = new JdbcTemplate(dataSource);
        fixtureDatabase = "nx_app_stats_test_" + UUID.randomUUID().toString().replace("-", "");
        assertOwnedFixtureDatabase();
        try {
            jdbc.execute("CREATE DATABASE `" + fixtureDatabase
                    + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
            fixtureDatabaseCreated = true;
            jdbc.execute("USE `" + fixtureDatabase + "`");
            createMinimalFixtureSchema();

            Configuration configuration = new Configuration(new Environment("app-statistics-test",
                    new SpringManagedTransactionFactory(), dataSource));
            configuration.setMapUnderscoreToCamelCase(true);
            configuration.addMapper(AppHomeOverviewMapper.class);
            configuration.addMapper(CanonicalStateMapper.class);
            SqlSessionTemplate session = new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(configuration));
            home = session.getMapper(AppHomeOverviewMapper.class);
            canonical = session.getMapper(CanonicalStateMapper.class);
        } catch (Exception failure) {
            dropOwnedFixtureDatabase();
            throw failure;
        }
    }

    @BeforeEach
    void clearOnlyTheOwnedFixtureDatabase() {
        assertOwnedFixtureDatabase();
        assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class)).isEqualTo(fixtureDatabase);
        for (String table : List.of("nx_order", "nx_product", "nx_compute_datacenter", "nx_compute_receipt",
                "nx_compute_task", "nx_user_device", "nx_user")) {
            jdbc.execute("TRUNCATE TABLE " + table);
        }
        nextReceiptId = 1;
        nextTaskId = 1;
    }

    @AfterAll
    void discardOwnedFixtureDatabase() throws Exception {
        if (connection == null) return;
        try {
            dropOwnedFixtureDatabase();
        } finally {
            connection.close();
        }
    }

    @Test
    void earningsSummaryMatchesFiveLegacyQueriesAtEveryWindowBoundaryAndScope() {
        receipt(USER, PRODUCTION, "PoStEd", DAY, "1.000001", "2.000002", 0);
        receipt(USER, PRODUCTION, "SUCCESS", END.minusNanos(1), "-3.500000", "0.000001", 0);
        receipt(USER, PRODUCTION, "SETTLED", YESTERDAY, "4.000000", "-1.000000", 0);
        receipt(USER, PRODUCTION, "CREDITED", WEEK, "5.000000", "6.000000", 0);
        receipt(USER, PRODUCTION, "PAID", MONTH, "0.000000", "-2.000000", 0);
        receipt(USER, PRODUCTION, "POSTED", ALL, "7.000000", "8.000000", 0);
        receipt(USER, PRODUCTION, "POSTED", END, "99.000000", "99.000000", 0);
        receipt(USER, PRODUCTION, "POSTED", ALL.minusNanos(1), "88.000000", "88.000000", 0);
        receipt(USER, "SANDBOX", "POSTED", DAY, "77.000000", "77.000000", 0);
        receipt(USER + 1, PRODUCTION, "POSTED", DAY, "66.000000", "66.000000", 0);
        receipt(USER, PRODUCTION, "PENDING", DAY, "55.000000", "55.000000", 0);
        receipt(USER, PRODUCTION, "POSTED", DAY, "44.000000", "44.000000", 1);

        assertSummaryEqualsLegacy();
    }

    @Test
    void earningsSummaryMatchesLegacyForASeededRandomPopulation() {
        Random random = new Random(19700101L);
        String[] statuses = {"POSTED", "SUCCESS", "SETTLED", "CREDITED", "PAID", "PENDING", "REJECTED"};
        for (int index = 0; index < 300; index++) {
            long minutes = random.nextLong(0, 70L * 24 * 60);
            LocalDateTime completed = END.minusMinutes(minutes);
            long owner = random.nextInt(10) == 0 ? USER + 1 : USER;
            String environment = random.nextInt(9) == 0 ? "SANDBOX" : PRODUCTION;
            int deleted = random.nextInt(12) == 0 ? 1 : 0;
            BigDecimal usdt = BigDecimal.valueOf(random.nextLong(-2_000_000L, 2_000_001L), 6);
            BigDecimal nex = BigDecimal.valueOf(random.nextLong(-2_000_000L, 2_000_001L), 6);
            receipt(owner, environment, statuses[random.nextInt(statuses.length)], completed,
                    usdt.toPlainString(), nex.toPlainString(), deleted);
        }

        assertSummaryEqualsLegacy();
    }

    @Test
    void earningsSummaryKeepsNullSumsAndZeroCountsForEmptyWindows() {
        AppHomeOverviewMapper.EarningsSummaryRow summary = summary();

        assertPeriod(summary.today(), null, null, 0L);
        assertPeriod(summary.yesterday(), null, null, 0L);
        assertPeriod(summary.week(), null, null, 0L);
        assertPeriod(summary.month(), null, null, 0L);
        assertPeriod(summary.all(), null, null, 0L);
        assertSummaryEqualsLegacy();
    }

    @Test
    void appStatisticsIndexMigrationIsIdempotentAndKeepsTheExactReadShapes() throws Exception {
        executeIndexMigration();
        executeIndexMigration();

        assertAscendingColumns("nx_compute_receipt", "idx_receipt_user_earnings",
                List.of("user_id", "source_environment", "is_deleted", "earning_status", "completed_at",
                        "reward_usdt", "reward_nex"));
        assertAscendingColumns("nx_compute_receipt", "idx_receipt_device_earnings",
                List.of("user_device_id", "source_environment", "is_deleted", "earning_status", "reward_usdt"));
        GeneratedColumn latestObserved = generatedColumn("nx_compute_task", "client_observed_at");
        assertThat(latestObserved.columnType()).isEqualToIgnoringCase("datetime");
        assertThat(latestObserved.extra()).containsIgnoringCase("VIRTUAL GENERATED");
        assertThat(normalizeExpression(latestObserved.expression()))
                .isEqualTo("coalesce(completed_at,updated_at,created_at)");
        List<IndexPart> latestClient = indexParts("nx_compute_task", "idx_task_device_latest_client");
        assertThat(latestClient).hasSize(4);
        assertThat(latestClient.get(0)).isEqualTo(new IndexPart("user_device_id", null, "A"));
        assertThat(latestClient.get(1)).isEqualTo(new IndexPart("is_deleted", null, "A"));
        assertThat(latestClient.get(2)).isEqualTo(new IndexPart("client_observed_at", null, "D"));
        assertThat(latestClient.get(3)).isEqualTo(new IndexPart("id", null, "D"));
    }

    @Test
    void appStatisticsIndexMigrationFailsClosedForAnExistingSameNameWrongIndex() {
        try {
            dropReceiptEarningsIndexIfPresent();
            jdbc.execute("CREATE INDEX idx_receipt_user_earnings ON nx_compute_receipt(user_id, completed_at)");
            assertThatThrownBy(this::executeIndexMigration)
                    .hasStackTraceContaining("APP_STATS_INDEX_SHAPE_INVALID_idx_receipt_user_earnings");
        } finally {
            releaseMigrationLock();
            dropReceiptEarningsIndexIfPresent();
        }
    }

    @Test
    void appStatisticsIndexMigrationFailsClosedForAnIncompatibleExistingLatestColumn() {
        int originalLockWait = jdbc.queryForObject("SELECT @@session.lock_wait_timeout", Integer.class);
        try {
            dropLatestClientIndexIfPresent();
            dropLatestObservedColumnIfPresent();
            jdbc.execute("ALTER TABLE nx_compute_task ADD COLUMN client_observed_at DATETIME NULL");
            assertThatThrownBy(this::executeIndexMigration)
                    .hasStackTraceContaining("APP_STATS_LATEST_COLUMN_SHAPE_INVALID");
        } finally {
            releaseMigrationLock();
            jdbc.execute("SET SESSION lock_wait_timeout = " + originalLockWait);
            dropLatestClientIndexIfPresent();
            dropLatestObservedColumnIfPresent();
            try {
                executeIndexMigration();
            } catch (Exception restoreFailure) {
                throw new AssertionError("Could not restore the owned migration fixture", restoreFailure);
            }
        }
    }

    @Test
    void onGridRetainsTaskEligibilityAndSignedPerSecondArithmetic() {
        user(USER, false, "ACTIVE", 0);
        device(100, USER, "ACTIVE", "OWNED", 0, "GPU", "DC-A");
        device(101, USER, "OFFLINE", "OWNED", 0, "GPU", "DC-A"); // onGrid never filtered device status.
        device(102, USER, "ACTIVE", "OWNED", 1, "GPU", "DC-A");
        user(USER + 1, false, "DISABLED", 0);
        device(103, USER + 1, "ACTIVE", "OWNED", 0, "GPU", "DC-A");
        user(USER + 2, true, "ACTIVE", 0);
        device(104, USER + 2, "ACTIVE", "OWNED", 0, "GPU", "DC-A");

        task(100, USER, PRODUCTION, "ASSIGNED", "2.000000", 4, "", END.minusMinutes(1), 0);
        task(101, USER, PRODUCTION, "RUNNING", "-3.000000", 6, "", END.minusMinutes(1), 0);
        task(100, USER, PRODUCTION, "PROCESSING", "9.000000", 0, "", END.minusMinutes(1), 0);
        task(100, USER, "SANDBOX", "RUNNING", "99.000000", 1, "", END.minusMinutes(1), 0);
        task(100, USER, PRODUCTION, "COMPLETED", "99.000000", 1, "", END.minusMinutes(1), 0);
        task(102, USER, PRODUCTION, "RUNNING", "99.000000", 1, "", END.minusMinutes(1), 0);
        task(103, USER + 1, PRODUCTION, "RUNNING", "99.000000", 1, "", END.minusMinutes(1), 0);
        task(104, USER + 2, PRODUCTION, "RUNNING", "99.000000", 1, "", END.minusMinutes(1), 0);

        AppHomeOverviewMapper.OnGridSummary grid = home.onGrid(PRODUCTION, false);
        assertThat(grid.activeJobs()).isEqualTo(3L);
        assertThat(grid.perSecUsdt()).isEqualByComparingTo("0.000000");
    }

    @Test
    void onGridClientsKeepsLatestNonblankClientAndDatacenterFallbacks() throws Exception {
        user(USER, false, "ACTIVE", 0);
        user(USER + 1, false, "DISABLED", 0);
        datacenter("DC-A", "Metro", "Metro DC", 0);
        datacenter("DC-B", "Coast", "Coast DC", 0);
        datacenter("DC-DELETED", "Hidden", "Hidden DC", 1);
        device(100, USER, "ACTIVE", "OWNED", 0, "A100", "DC-A");
        device(101, USER, "ONLINE", "OWNED", 0, "A100", "DC-A");
        device(102, USER, "BUSY", "OWNED", 0, "L40", "DC-B");
        device(103, USER, "RUNNING", "OWNED", 0, "T4", "DC-DELETED");
        device(104, USER, "OFFLINE", "OWNED", 0, "T4", "DC-B");
        device(105, USER, "ACTIVE", "LEASED", 0, "T4", "DC-B");
        device(106, USER, "ACTIVE", "OWNED", 1, "T4", "DC-B");
        device(107, USER + 1, "ACTIVE", "OWNED", 0, "T4", "DC-B");

        task(100, USER, PRODUCTION, "COMPLETED", "0", 1, "old", END.minusDays(1), 0);
        long updatedAtFallback = task(100, USER, PRODUCTION, "COMPLETED", "0", 1, "newest", END.minusHours(1), 0);
        task(101, USER, PRODUCTION, "COMPLETED", "0", 1, "newest", END.minusHours(2), 0);
        task(102, USER, PRODUCTION, "COMPLETED", "0", 1, "valid older", END.minusDays(1), 0);
        task(102, USER, PRODUCTION, "COMPLETED", "0", 1, "   ", END.minusMinutes(1), 0);
        task(102, USER, PRODUCTION, "COMPLETED", "0", 1, "tie older", END.minusHours(3), 0);
        task(102, USER, PRODUCTION, "COMPLETED", "0", 1, "tie winner", END.minusHours(3), 0);
        jdbc.update("UPDATE nx_compute_task SET completed_at=NULL WHERE id=?", updatedAtFallback);

        executeIndexMigration();
        assertLatestClientOracle(100L, "newest");
        assertLatestClientOracle(102L, "tie winner");
        assertLatestClientPlanAvoidsFilesort(102L);

        List<AppHomeOverviewMapper.OnGridClientRow> rows = home.onGridClients(false);
        assertThat(rows).extracting(row -> org.assertj.core.groups.Tuple.tuple(
                        row.name(), row.model(), row.city(), row.gpus()))
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("newest", "A100", "Metro", 2L),
                        org.assertj.core.groups.Tuple.tuple("tie winner", "L40", "Coast", 1L),
                        org.assertj.core.groups.Tuple.tuple(null, "T4", "DC-DELETED", 1L));
    }

    @Test
    void ownedDevicesMatchesTheLegacyPerDeviceReceiptAndPaymentSemantics() {
        user(USER, false, "ACTIVE", 0);
        user(USER + 1, true, "ACTIVE", 0);
        product(1, "P-ONE", "9.000000", 0);
        device(200, USER, "ACTIVE", "OWNED", 0, "A100", "DC-A");
        device(201, USER, "ACTIVE", "OWNED", 0, "L40", "DC-B");
        device(202, USER, "ACTIVE", "LEASED", 0, "T4", "DC-B");
        device(203, USER + 1, "ACTIVE", "OWNED", 0, "T4", "DC-B");
        jdbc.update("UPDATE nx_user_device SET source_order_no='ORDER-200', product_id=1, price_usdt_snapshot=4 WHERE id=200");
        jdbc.update("UPDATE nx_user_device SET price_usdt_snapshot=7 WHERE id=201");
        order("ORDER-200", USER, 1, 2, "10.000000", "PAID", 0);
        receiptForDevice(USER, 200L, PRODUCTION, "SUCCESS", DAY, "1.100000", "0", 0);
        receiptForDevice(USER, 200L, PRODUCTION, "PENDING", DAY, "99.000000", "0", 0);
        receiptForDevice(USER, 201L, PRODUCTION, "cReDiTeD", DAY, "-2.000000", "0", 0);
        receiptForDevice(USER, 201L, "SANDBOX", "POSTED", DAY, "88.000000", "0", 0);

        List<CanonicalStateMapper.OwnedDevice> actual = canonical.ownedDevices(USER);
        assertThat(actual).hasSize(2);
        assertThat(actual).extracting(CanonicalStateMapper.OwnedDevice::id).containsExactly(200L, 201L);
        assertThat(actual.get(0).actualPaidUsdt()).isEqualByComparingTo("5.000000");
        assertThat(actual.get(0).cumulativeOutputUsdt()).isEqualByComparingTo("1.100000");
        assertThat(actual.get(1).actualPaidUsdt()).isEqualByComparingTo("7.000000");
        assertThat(actual.get(1).cumulativeOutputUsdt()).isEqualByComparingTo("-2.000000");
    }

    private void assertSummaryEqualsLegacy() {
        AppHomeOverviewMapper.EarningsSummaryRow summary = summary();
        assertSamePeriod(home.earnings(USER, PRODUCTION, DAY, END), summary.today());
        assertSamePeriod(home.earnings(USER, PRODUCTION, YESTERDAY, DAY), summary.yesterday());
        assertSamePeriod(home.earnings(USER, PRODUCTION, WEEK, END), summary.week());
        assertSamePeriod(home.earnings(USER, PRODUCTION, MONTH, END), summary.month());
        assertSamePeriod(home.earnings(USER, PRODUCTION, ALL, END), summary.all());
    }

    private AppHomeOverviewMapper.EarningsSummaryRow summary() {
        return home.earningsSummary(USER, PRODUCTION, DAY, YESTERDAY, DAY, WEEK, MONTH, END);
    }

    private void assertSamePeriod(AppHomeOverviewMapper.PeriodRow expected,
                                  AppHomeOverviewMapper.PeriodRow actual) {
        assertPeriod(actual, expected.usdt(), expected.nex(), expected.jobCount());
    }

    private void assertPeriod(AppHomeOverviewMapper.PeriodRow actual, BigDecimal usdt, BigDecimal nex, Long count) {
        assertThat(actual.jobCount()).isEqualTo(count);
        if (usdt == null) assertThat(actual.usdt()).isNull();
        else assertThat(actual.usdt()).isEqualByComparingTo(usdt);
        if (nex == null) assertThat(actual.nex()).isNull();
        else assertThat(actual.nex()).isEqualByComparingTo(nex);
    }

    private void receipt(long userId, String environment, String status, LocalDateTime completedAt,
                         String usdt, String nex, int deleted) {
        receiptForDevice(userId, null, environment, status, completedAt, usdt, nex, deleted);
    }

    private void receiptForDevice(long userId, Long deviceId, String environment, String status, LocalDateTime completedAt,
                                  String usdt, String nex, int deleted) {
        long id = nextReceiptId++;
        jdbc.update("""
                INSERT INTO nx_compute_receipt(id,user_id,user_device_id,receipt_no,task_type,client_name,
                    reward_usdt,reward_nex,earning_status,source_environment,proof_hash,completed_at,is_deleted)
                VALUES(?,?,?,?,?,'fixture',?,?,?,?,?,?,?)
                """, id, userId, deviceId, "RECEIPT-" + id, "fixture", new BigDecimal(usdt), new BigDecimal(nex),
                status, environment, "proof-" + id, completedAt, deleted);
    }

    private void user(long id, boolean sandbox, String status, int deleted) {
        jdbc.update("""
                INSERT INTO nx_user(id,country_code,phone,client_ip,password_hash,nickname,referral_code,status,sandbox,is_deleted)
                VALUES(?, '86', ?, '127.0.0.1', 'not-a-secret', ?, ?, ?, ?, ?)
                """, id, "fixture-" + id, "user-" + id, "ref-" + id, status, sandbox, deleted);
    }

    private void device(long id, long userId, String status, String ownership, int deleted, String gpu, String dc) {
        jdbc.update("""
                INSERT INTO nx_user_device(id,user_id,instance_no,name,device_type,gpu_model,dc_location,
                    ownership_status,status,purchased_at,activated_at,is_deleted)
                VALUES(?,?,?,'fixture device','PC_GPU',?,?,?, ?,?,?,?)
                """, id, userId, "DEVICE-" + id, gpu, dc, ownership, status, DAY.minusDays(1), DAY.minusDays(1), deleted);
    }

    private void datacenter(String location, String city, String displayName, int deleted) {
        jdbc.update("""
                INSERT INTO nx_compute_datacenter(dc_location,region_label,location,display_name,status,is_deleted)
                VALUES(?, 'fixture region', ?, ?, 'active', ?)
                """, location, city, displayName, deleted);
    }

    private long task(long deviceId, long userId, String environment, String status, String reward,
                      int seconds, String client, LocalDateTime changedAt, int deleted) {
        long id = nextTaskId++;
        jdbc.update("""
                INSERT INTO nx_compute_task(id,task_no,user_id,user_device_id,task_type,client_name,status,reward_usdt,
                    required_seconds,source_environment,completed_at,created_at,updated_at,is_deleted)
                VALUES(?,?,?,?,'fixture',?,?,?,?,?,?,?,?,?)
                """, id, "TASK-" + id, userId, deviceId, client, status, new BigDecimal(reward), seconds,
                environment, changedAt, changedAt, changedAt, deleted);
        return id;
    }

    private void product(long id, String productNo, String price, int deleted) {
        jdbc.update("""
                INSERT INTO nx_product(id,product_no,name,product_type,tier,price_usdt,status,is_deleted)
                VALUES(?,?,?,'DEVICE','fixture',?,'ACTIVE',?)
                """, id, productNo, "product-" + id, new BigDecimal(price), deleted);
    }

    private void order(String orderNo, long userId, long productId, int quantity, String amount, String paymentStatus,
                       int deleted) {
        jdbc.update("""
                INSERT INTO nx_order(user_id,order_no,product_id,quantity,order_type,item_count,subtotal_usdt,
                    discount_usdt,amount_usdt,payment_status,order_status,activation_status,is_deleted)
                VALUES(?,?,?,?,'SINGLE',?, ?,0,?,?,'PAID','WAITING_PAYMENT',?)
                """, userId, orderNo, productId, quantity, quantity, new BigDecimal(amount), new BigDecimal(amount),
                paymentStatus, deleted);
    }

    private void assertOwnedFixtureDatabase() {
        assertThat(fixtureDatabase).matches("nx_app_stats_test_[0-9a-f]{32}");
    }

    private void dropOwnedFixtureDatabase() {
        if (!fixtureDatabaseCreated) return;
        assertOwnedFixtureDatabase();
        jdbc.execute("USE information_schema");
        jdbc.execute("DROP DATABASE `" + fixtureDatabase + "`");
        fixtureDatabaseCreated = false;
    }

    private void executeIndexMigration() throws Exception {
        ScriptUtils.executeSqlScript(connection, new FileSystemResource(INDEX_MIGRATION));
    }

    private void assertAscendingColumns(String table, String index, List<String> columns) {
        assertThat(indexParts(table, index)).containsExactlyElementsOf(columns.stream()
                .map(column -> new IndexPart(column, null, "A")).toList());
    }

    private List<IndexPart> indexParts(String table, String index) {
        return jdbc.query("""
                SELECT column_name, expression, collation, non_unique, index_type, sub_part, is_visible
                  FROM information_schema.statistics
                 WHERE table_schema = DATABASE() AND table_name = ? AND index_name = ?
                 ORDER BY seq_in_index
                """, (resultSet, row) -> {
            assertThat(resultSet.getInt("non_unique")).isEqualTo(1);
            assertThat(resultSet.getString("index_type")).isEqualTo("BTREE");
            assertThat(resultSet.getObject("sub_part")).isNull();
            assertThat(resultSet.getString("is_visible")).isEqualTo("YES");
            return new IndexPart(resultSet.getString("column_name"), resultSet.getString("expression"),
                    resultSet.getString("collation"));
        }, table, index);
    }

    private GeneratedColumn generatedColumn(String table, String column) {
        return jdbc.queryForObject("""
                SELECT column_type, extra, generation_expression
                  FROM information_schema.columns
                 WHERE table_schema=DATABASE() AND table_name=? AND column_name=?
                """, (resultSet, row) -> new GeneratedColumn(resultSet.getString("column_type"),
                resultSet.getString("extra"), resultSet.getString("generation_expression")), table, column);
    }

    private String normalizeExpression(String expression) {
        return expression == null ? null : expression.replace("`", "").replace(" ", "").toLowerCase();
    }

    private void assertLatestClientOracle(long deviceId, String expectedClient) {
        String client = jdbc.queryForObject("""
                SELECT t.client_name FROM nx_compute_task t
                 WHERE t.user_device_id=? AND t.is_deleted=0
                   AND NULLIF(TRIM(t.client_name), '') IS NOT NULL
                 ORDER BY COALESCE(t.completed_at, t.updated_at, t.created_at) DESC, t.id DESC
                 LIMIT 1
                """, String.class, deviceId);
        assertThat(client).isEqualTo(expectedClient);
    }

    private void assertLatestClientPlanAvoidsFilesort(long deviceId) {
        var plan = jdbc.queryForMap("""
                EXPLAIN SELECT t.id FROM nx_compute_task t
                 WHERE t.user_device_id=? AND t.is_deleted=0
                   AND NULLIF(TRIM(t.client_name), '') IS NOT NULL
                 ORDER BY t.user_device_id, t.is_deleted, t.client_observed_at DESC, t.id DESC
                 LIMIT 1
                """, deviceId);
        assertThat(plan.get("key")).isEqualTo("idx_task_device_latest_client");
        assertThat(String.valueOf(plan.get("Extra"))).doesNotContainIgnoringCase("filesort");
    }

    private void dropReceiptEarningsIndexIfPresent() {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.statistics
                 WHERE table_schema=DATABASE() AND table_name='nx_compute_receipt'
                   AND index_name='idx_receipt_user_earnings'
                """, Integer.class);
        if (count != null && count > 0) jdbc.execute("DROP INDEX idx_receipt_user_earnings ON nx_compute_receipt");
    }

    private void dropLatestClientIndexIfPresent() {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.statistics
                 WHERE table_schema=DATABASE() AND table_name='nx_compute_task'
                   AND index_name='idx_task_device_latest_client'
                """, Integer.class);
        if (count != null && count > 0) jdbc.execute("DROP INDEX idx_task_device_latest_client ON nx_compute_task");
    }

    private void dropLatestObservedColumnIfPresent() {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.columns
                 WHERE table_schema=DATABASE() AND table_name='nx_compute_task'
                   AND column_name='client_observed_at'
                """, Integer.class);
        if (count != null && count > 0) jdbc.execute("ALTER TABLE nx_compute_task DROP COLUMN client_observed_at");
    }

    private void releaseMigrationLock() {
        jdbc.queryForObject("""
                SELECT RELEASE_LOCK(CONCAT('nx:appstats:', LEFT(SHA2(DATABASE(), 256), 30)))
                """, Integer.class);
    }

    private void createMinimalFixtureSchema() {
        jdbc.execute("""
                CREATE TABLE nx_user (
                    id BIGINT PRIMARY KEY, sandbox TINYINT NOT NULL, status VARCHAR(32) NOT NULL,
                    is_deleted TINYINT NOT NULL DEFAULT 0, country_code VARCHAR(8) NOT NULL,
                    phone VARCHAR(32) NOT NULL, client_ip VARCHAR(64) NOT NULL, password_hash VARCHAR(128) NOT NULL,
                    nickname VARCHAR(64) NOT NULL, referral_code VARCHAR(32) NOT NULL
                )
                """);
        jdbc.execute("""
                CREATE TABLE nx_user_device (
                    id BIGINT PRIMARY KEY, user_id BIGINT NOT NULL, source_order_no VARCHAR(96), product_id BIGINT,
                    product_code VARCHAR(64), product_tier VARCHAR(32), instance_no VARCHAR(64) NOT NULL,
                    name VARCHAR(128) NOT NULL, device_type VARCHAR(32) NOT NULL, gpu_model VARCHAR(128),
                    vram_total_gb INT, base_power_w DECIMAL(18,6) NOT NULL DEFAULT 0, dc_location VARCHAR(128),
                    price_usdt_snapshot DECIMAL(18,6) NOT NULL DEFAULT 0, ownership_status VARCHAR(32) NOT NULL,
                    status VARCHAR(32) NOT NULL, daily_usdt DECIMAL(18,6) NOT NULL DEFAULT 0,
                    daily_nex DECIMAL(18,6) NOT NULL DEFAULT 0, pending_deactivate TINYINT NOT NULL DEFAULT 0,
                    row_version BIGINT NOT NULL DEFAULT 0, purchased_at DATETIME, activated_at DATETIME,
                    deactivated_at DATETIME, is_deleted TINYINT NOT NULL DEFAULT 0
                )
                """);
        jdbc.execute("""
                CREATE TABLE nx_compute_task (
                    id BIGINT PRIMARY KEY, task_no VARCHAR(96) NOT NULL, user_id BIGINT NOT NULL,
                    user_device_id BIGINT NOT NULL, task_type VARCHAR(64) NOT NULL, client_name VARCHAR(128) NOT NULL,
                    status VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
                    reward_usdt DECIMAL(18,6) NOT NULL DEFAULT 0,
                    required_seconds INT NOT NULL DEFAULT 60,
                    source_environment VARCHAR(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
                    completed_at DATETIME, created_at DATETIME NOT NULL, updated_at DATETIME NOT NULL,
                    is_deleted TINYINT NOT NULL DEFAULT 0
                )
                """);
        jdbc.execute("""
                CREATE TABLE nx_compute_receipt (
                    id BIGINT PRIMARY KEY, user_id BIGINT NOT NULL, user_device_id BIGINT, task_no VARCHAR(96),
                    receipt_no VARCHAR(96) NOT NULL, task_type VARCHAR(64) NOT NULL, client_name VARCHAR(128) NOT NULL,
                    reward_usdt DECIMAL(18,6) NOT NULL DEFAULT 0, reward_nex DECIMAL(18,6) NOT NULL DEFAULT 0,
                    earning_status VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
                    source_environment VARCHAR(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
                    proof_hash VARCHAR(128) NOT NULL, completed_at DATETIME NOT NULL, is_deleted TINYINT NOT NULL DEFAULT 0
                )
                """);
        jdbc.execute("""
                CREATE TABLE nx_compute_datacenter (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY, dc_location VARCHAR(128) NOT NULL, region_label VARCHAR(128) NOT NULL,
                    location VARCHAR(128) NOT NULL, display_name VARCHAR(128) NOT NULL, status VARCHAR(24) NOT NULL,
                    is_deleted TINYINT NOT NULL DEFAULT 0
                )
                """);
        jdbc.execute("""
                CREATE TABLE nx_product (
                    id BIGINT PRIMARY KEY, product_no VARCHAR(64) NOT NULL, name VARCHAR(128) NOT NULL,
                    product_type VARCHAR(32) NOT NULL, tier VARCHAR(32), price_usdt DECIMAL(18,6) NOT NULL,
                    status VARCHAR(32) NOT NULL, is_deleted TINYINT NOT NULL DEFAULT 0
                )
                """);
        jdbc.execute("""
                CREATE TABLE nx_order (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY, user_id BIGINT NOT NULL, order_no VARCHAR(96) NOT NULL,
                    product_id BIGINT, quantity INT NOT NULL, order_type VARCHAR(32) NOT NULL, item_count INT NOT NULL,
                    subtotal_usdt DECIMAL(18,6) NOT NULL, discount_usdt DECIMAL(18,6) NOT NULL,
                    amount_usdt DECIMAL(18,6) NOT NULL, payment_status VARCHAR(32) NOT NULL,
                    order_status VARCHAR(32) NOT NULL, activation_status VARCHAR(32) NOT NULL,
                    is_deleted TINYINT NOT NULL DEFAULT 0
                )
                """);
    }

    private record IndexPart(String columnName, String expression, String collation) {
    }

    private record GeneratedColumn(String columnType, String extra, String expression) {
    }
}
