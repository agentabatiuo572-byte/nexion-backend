package ffdd.opsconsole.treasury.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
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
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

/** Reads the real MyBatis statement against disposable rows in an exclusively owned database. */
@EnabledIfEnvironmentVariable(named = "NEXION_TEST_DB_PASSWORD", matches = ".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WalletReconciliationMySqlIntegrationTest {
    private static final String INDEX = "idx_wallet_reconciliation_user";
    private static final String MIGRATION = "scripts/migrations/20260914_wallet_reconciliation_read_index.sql";
    private static final String LEGACY = """
            SELECT COALESCE(SUM(ABS(COALESCE(w.usdt_available, 0) - COALESCE(latest.balance_after, 0))), 0)
              FROM (SELECT user_id FROM nx_user_wallet WHERE is_deleted=0
                    UNION SELECT user_id FROM nx_wallet_ledger
                           WHERE is_deleted=0 AND asset='USDT' AND status='SUCCESS') users
              LEFT JOIN nx_user_wallet w ON w.user_id=users.user_id AND w.is_deleted=0
              LEFT JOIN (SELECT l.user_id,l.balance_after FROM nx_wallet_ledger l
                         JOIN (SELECT user_id,MAX(id) AS latest_id FROM nx_wallet_ledger
                               WHERE is_deleted=0 AND asset='USDT' AND status='SUCCESS'
                               GROUP BY user_id) ids ON ids.latest_id=l.id
                         WHERE l.is_deleted=0) latest ON latest.user_id=users.user_id
            """;
    private Connection connection;
    private JdbcTemplate jdbc;
    private String database;
    private boolean created;
    private Configuration configuration;
    private TreasuryLedgerMapper mapper;

    @BeforeAll
    void createOwnedDatabase() throws Exception {
        connection = DriverManager.getConnection(System.getenv().getOrDefault("NEXION_TEST_DB_URL",
                        "jdbc:mysql://127.0.0.1:3306/nexion?useUnicode=true&characterEncoding=utf8"
                                + "&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true"),
                System.getenv().getOrDefault("NEXION_TEST_DB_USERNAME", "root"),
                System.getenv("NEXION_TEST_DB_PASSWORD"));
        SingleConnectionDataSource dataSource = new SingleConnectionDataSource(connection, true);
        jdbc = new JdbcTemplate(dataSource);
        database = "nx_wallet_recon_test_" + UUID.randomUUID().toString().replace("-", "");
        assertThat(database).matches("nx_wallet_recon_test_[a-f0-9]{32}");
        jdbc.execute("CREATE DATABASE `" + database + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
        created = true;
        jdbc.execute("USE `" + database + "`");
        configuration = new Configuration(new Environment("wallet-recon-test",
                new SpringManagedTransactionFactory(), dataSource));
        configuration.addMapper(TreasuryLedgerMapper.class);
        mapper = new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(configuration))
                .getMapper(TreasuryLedgerMapper.class);
    }

    @BeforeEach
    void resetOnlyOwnedTables() {
        assertOwned();
        jdbc.execute("SELECT RELEASE_ALL_LOCKS()");
        jdbc.execute("DROP TABLE IF EXISTS nx_user_wallet,nx_wallet_ledger");
        jdbc.execute("""
                CREATE TABLE nx_user_wallet(id BIGINT PRIMARY KEY,user_id BIGINT,
                    usdt_available DECIMAL(18,6),is_deleted TINYINT,UNIQUE KEY uk_wallet_user(user_id))
                """);
        jdbc.execute("""
                CREATE TABLE nx_wallet_ledger(id BIGINT PRIMARY KEY,user_id BIGINT,
                    asset VARCHAR(16),status VARCHAR(32),is_deleted TINYINT,balance_after DECIMAL(18,6),
                    created_at DATETIME,KEY idx_wallet_ledger_user_time(user_id,created_at))
                """);
    }

    @AfterAll
    void dropOnlyOwnedDatabase() throws Exception {
        try {
            if (created) {
                assertOwned();
                jdbc.execute("DROP DATABASE `" + database + "`");
            }
        } finally {
            if (connection != null) connection.close();
        }
    }

    @Test
    void emptyWalletOnlyLedgerOnlyAndLatestEligibleIdStayEquivalent() throws Exception {
        migrate();
        assertThat(mapper.walletLedgerReconciliationGapUsdt()).isEqualByComparingTo("0");
        jdbc.update("INSERT INTO nx_user_wallet VALUES (1,1,10,0),(2,2,30,0),(3,3,40,1),(4,4,NULL,0),(5,5,-2,0)");
        ledger(1,1L,"USDT","SUCCESS",0,"9");
        ledger(2,1L,"USDT","FAILED",0,"999");
        ledger(3,2L,"NEX","SUCCESS",0,"999");
        ledger(4,3L,"USDT","SUCCESS",0,"12");
        ledger(5,7L,"USDT","SUCCESS",0,"8");
        ledger(6,4L,"USDT","SUCCESS",0,null);
        ledger(7,5L,"USDT","SUCCESS",0,"-3");
        ledger(8,1L,"usdt","success",0,"11");
        ledger(9,1L,"USDT","SUCCESS",1,"999");
        ledger(10,1L,"USDT","PENDING",0,"999");
        ledger(11,1L,null,"SUCCESS",0,"999");
        ledger(12,1L,"USDT",null,0,"999");
        ledger(13,null,"USDT","SUCCESS",0,"999");
        jdbc.update("UPDATE nx_wallet_ledger SET created_at='2000-01-01' WHERE id=8");
        assertThat(mapper.walletLedgerReconciliationGapUsdt()).isEqualByComparingTo("52");
        assertEquivalent();
    }

    @Test
    void caseSensitiveAssetAndStatusKeepTheirOriginalMeaning() throws Exception {
        jdbc.execute("ALTER TABLE nx_wallet_ledger MODIFY asset VARCHAR(16) COLLATE utf8mb4_bin,"
                + " MODIFY status VARCHAR(32) COLLATE utf8mb4_bin");
        migrate();
        jdbc.update("INSERT INTO nx_user_wallet VALUES (1,1,10,0)");
        ledger(1,1L,"USDT","SUCCESS",0,"8");
        ledger(2,1L,"usdt","SUCCESS",0,"999");
        ledger(3,1L,"USDT","success",0,"999");
        assertThat(mapper.walletLedgerReconciliationGapUsdt()).isEqualByComparingTo("2");
        assertEquivalent();
    }

    @Test
    void randomizedBalancesAndEligibilityMatchThePreviousQuery() throws Exception {
        migrate();
        Random random = new Random(743260);
        for (int user = 1; user <= 30; user++) {
            jdbc.update("INSERT INTO nx_user_wallet VALUES (?,?,?,?)", user,user,
                    BigDecimal.valueOf(random.nextInt(200000)-100000,6),random.nextInt(5)==0 ? 1 : 0);
        }
        String[] assets={"USDT","usdt","NEX",null};
        String[] statuses={"SUCCESS","success","PENDING","FAILED",null};
        for (int id=1; id<=600; id++) {
            ledger(id,(long)random.nextInt(45),assets[random.nextInt(assets.length)],
                    statuses[random.nextInt(statuses.length)],random.nextInt(5)==0 ? 1 : 0,
                    BigDecimal.valueOf(random.nextInt(200000)-100000,6).toPlainString());
        }
        assertEquivalent();
    }

    @Test
    void largeHistoryUsesOneSharedGroupedIndexRead() throws Exception {
        migrate();
        jdbc.execute("SET SESSION cte_max_recursion_depth=20001");
        jdbc.execute("""
                INSERT INTO nx_wallet_ledger(id,user_id,asset,status,is_deleted,balance_after,created_at)
                WITH RECURSIVE ids(n) AS (SELECT 1 UNION ALL SELECT n+1 FROM ids WHERE n<20000)
                SELECT n,MOD(n,20),'USDT','SUCCESS',0,n,NOW() FROM ids
                """);
        jdbc.execute("ANALYZE TABLE nx_wallet_ledger");
        assertEquivalent();
        String sql=configuration.getMappedStatement(TreasuryLedgerMapper.class.getName()
                + ".walletLedgerReconciliationGapUsdt").getBoundSql(null).getSql();
        String plan=jdbc.queryForObject("EXPLAIN FORMAT=JSON "+sql,String.class);
        assertThat(plan).contains(INDEX).contains("\"using_index_for_group_by\": true")
                .contains("sharing_temporary_table_with");
    }

    @Test
    void migrationIsIdempotentAndRestoresConnectionSettingsWithoutChangingRows() throws Exception {
        ledger(1,42L,"USDT","SUCCESS",0,"1.123456");
        jdbc.execute("SET SESSION lock_wait_timeout=37");
        migrate();
        migrate();
        assertThat(jdbc.queryForObject("SELECT @@session.lock_wait_timeout",Integer.class)).isEqualTo(37);
        assertThat(jdbc.queryForObject("SELECT IS_USED_LOCK(@wallet_recon_lock_name)",Long.class)).isNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_wallet_ledger",Integer.class)).isEqualTo(1);
        assertThat(mapper.walletLedgerReconciliationGapUsdt()).isEqualByComparingTo("1.123456");
        jdbc.execute("DROP INDEX "+INDEX+" ON nx_wallet_ledger");
        migrate();
        assertEquivalent();
        String startup=Files.readString(Path.of("scripts/apply_startup_schema_migrations.ps1"));
        assertThat(startup.indexOf("20260914_wallet_reconciliation_read_index.sql"))
                .isGreaterThan(startup.indexOf("20260914_polling_read_indexes.sql"));
        assertThat(startup.split("20260914_wallet_reconciliation_read_index.sql",-1)).hasSize(2);
    }

    @Test
    void wrongExistingIndexIsRejectedBeforeAlterAndCanBeRecovered() throws Exception {
        for (String definition : new String[]{"(user_id)","(user_id,status,asset,is_deleted,id)",
                "(user_id,asset,status,is_deleted,id) INVISIBLE","(user_id,asset(2),status,is_deleted,id)"}) {
            jdbc.execute("CREATE INDEX "+INDEX+" ON nx_wallet_ledger "+definition);
            assertThatThrownBy(this::migrate).hasMessageContaining("wallet_recon_stmt");
            jdbc.execute("SELECT RELEASE_ALL_LOCKS()");
            jdbc.execute("DROP INDEX "+INDEX+" ON nx_wallet_ledger");
        }
        migrate();
        assertEquivalent();
    }

    @Test
    void incompatibleSourceFailsBeforeAnyIndexIsAdded() throws Exception {
        jdbc.execute("ALTER TABLE nx_wallet_ledger MODIFY is_deleted INT");
        assertThatThrownBy(this::migrate).hasMessageContaining("wallet_recon_stmt");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.statistics"
                + " WHERE table_schema=DATABASE() AND table_name='nx_wallet_ledger' AND index_name=?",
                Integer.class,INDEX)).isZero();
    }

    private void ledger(int id,Long user,String asset,String status,int deleted,String balance) {
        jdbc.update("INSERT INTO nx_wallet_ledger VALUES (?,?,?,?,?,?,NOW())",id,user,asset,status,deleted,
                balance==null ? null : new BigDecimal(balance));
    }

    private void assertEquivalent() {
        assertThat(mapper.walletLedgerReconciliationGapUsdt())
                .isEqualByComparingTo(jdbc.queryForObject(LEGACY,BigDecimal.class));
    }

    private void migrate() throws Exception {
        assertOwned();
        ScriptUtils.executeSqlScript(connection,new FileSystemResource(MIGRATION));
    }

    private void assertOwned() {
        assertThat(database).matches("nx_wallet_recon_test_[a-f0-9]{32}");
        assertThat(jdbc.queryForObject("SELECT DATABASE()",String.class)).isEqualTo(database);
    }
}
