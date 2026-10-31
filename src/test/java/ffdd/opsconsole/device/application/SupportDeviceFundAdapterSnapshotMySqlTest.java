package ffdd.opsconsole.device.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.device.facade.SupportDeviceReadFacade;
import ffdd.opsconsole.device.facade.SupportDeviceReadFacade.FundEarnings;
import ffdd.opsconsole.device.mapper.SupportDeviceReadMapper;
import ffdd.opsconsole.finance.facade.SupportFundJournalFacade;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/** Real RR/owner-lock/mapper/adapter regression in a new disposable schema; no business rows are read. */
@EnabledIfEnvironmentVariable(named="NEXION_TEST_DB_PASSWORD", matches=".+")
class SupportDeviceFundAdapterSnapshotMySqlTest {
    @Test void waitingDeviceUsesTheCommittedWalletDespiteItsEarlierRepeatableReadSnapshot() throws Exception {
        String source = System.getenv().getOrDefault("NEXION_TEST_DB_URL",
                "jdbc:mysql://127.0.0.1:3306/nexion?useSSL=false&allowPublicKeyRetrieval=true");
        String user = System.getenv().getOrDefault("NEXION_TEST_DB_USERNAME", "root");
        String password = System.getenv("NEXION_TEST_DB_PASSWORD");
        assertThat(source).matches("^jdbc:mysql://(127\\.0\\.0\\.1|localhost|\\[::1\\])(?::[0-9]{1,5})?/[^?]+(?:\\?.*)?$");
        String database = "nx_device_cash_snapshot_" + UUID.randomUUID().toString().replace("-", "");
        int slash = source.indexOf('/', "jdbc:mysql://".length()), query = source.indexOf('?', slash);
        String url = source.substring(0, slash + 1) + database + (query < 0 ? "" : source.substring(query));
        try (Connection admin = DriverManager.getConnection(source, user, password)) {
            admin.createStatement().execute("CREATE DATABASE " + database);
            try (Connection first = DriverManager.getConnection(url, user, password);
                 Connection second = DriverManager.getConnection(url, user, password)) {
                createFixture(first);
                first.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
                first.setAutoCommit(false);
                lockOwner(first);
                first.createStatement().executeUpdate("UPDATE nx_user_wallet SET usdt_available=usdt_available+0.3,version=version+1,fund_revision=fund_revision+1 WHERE user_id=7");
                first.createStatement().executeUpdate("INSERT INTO nx_compute_receipt VALUES ('CTR-DEVICE-A',7,11,0.3)");
                var snapshotOpened = new CountDownLatch(1);
                var attemptedOwnerLock = new CountDownLatch(1);
                var executor = Executors.newSingleThreadExecutor();
                try {
                    var waiting = executor.submit(() -> {
                        second.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
                        second.setAutoCommit(false);
                        // Models requireCurrent's ordinary read before lockBinding waits on the owner.
                        assertThat(ordinaryBalance(second)).isEqualByComparingTo("10.000000");
                        snapshotOpened.countDown(); attemptedOwnerLock.countDown();
                        lockOwner(second);
                        // Taking the owner lock must not be mistaken for refreshing an RR read view.
                        assertThat(ordinaryBalance(second)).isEqualByComparingTo("10.000000");
                        var configuration = new Configuration(new Environment("device-cash-snapshot",
                                new JdbcTransactionFactory(), new SingleConnectionDataSource(second, true)));
                        configuration.addMapper(SupportDeviceReadMapper.class);
                        try (var session = new SqlSessionFactoryBuilder().build(configuration).openSession()) {
                            var mapper = session.getMapper(SupportDeviceReadMapper.class);
                            var fund = mock(SupportFundJournalFacade.class);
                            var binding = mock(SupportFundJournalFacade.Before.class);
                            // The real journal binder already uses a wallet FOR UPDATE read.
                            when(fund.begin(List.of(7L))).thenAnswer(invocation -> {
                                second.createStatement().executeQuery("SELECT id FROM nx_user_wallet WHERE user_id=7 FOR UPDATE").close();
                                return binding;
                            });
                            var sources = mock(SupportDeviceReadFacade.class);
                            when(sources.readFundEarning(7L,"CTR-DEVICE-B")).thenReturn(new FundEarnings(
                                    List.of(Map.of("receiptNo", "CTR-DEVICE-B", "taskNo", "CTA-DEVICE-B",
                                            "deviceId", 12L, "rewardUsdt", new BigDecimal("0.300000"),
                                            "rewardNex", BigDecimal.ZERO, "completedAt", LocalDateTime.of(2026,10,30,20,0))),
                                    List.of(), List.of(), List.of()));
                            var proof = new java.util.concurrent.atomic.AtomicReference<JsonNode>();
                            var json = new ObjectMapper();
                            when(fund.bind(eq(binding), any())).thenAnswer(invocation -> {
                                var input = invocation.<SupportFundJournalFacade.ReceiptInput>getArgument(1);
                                proof.set(json.readTree(input.sourceProofJson()));
                                return new SupportFundJournalFacade.BoundReceipt(1, input.operationKey(), "test-proof", 1);
                            });
                            var adapter = new SupportDeviceFundAdapter(fund, mapper, sources, json);
                            var before = adapter.begin(7);
                            second.createStatement().executeUpdate("UPDATE nx_user_wallet SET usdt_available=usdt_available+0.3,version=version+1,fund_revision=fund_revision+1 WHERE user_id=7");
                            second.createStatement().executeUpdate("INSERT INTO nx_compute_receipt VALUES ('CTR-DEVICE-B',7,12,0.3)");
                            adapter.afterUsdt(before);
                            adapter.recorded(before, "CTR-DEVICE-B");
                            verify(fund).bind(eq(binding), any());
                            second.commit();
                            return proof.get();
                        }
                    });
                    assertThat(snapshotOpened.await(5, TimeUnit.SECONDS)).isTrue();
                    assertThat(attemptedOwnerLock.await(5, TimeUnit.SECONDS)).isTrue();
                    assertThatThrownBy(() -> waiting.get(250, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
                    first.commit();
                    var stages = waiting.get(10, TimeUnit.SECONDS).path("walletStages");
                    assertThat(stages.size()).isEqualTo(2);
                    assertThat(stages.get(0).path("usdtAvailable").decimalValue()).isEqualByComparingTo("10.300000");
                    assertThat(stages.get(1).path("usdtAvailable").decimalValue()).isEqualByComparingTo("10.600000");
                    assertThat(stages.get(0).path("revision").longValue()).isEqualTo(2);
                    assertThat(stages.get(1).path("revision").longValue()).isEqualTo(3);
                    assertThat(ordinaryBalance(admin, database)).isEqualByComparingTo("10.600000");
                    try (var rows = admin.createStatement().executeQuery("SELECT COUNT(*),COUNT(DISTINCT user_device_id) FROM " + database + ".nx_compute_receipt")) {
                        assertThat(rows.next()).isTrue();
                        assertThat(rows.getInt(1)).isEqualTo(2); assertThat(rows.getInt(2)).isEqualTo(2);
                    }
                } finally {
                    first.rollback(); executor.shutdownNow(); executor.awaitTermination(5, TimeUnit.SECONDS);
                }
            } finally {
                admin.createStatement().execute("DROP DATABASE " + database);
            }
        }
    }

    private static void lockOwner(Connection connection) throws Exception {
        try (var rows = connection.createStatement().executeQuery("SELECT id FROM nx_user WHERE id=7 FOR UPDATE")) {
            assertThat(rows.next()).isTrue();
        }
    }
    private static BigDecimal ordinaryBalance(Connection connection) throws Exception {
        return ordinaryBalance(connection, null);
    }
    private static BigDecimal ordinaryBalance(Connection connection, String database) throws Exception {
        try (var rows = connection.createStatement().executeQuery("SELECT usdt_available FROM "
                + (database == null ? "" : database + ".") + "nx_user_wallet WHERE user_id=7")) {
            assertThat(rows.next()).isTrue(); return rows.getBigDecimal(1);
        }
    }
    private static void createFixture(Connection connection) throws Exception {
        var sql = connection.createStatement();
        sql.execute("CREATE TABLE nx_user(id BIGINT PRIMARY KEY) ENGINE=InnoDB");
        sql.execute("INSERT INTO nx_user VALUES (7)");
        sql.execute("CREATE TABLE nx_user_wallet(id BIGINT PRIMARY KEY,user_id BIGINT UNIQUE,fund_incarnation VARCHAR(64),fund_revision BIGINT,version BIGINT,usdt_available DECIMAL(18,6),nex_available DECIMAL(18,6),cregis_risk_held DECIMAL(18,6),pending_withdraw DECIMAL(18,6),sandbox INT,is_deleted INT,created_at DATETIME) ENGINE=InnoDB");
        sql.execute("INSERT INTO nx_user_wallet VALUES (9,7,'3e74451d-7410-44c1-85d0-f5bd52d31130',1,11,10,20,0,0,0,0,'2026-10-29 20:00:00')");
        sql.execute("CREATE TABLE nx_compute_receipt(receipt_no VARCHAR(96) PRIMARY KEY,user_id BIGINT,user_device_id BIGINT,reward_usdt DECIMAL(18,6)) ENGINE=InnoDB");
    }
}
