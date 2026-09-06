package ffdd.opsconsole.finance.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** Real two-transaction lock check in a disposable schema, never the application database. */
@EnabledIfEnvironmentVariable(named = "NEXION_WITHDRAW_LOCK_IT", matches = "true")
class EarningsWithdrawalMySqlLockTest {
    @Test
    void currentReadObservesCommittedFreezeAndPreventsFreezeCommitDuringReservation() throws Exception {
        String schema = "nexion_withdraw_lock_it_" + UUID.randomUUID().toString().replace("-", "");
        if (!schema.matches("nexion_withdraw_lock_it_[0-9a-f]{32}")) throw new IllegalStateException("unsafe test schema");
        String base = "jdbc:mysql://127.0.0.1:3306/";
        String options = "?useSSL=false&allowPublicKeyRetrieval=true&characterEncoding=utf8";
        String password = System.getenv("NEXION_DB_PASSWORD");
        try (Connection admin = DriverManager.getConnection(base + options, "root", password)) {
            admin.createStatement().execute("CREATE DATABASE " + schema);
            try (Connection first = DriverManager.getConnection(base + schema + options, "root", password);
                 Connection second = DriverManager.getConnection(base + schema + options, "root", password)) {
                first.createStatement().execute("CREATE TABLE nx_user (id BIGINT PRIMARY KEY,is_deleted INT) ENGINE=InnoDB");
                first.createStatement().execute("CREATE TABLE nx_admin_risk_multi_account_cluster (id BIGINT PRIMARY KEY,cluster_id VARCHAR(32) UNIQUE,account_count INT,status VARCHAR(32),nodes_json JSON,is_deleted INT) ENGINE=InnoDB");
                first.createStatement().execute("INSERT INTO nx_user VALUES (7,0)");
                first.createStatement().execute("INSERT INTO nx_admin_risk_multi_account_cluster VALUES (1,'C1',1,'cleared','[{\"userNo\":\"U00000007\"}]',0)");
                first.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
                first.setAutoCommit(false);
                assertThat(status(first, "riskCluster")).isEqualTo("cleared");
                second.createStatement().executeUpdate("UPDATE nx_admin_risk_multi_account_cluster SET status='frozen' WHERE cluster_id='C1'");
                // A normal repeatable-read snapshot is stale, but final locking read is current.
                assertThat(status(first, "riskCluster")).isEqualTo("cleared");
                assertThat(status(first, "lockRiskCluster")).isEqualTo("frozen");
                first.rollback();
                second.createStatement().executeUpdate("UPDATE nx_admin_risk_multi_account_cluster SET status='cleared' WHERE cluster_id='C1'");
                assertThat(status(first, "lockRiskCluster")).isEqualTo("cleared");
                var executor = Executors.newSingleThreadExecutor();
                CountDownLatch attemptingFreeze = new CountDownLatch(1);
                try {
                    var freeze = executor.submit(() -> {
                        attemptingFreeze.countDown();
                        return second.createStatement().executeUpdate("UPDATE nx_admin_risk_multi_account_cluster SET status='frozen' WHERE cluster_id='C1'");
                    });
                    assertThat(attemptingFreeze.await(2, TimeUnit.SECONDS)).isTrue();
                    assertThatThrownBy(() -> freeze.get(250, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
                    // Models the reservation transaction committing while retaining the cluster lock.
                    first.commit();
                    assertThat(freeze.get(5, TimeUnit.SECONDS)).isEqualTo(1);
                    assertThat(status(first, "lockRiskCluster")).isEqualTo("frozen");
                } finally {
                    first.rollback();
                    executor.shutdownNow();
                    executor.awaitTermination(5, TimeUnit.SECONDS);
                }
            } finally {
                // Only the unique schema created above is removed; no application table is touched.
                admin.createStatement().execute("DROP DATABASE " + schema);
            }
        }
    }

    @Test
    void k1UserThenClusterCasSerializesBeforeWithdrawalUserWalletAndClusterLocks() throws Exception {
        String schema = schema();
        String base = "jdbc:mysql://127.0.0.1:3306/";
        String options = "?useSSL=false&allowPublicKeyRetrieval=true&characterEncoding=utf8";
        String password = System.getenv("NEXION_DB_PASSWORD");
        try (Connection admin = DriverManager.getConnection(base + options, "root", password)) {
            admin.createStatement().execute("CREATE DATABASE " + schema);
            try (Connection k1 = DriverManager.getConnection(base + schema + options, "root", password);
                 Connection withdrawal = DriverManager.getConnection(base + schema + options, "root", password)) {
                createFundsFixture(k1);
                k1.setAutoCommit(false);
                lockUser(k1);
                var executor = Executors.newSingleThreadExecutor();
                CountDownLatch attemptedUserLock = new CountDownLatch(1);
                try {
                    var reserve = executor.submit(() -> {
                        withdrawal.setAutoCommit(false);
                        attemptedUserLock.countDown();
                        lockUser(withdrawal);
                        lockWallet(withdrawal);
                        return status(withdrawal, "lockRiskCluster");
                    });
                    assertThat(attemptedUserLock.await(2, TimeUnit.SECONDS)).isTrue();
                    assertThatThrownBy(() -> reserve.get(250, TimeUnit.MILLISECONDS))
                            .isInstanceOf(TimeoutException.class);
                    assertThat(k1.createStatement().executeUpdate("UPDATE nx_admin_risk_multi_account_cluster "
                            + "SET status='frozen',version=version+1 WHERE cluster_id='C1' AND status='cleared' AND version=0"))
                            .isEqualTo(1);
                    k1.commit();
                    assertThat(reserve.get(5, TimeUnit.SECONDS)).isEqualTo("frozen");
                } finally {
                    k1.rollback();
                    withdrawal.rollback();
                    executor.shutdownNow();
                    executor.awaitTermination(5, TimeUnit.SECONDS);
                }
            } finally {
                admin.createStatement().execute("DROP DATABASE " + schema);
            }
        }
    }

    @Test
    void rewardUserWalletThenEntrySerializesBeforeWithdrawalCanLockProtectedEntries() throws Exception {
        String schema = schema();
        String base = "jdbc:mysql://127.0.0.1:3306/";
        String options = "?useSSL=false&allowPublicKeyRetrieval=true&characterEncoding=utf8";
        String password = System.getenv("NEXION_DB_PASSWORD");
        try (Connection admin = DriverManager.getConnection(base + options, "root", password)) {
            admin.createStatement().execute("CREATE DATABASE " + schema);
            try (Connection credit = DriverManager.getConnection(base + schema + options, "root", password);
                 Connection withdrawal = DriverManager.getConnection(base + schema + options, "root", password)) {
                createFundsFixture(credit);
                credit.setAutoCommit(false);
                lockUser(credit);
                lockWallet(credit);
                var executor = Executors.newSingleThreadExecutor();
                CountDownLatch attemptedUserLock = new CountDownLatch(1);
                try {
                    var reserve = executor.submit(() -> {
                        withdrawal.setAutoCommit(false);
                        attemptedUserLock.countDown();
                        lockUser(withdrawal);
                        lockWallet(withdrawal);
                        return protectedEntryCountForUpdate(withdrawal);
                    });
                    assertThat(attemptedUserLock.await(2, TimeUnit.SECONDS)).isTrue();
                    assertThatThrownBy(() -> reserve.get(250, TimeUnit.MILLISECONDS))
                            .isInstanceOf(TimeoutException.class);
                    credit.createStatement().executeUpdate("INSERT INTO nx_earnings_release_entry "
                            + "(user_id,source_environment,asset,amount,bucket,status,is_deleted) "
                            + "VALUES (7,'PRODUCTION','USDT',5,'pending_review','ACTIVE',0)");
                    credit.commit();
                    assertThat(reserve.get(5, TimeUnit.SECONDS)).isEqualTo(1);
                } finally {
                    credit.rollback();
                    withdrawal.rollback();
                    executor.shutdownNow();
                    executor.awaitTermination(5, TimeUnit.SECONDS);
                }
            } finally {
                admin.createStatement().execute("DROP DATABASE " + schema);
            }
        }
    }

    private String schema() {
        String schema = "nexion_withdraw_lock_it_" + UUID.randomUUID().toString().replace("-", "");
        if (!schema.matches("nexion_withdraw_lock_it_[0-9a-f]{32}")) throw new IllegalStateException("unsafe test schema");
        return schema;
    }

    private void createFundsFixture(Connection connection) throws Exception {
        connection.createStatement().execute("CREATE TABLE nx_user (id BIGINT PRIMARY KEY,status VARCHAR(16),sandbox INT,is_deleted INT) ENGINE=InnoDB");
        connection.createStatement().execute("CREATE TABLE nx_user_wallet (user_id BIGINT PRIMARY KEY,sandbox INT,is_deleted INT) ENGINE=InnoDB");
        connection.createStatement().execute("CREATE TABLE nx_admin_risk_multi_account_cluster (id BIGINT PRIMARY KEY,cluster_id VARCHAR(32) UNIQUE,account_count INT,status VARCHAR(32),version BIGINT,nodes_json JSON,is_deleted INT) ENGINE=InnoDB");
        connection.createStatement().execute("CREATE TABLE nx_earnings_release_entry (id BIGINT AUTO_INCREMENT PRIMARY KEY,user_id BIGINT,source_environment VARCHAR(16),asset VARCHAR(8),amount DECIMAL(18,8),bucket VARCHAR(32),status VARCHAR(16),is_deleted INT) ENGINE=InnoDB");
        connection.createStatement().execute("INSERT INTO nx_user VALUES (7,'ACTIVE',0,0)");
        connection.createStatement().execute("INSERT INTO nx_user_wallet VALUES (7,0,0)");
        connection.createStatement().execute("INSERT INTO nx_admin_risk_multi_account_cluster VALUES (1,'C1',1,'cleared',0,'[{\"userNo\":\"U00000007\"}]',0)");
    }

    private void lockUser(Connection connection) throws Exception {
        try (var statement = connection.prepareStatement("SELECT id FROM nx_user WHERE id=7 AND is_deleted=0 FOR UPDATE");
             ResultSet rows = statement.executeQuery()) {
            assertThat(rows.next()).isTrue();
        }
    }

    private void lockWallet(Connection connection) throws Exception {
        try (var statement = connection.prepareStatement("SELECT user_id FROM nx_user_wallet WHERE user_id=7 AND is_deleted=0 AND sandbox=0 FOR UPDATE");
             ResultSet rows = statement.executeQuery()) {
            assertThat(rows.next()).isTrue();
        }
    }

    private int protectedEntryCountForUpdate(Connection connection) throws Exception {
        try (var statement = connection.prepareStatement("SELECT amount FROM nx_earnings_release_entry WHERE user_id=7 AND asset='USDT' AND bucket IN ('pending_review','bonus_locked') AND status='ACTIVE' AND is_deleted=0 ORDER BY id FOR UPDATE");
             ResultSet rows = statement.executeQuery()) {
            int count = 0;
            while (rows.next()) count++;
            return count;
        }
    }

    private String status(Connection connection, String method) throws Exception {
        String sql = String.join(" ", EarningsReleaseMapper.class.getMethod(method, Long.class)
                .getAnnotation(Select.class).value()).replace("#{userId}", "?");
        try (var statement = connection.prepareStatement(sql)) {
            statement.setLong(1, 7L);
            try (ResultSet rows = statement.executeQuery()) {
                assertThat(rows.next()).isTrue();
                return rows.getString("status");
            }
        }
    }
}
