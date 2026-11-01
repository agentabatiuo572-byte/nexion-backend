package ffdd.opsconsole.risk.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import ffdd.opsconsole.risk.mapper.RiskOpsMapper;
import ffdd.opsconsole.shared.seed.OpsReadTimeSeedPolicy;
import java.sql.Connection;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Synthetic, disposable schema only; the runner supplies an already isolated MySQL. */
@EnabledIfEnvironmentVariable(named = "NEXION_RISK_E3_LOCK_IT", matches = "true")
class RiskE3ProjectionMySqlIntegrationTest {
    @Test
    void rrProjectionKeepsFactsAndDispositionWithoutBlockingConcurrentLedgerInsert() throws Exception {
        String endpoint = System.getenv("NEXION_ISOLATED_MYSQL_ENDPOINT");
        assertThat(endpoint).isEqualTo("127.0.0.1:13307");
        String password = System.getenv().getOrDefault("NEXION_ISOLATED_MYSQL_PASSWORD", "");
        String schema = "nx_risk_e3_it_" + UUID.randomUUID().toString().replace("-", "");
        String base = "jdbc:mysql://" + endpoint + "/";
        String options = "?useSSL=false&allowPublicKeyRetrieval=true&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true";
        JdbcTemplate admin = new JdbcTemplate(new DriverManagerDataSource(base + options, "root", password));
        assertThat(admin.queryForObject("SELECT @@port", Integer.class)).isEqualTo(13307);
        admin.execute("CREATE DATABASE " + schema);
        try {
            var source = new DriverManagerDataSource(base + schema + options, "root", password);
            JdbcTemplate jdbc = new JdbcTemplate(source);
            seed(jdbc);
            MybatisConfiguration configuration = new MybatisConfiguration();
            configuration.setMapUnderscoreToCamelCase(true);
            var factory = new MybatisSqlSessionFactoryBean();
            factory.setDataSource(source);
            factory.setConfiguration(configuration);
            var sessions = factory.getObject();
            sessions.getConfiguration().addMapper(RiskOpsMapper.class);
            var mapper = new SqlSessionTemplate(sessions).getMapper(RiskOpsMapper.class);
            var repository = new MybatisRiskOpsRepository(mapper, OpsReadTimeSeedPolicy.disabledForDirectConstruction());
            var transaction = new TransactionTemplate(new DataSourceTransactionManager(source));
            transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
            var writer = Executors.newSingleThreadExecutor();
            try {
                transaction.executeWithoutResult(status -> {
                    assertThat(jdbc.queryForObject("SELECT @@transaction_isolation", String.class)).isEqualTo("REPEATABLE-READ");
                    repository.refreshE3TradeinArbitrageProjection();
                    assertProjection(jdbc);
                    // Keep the scanning transaction open while another connection inserts into its scanned user range.
                    var inserted = writer.submit(() -> {
                        try (Connection connection = source.getConnection(); var statement = connection.createStatement()) {
                            statement.execute("SET innodb_lock_wait_timeout=1");
                            return statement.executeUpdate("INSERT INTO nx_wallet_ledger(user_id,biz_type,status,direction,amount,is_deleted,created_at) VALUES(100000007,'COMPUTE_TASK_REWARD','SUCCESS','IN',1,0,NOW())");
                        }
                    });
                    try {
                        assertThat(inserted.get(3, TimeUnit.SECONDS)).isEqualTo(1);
                    } catch (Exception failure) {
                        throw new AssertionError("E3 projection must not retain source-ledger row/gap locks", failure);
                    }
                    // RR retains the original snapshot even though the independent ledger writer committed.
                    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_wallet_ledger WHERE user_id=100000007 AND biz_type='COMPUTE_TASK_REWARD'", Integer.class)).isZero();
                    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM performance_schema.data_locks WHERE OBJECT_SCHEMA=? AND OBJECT_NAME IN ('nx_wallet_ledger','nx_commission_event','nx_tradein_application') AND LOCK_TYPE='RECORD'", Integer.class, schema)).isZero();
                });
                transaction.executeWithoutResult(status -> repository.refreshE3TradeinArbitrageProjection());
                assertProjection(jdbc);
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_admin_risk_arbitrage_row", Integer.class)).isEqualTo(3);
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_wallet_ledger WHERE biz_type='COMPUTE_TASK_REWARD'", Integer.class)).isEqualTo(1);
            } finally {
                writer.shutdownNow();
                assertThat(writer.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
            }
        } finally {
            admin.execute("DROP DATABASE " + schema);
        }
    }

    private static void assertProjection(JdbcTemplate jdbc) {
        assertThat(jdbc.queryForList("SELECT row_id FROM nx_admin_risk_arbitrage_row WHERE is_deleted=0 ORDER BY row_id", String.class))
                .containsExactly("K2-E3-U100000007", "K2-E3-U12");
        var existing = jdbc.queryForMap("SELECT * FROM nx_admin_risk_arbitrage_row WHERE row_id='K2-E3-U100000007'");
        assertThat(existing).containsEntry("view_key", "tradein").containsEntry("cluster_id", null)
                .containsEntry("cell1", "U100000007").containsEntry("cell2", "3 次置换")
                .containsEntry("cell3", "近 30 天").containsEntry("cell4", "3 次高频下架置换")
                .containsEntry("cell5", "1 笔返佣 / 1 笔礼金").containsEntry("cell6", null)
                .containsEntry("level_value", 3).containsEntry("actions_csv", "flag")
                .containsEntry("disposition", "flagged").containsEntry("version", 7L);
        assertThat(jdbc.queryForObject("SELECT is_deleted FROM nx_admin_risk_arbitrage_row WHERE row_id='K2-E3-U99'", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT cell5 FROM nx_admin_risk_arbitrage_row WHERE row_id='K2-E3-U12'", String.class)).isEqualTo("1 笔礼金");
    }

    private static void seed(JdbcTemplate jdbc) {
        jdbc.execute("CREATE TABLE nx_tradein_application(id BIGINT PRIMARY KEY,user_id BIGINT,status VARCHAR(32),completed_at DATETIME,is_deleted TINYINT,KEY idx_tradein_user(user_id)) ENGINE=InnoDB");
        jdbc.execute("CREATE TABLE nx_commission_event(id BIGINT PRIMARY KEY,user_id BIGINT,status VARCHAR(32),amount_usdt DECIMAL(24,8),created_at DATETIME,is_deleted TINYINT,KEY idx_commission_user_time(user_id,created_at)) ENGINE=InnoDB");
        jdbc.execute("CREATE TABLE nx_wallet_ledger(id BIGINT AUTO_INCREMENT PRIMARY KEY,user_id BIGINT,biz_type VARCHAR(64),status VARCHAR(32),direction VARCHAR(8),amount DECIMAL(24,8),created_at DATETIME,is_deleted TINYINT,KEY idx_wallet_ledger_user_time(user_id,created_at)) ENGINE=InnoDB");
        jdbc.execute("""
                CREATE TABLE nx_admin_risk_arbitrage_row(
                  id BIGINT AUTO_INCREMENT PRIMARY KEY,row_id VARCHAR(64) UNIQUE,view_key VARCHAR(32),cluster_id VARCHAR(64),
                  cell1 VARCHAR(255),cell2 VARCHAR(255),cell3 VARCHAR(255),cell4 VARCHAR(255),cell5 VARCHAR(255),cell6 VARCHAR(255),
                  level_value INT,actions_csv VARCHAR(64),disposition VARCHAR(64),version BIGINT DEFAULT 0,
                  is_deleted TINYINT DEFAULT 0,created_at DATETIME DEFAULT CURRENT_TIMESTAMP,updated_at DATETIME DEFAULT CURRENT_TIMESTAMP
                ) ENGINE=InnoDB
                """);
        long id = 1;
        for (long user : List.of(100000007L, 12L, 13L, 14L, 15L)) {
            for (int count = 0; count < (user == 14L ? 2 : 3); count++) {
                jdbc.update("INSERT INTO nx_tradein_application VALUES(?,?,'COMPLETED',DATE_SUB(NOW(),INTERVAL 1 DAY),0)", id++, user);
            }
        }
        jdbc.update("INSERT INTO nx_tradein_application VALUES(?,100000007,'COMPLETED',DATE_SUB(NOW(),INTERVAL 31 DAY),0)", id++);
        jdbc.update("INSERT INTO nx_tradein_application VALUES(?,100000007,'PENDING',NOW(),0)", id++);
        jdbc.update("INSERT INTO nx_tradein_application VALUES(?,100000007,'COMPLETED',NOW(),1)", id);
        jdbc.execute("INSERT INTO nx_commission_event VALUES(1,100000007,'SUCCESS',1,NOW(),0),(2,13,'CANCELLED',1,NOW(),0),(3,15,'SUCCESS',0,NOW(),0),(4,15,'SUCCESS',1,DATE_SUB(NOW(),INTERVAL 31 DAY),0)");
        jdbc.execute("""
                INSERT INTO nx_wallet_ledger(user_id,biz_type,status,direction,amount,created_at,is_deleted) VALUES
                (100000007,'WELCOME_GIFT','SUCCESS','IN',1,NOW(),0),(12,'WELCOME_GIFT','POSTED','IN',1,NOW(),0),
                (14,'WELCOME_GIFT','SUCCESS','IN',1,NOW(),0),(13,'WELCOME_GIFT','SUCCESS','OUT',1,NOW(),0),
                (15,'WELCOME_GIFT','SUCCESS','IN',0,NOW(),0),(15,'WELCOME_GIFT','FAILED','IN',1,NOW(),0),
                (15,'WELCOME_GIFT','SUCCESS','IN',1,NOW(),1),(15,'WELCOME_GIFT','SUCCESS','IN',1,DATE_SUB(NOW(),INTERVAL 31 DAY),0)
                """);
        jdbc.execute("INSERT INTO nx_admin_risk_arbitrage_row(row_id,disposition,version,is_deleted) VALUES('K2-E3-U100000007','flagged',7,1),('K2-E3-U99',NULL,0,0)");
    }
}
