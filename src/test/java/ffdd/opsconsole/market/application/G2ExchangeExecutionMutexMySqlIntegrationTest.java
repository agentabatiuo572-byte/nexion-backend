package ffdd.opsconsole.market.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

@EnabledIfEnvironmentVariable(named = "NEXION_TEST_DB_PASSWORD", matches = ".+")
class G2ExchangeExecutionMutexMySqlIntegrationTest {
    private static final String LOCK_KEY = "G2_EXCHANGE_EXECUTION";

    @Test
    void sharedMysqlRowSerializesIndependentAppAndBatchTransactions() throws Exception {
        DataSource dataSource = dataSource();
        ensureMutexRow(dataSource);
        CountDownLatch batchAttempted = new CountDownLatch(1);
        AtomicBoolean batchAcquired = new AtomicBoolean();

        try (Connection appTransaction = dataSource.getConnection()) {
            appTransaction.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            appTransaction.setAutoCommit(false);
            assertThat(lock(appTransaction)).isEqualTo(LOCK_KEY);

            var executor = Executors.newSingleThreadExecutor();
            try {
                var batchFuture = executor.submit(() -> {
                    try (Connection batchTransaction = dataSource.getConnection()) {
                        batchTransaction.setAutoCommit(false);
                        batchAttempted.countDown();
                        String value = lock(batchTransaction);
                        batchAcquired.set(true);
                        batchTransaction.commit();
                        return value;
                    }
                });

                assertThat(batchAttempted.await(2, TimeUnit.SECONDS)).isTrue();
                Thread.sleep(250);
                assertThat(batchAcquired).as("batch must wait while App holds the shared cap row").isFalse();

                appTransaction.commit();
                assertThat(batchFuture.get(3, TimeUnit.SECONDS)).isEqualTo(LOCK_KEY);
                assertThat(batchAcquired).isTrue();
            } finally {
                executor.shutdownNow();
            }
        }
    }

    @Test
    void sharedMysqlRowKeepsConcurrentAppAndBatchAdmissionWithinPlatformCap() throws Exception {
        DataSource dataSource = dataSource();
        ensureMutexRow(dataSource);
        String probeTable = "nx_g2_cap_probe_"
                + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        ensureCapProbe(dataSource, probeTable);
        BigDecimal amount = new BigDecimal("10.000000");
        BigDecimal cap = new BigDecimal("10.000000");
        CountDownLatch batchAttempted = new CountDownLatch(1);

        try (Connection appTransaction = dataSource.getConnection()) {
            appTransaction.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            appTransaction.setAutoCommit(false);
            assertThat(lock(appTransaction)).isEqualTo(LOCK_KEY);
            assertThat(used(appTransaction, probeTable)).isEqualByComparingTo(BigDecimal.ZERO);

            var executor = Executors.newSingleThreadExecutor();
            try {
                var batchAccepted = executor.submit(() -> attemptAdmission(
                        dataSource, probeTable, amount, cap, batchAttempted));
                assertThat(batchAttempted.await(2, TimeUnit.SECONDS)).isTrue();
                Thread.sleep(250);
                assertThat(batchAccepted.isDone()).as("batch admission must wait for App cap reservation").isFalse();

                insertUsage(appTransaction, probeTable, "APP", amount);
                appTransaction.commit();

                assertThat(batchAccepted.get(3, TimeUnit.SECONDS)).isFalse();
                assertThat(totalUsed(dataSource, probeTable)).isEqualByComparingTo(cap);
            } finally {
                executor.shutdownNow();
            }
        } finally {
            dropProbeTable(dataSource, probeTable);
        }
    }

    private String lock(Connection connection) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT lock_key FROM nx_admin_operation_mutex WHERE lock_key=? FOR UPDATE")) {
            statement.setString(1, LOCK_KEY);
            try (ResultSet result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
                return result.getString(1);
            }
        }
    }

    private void ensureMutexRow(DataSource dataSource) throws Exception {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS nx_admin_operation_mutex (
                      lock_key VARCHAR(64) PRIMARY KEY,
                      updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                    """);
            statement.executeUpdate("INSERT IGNORE INTO nx_admin_operation_mutex(lock_key) VALUES ('" + LOCK_KEY + "')");
        }
    }

    private boolean attemptAdmission(DataSource dataSource, String probeTable, BigDecimal amount,
                                     BigDecimal cap, CountDownLatch attempted) throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            connection.setAutoCommit(false);
            attempted.countDown();
            lock(connection);
            boolean accepted = used(connection, probeTable).add(amount).compareTo(cap) <= 0;
            if (accepted) insertUsage(connection, probeTable, "BATCH", amount);
            connection.commit();
            return accepted;
        }
    }

    private BigDecimal used(Connection connection, String probeTable) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COALESCE(SUM(amount_usdt),0) FROM " + probeTable)) {
            try (ResultSet result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
                return result.getBigDecimal(1);
            }
        }
    }

    private void insertUsage(Connection connection, String probeTable, String source, BigDecimal amount) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO " + probeTable + "(source,amount_usdt) VALUES (?,?)")) {
            statement.setString(1, source);
            statement.setBigDecimal(2, amount);
            assertThat(statement.executeUpdate()).isEqualTo(1);
        }
    }

    private BigDecimal totalUsed(DataSource dataSource, String probeTable) throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            return used(connection, probeTable);
        }
    }

    private void ensureCapProbe(DataSource dataSource, String probeTable) throws Exception {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE %s (
                      id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
                      source VARCHAR(16) NOT NULL,
                      amount_usdt DECIMAL(18,6) NOT NULL,
                      created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                    """.formatted(probeTable));
        }
    }

    private void dropProbeTable(DataSource dataSource, String probeTable) throws Exception {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("DROP TABLE IF EXISTS " + probeTable);
        }
    }

    private DataSource dataSource() {
        String url = System.getenv().getOrDefault("NEXION_TEST_DB_URL",
                "jdbc:mysql://127.0.0.1:3306/nexion?useUnicode=true&characterEncoding=utf8"
                        + "&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true");
        String username = System.getenv().getOrDefault("NEXION_TEST_DB_USERNAME", "root");
        return new DriverManagerDataSource(url, username, System.getenv("NEXION_TEST_DB_PASSWORD"));
    }
}
