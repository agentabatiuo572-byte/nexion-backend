package ffdd.opsconsole.market.mapper;

import static org.assertj.core.api.Assertions.*;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import java.sql.*;
import java.util.UUID;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/** Runs the real mapper SQL only in a newly created, UUID-owned schema. */
@EnabledIfEnvironmentVariable(named="NEXION_TEST_DB_PASSWORD", matches=".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ExchangeRecoveryMySqlIntegrationTest {
    private String database;
    private Connection connection;
    private JdbcTemplate jdbc;
    private AppExchangeMapper orders;
    private AppMarketSandboxMapper sandbox;
    private boolean created;

    private Connection connect() throws SQLException {
        return DriverManager.getConnection(System.getenv().getOrDefault("NEXION_TEST_DB_URL",
                        "jdbc:mysql://127.0.0.1:3306/nexion?useSSL=false&allowPublicKeyRetrieval=true"),
                System.getenv().getOrDefault("NEXION_TEST_DB_USERNAME","root"), System.getenv("NEXION_TEST_DB_PASSWORD"));
    }

    @BeforeAll void setup() throws Exception {
        connection = connect();
        var source = new SingleConnectionDataSource(connection, true);
        jdbc = new JdbcTemplate(source);
        database = "nx_exchange_recovery_test_" + UUID.randomUUID().toString().replace("-", "");
        assertThat(database).matches("nx_exchange_recovery_test_[a-f0-9]{32}");
        jdbc.execute("CREATE DATABASE `" + database + "` CHARACTER SET utf8mb4");
        created = true;
        jdbc.execute("USE `" + database + "`");
        jdbc.execute("CREATE TABLE nx_user(id BIGINT PRIMARY KEY,status VARCHAR(32),is_deleted TINYINT,sandbox TINYINT)");
        jdbc.execute("""
                CREATE TABLE nx_exchange_order(id BIGINT PRIMARY KEY,user_id BIGINT,exchange_no VARCHAR(64),
                from_asset VARCHAR(16),to_asset VARCHAR(16),from_amount DECIMAL(36,6),to_amount DECIMAL(36,6),
                rate DECIMAL(36,6),status VARCHAR(32),created_at DATETIME,is_deleted TINYINT,
                UNIQUE KEY(exchange_no)) ENGINE=InnoDB
                """);
        jdbc.execute("""
                CREATE TABLE nx_exchange_sandbox_order(id BIGINT PRIMARY KEY,run_id VARCHAR(96),user_id BIGINT,
                exchange_no VARCHAR(64),idempotency_key VARCHAR(128),request_hash VARCHAR(64),
                from_asset VARCHAR(16),to_asset VARCHAR(16),from_amount DECIMAL(36,6),to_amount DECIMAL(36,6),
                rate DECIMAL(36,6),status VARCHAR(32),created_at DATETIME,
                UNIQUE KEY(run_id,user_id,idempotency_key)) ENGINE=InnoDB
                """);
        var configuration = new Configuration(new Environment("exchange-recovery-read", new SpringManagedTransactionFactory(), source));
        configuration.addMapper(AppExchangeMapper.class);
        configuration.addMapper(AppMarketSandboxMapper.class);
        var session = new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(configuration));
        orders = session.getMapper(AppExchangeMapper.class);
        sandbox = session.getMapper(AppMarketSandboxMapper.class);
    }

    @BeforeEach void seed() {
        assertOwned();
        jdbc.update("DELETE FROM nx_exchange_order");
        jdbc.update("DELETE FROM nx_exchange_sandbox_order");
        jdbc.update("DELETE FROM nx_user");
        jdbc.update("INSERT INTO nx_user VALUES(7,'ACTIVE',0,0),(8,'ACTIVE',0,0),(9,'ACTIVE',0,1),(10,'DISABLED',0,0),(11,'ACTIVE',1,0)");
        for (int user = 7; user <= 11; user++) {
            jdbc.update("INSERT INTO nx_exchange_order VALUES(?,? ,?,'USDT','NEX',20,100,0.2,'QUEUED',NOW(),0)", user,user,"EX-"+user);
        }
        jdbc.update("INSERT INTO nx_exchange_order VALUES(12,7,'EX-deleted','USDT','NEX',20,100,0.2,'QUEUED',NOW(),1)");
        jdbc.update("INSERT INTO nx_exchange_sandbox_order VALUES(1,'run-a',9,'EX-sbx-1','same-key','hash-a','USDT','NEX',20,100,0.2,'QUEUED',NOW()),"
                + "(2,'run-b',9,'EX-sbx-2','same-key','hash-b','USDT','NEX',20,100,0.2,'CANCELLED',NOW()),"
                + "(3,'run-a',12,'EX-sbx-3','same-key','hash-c','USDT','NEX',20,100,0.2,'COMPLETED',NOW())");
    }

    @Test void exactOrderCannotLeakAnotherOwnerInactiveDeletedOrSandboxUser() {
        assertThat(orders.recoveryOrder(7L,"EX-7").status()).isEqualTo("QUEUED");
        assertThat(orders.recoveryOrder(7L,"EX-8")).isNull();
        assertThat(orders.recoveryOrder(9L,"EX-9")).isNull();
        assertThat(orders.recoveryOrder(10L,"EX-10")).isNull();
        assertThat(orders.recoveryOrder(11L,"EX-11")).isNull();
        assertThat(orders.recoveryOrder(7L,"EX-deleted")).isNull();
        assertThat(orders.recoveryOrder(7L,"unknown")).isNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_exchange_order",Integer.class)).isEqualTo(6);
    }

    @Test void sandboxUsesAllThreeKeyPartsWithNoWalletTableOrInitialization() {
        assertThat(sandbox.exchangeRecoveryByKey("run-a",9L,"same-key").exchangeNo()).isEqualTo("EX-sbx-1");
        assertThat(sandbox.exchangeRecoveryByKey("run-b",9L,"same-key").exchangeNo()).isEqualTo("EX-sbx-2");
        assertThat(sandbox.exchangeRecoveryByKey("run-a",12L,"same-key").exchangeNo()).isEqualTo("EX-sbx-3");
        assertThat(sandbox.exchangeRecoveryByKey("run-a",7L,"same-key")).isNull();
        assertThat(sandbox.exchangeRecoveryByKey("run-c",9L,"same-key")).isNull();
        assertThat(sandbox.exchangeRecoveryByKey("run-a",9L,"other-key")).isNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_exchange_sandbox_order",Integer.class)).isEqualTo(3);
    }

    @Test void canonicalAndSandboxReadsDoNotWaitForInFlightOrderWritesOrSeeUncommittedState() throws Exception {
        assertOwned();
        try (Connection writer = connect()) {
            writer.setCatalog(database);
            writer.setAutoCommit(false);
            try (Statement statement = writer.createStatement()) {
            try (ResultSet current = statement.executeQuery("SELECT DATABASE()")) {
                assertThat(current.next()).isTrue();
                assertThat(current.getString(1)).isEqualTo(database);
            }
            statement.executeUpdate("UPDATE `"+database+"`.nx_exchange_order SET status='CANCELLED' WHERE id=7");
            statement.executeUpdate("UPDATE `"+database+"`.nx_exchange_sandbox_order SET status='CANCELLED' WHERE id=1");
            jdbc.execute("SET SESSION innodb_lock_wait_timeout=1");
            assertThat(orders.recoveryOrder(7L,"EX-7").status()).isEqualTo("QUEUED");
            assertThat(sandbox.exchangeRecoveryByKey("run-a",9L,"same-key").status()).isEqualTo("QUEUED");
            writer.commit();
            assertThat(orders.recoveryOrder(7L,"EX-7").status()).isEqualTo("CANCELLED");
            assertThat(sandbox.exchangeRecoveryByKey("run-a",9L,"same-key").status()).isEqualTo("CANCELLED");
            }
        }
    }

    private void assertOwned() {
        assertThat(database).matches("nx_exchange_recovery_test_[a-f0-9]{32}");
        assertThat(jdbc.queryForObject("SELECT DATABASE()",String.class)).isEqualTo(database);
    }

    @AfterAll void cleanup() throws Exception {
        try { if (created) { assertOwned(); jdbc.execute("DROP DATABASE `"+database+"`"); } }
        finally { if (connection != null) connection.close(); }
    }
}
