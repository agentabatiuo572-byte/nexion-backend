package ffdd.opsconsole.growth.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

/** Runs only against a caller-provided disposable MySQL schema, never a default database. */
@EnabledIfEnvironmentVariable(named = "NEXION_H8_MUTEX_MYSQL_URL", matches = ".+")
class H8RewardMutexMigrationMySqlIntegrationTest {
    private static final String MIGRATION = "20261017_h8_reward_mutation_mutex.sql";

    @Test
    void forwardMigrationRepairsMissingRowAndPreservesRepeatabilityAndTransactionLocks() throws Exception {
        assertThat(Files.readString(Path.of("scripts", "apply_startup_schema_migrations.ps1")))
                .contains(MIGRATION);
        try (Connection connection = connect()) {
            // Fresh installation: the migration can create its own prerequisite table.
            migrate(connection);
            assertThat(lock(connection)).isEqualTo("H8_REWARD");
            try (var statement = connection.createStatement()) {
                statement.executeUpdate("INSERT INTO nx_admin_operation_mutex VALUES ('G4_CONFIG','2020-01-01 00:00:00')");
                statement.executeUpdate("UPDATE nx_admin_operation_mutex SET updated_at='2020-02-02 00:00:00' WHERE lock_key='H8_REWARD'");
            }
            List<String> before = rows(connection);
            migrate(connection);
            assertThat(rows(connection)).as("rerun preserves all lock rows and timestamps").isEqualTo(before);

            // Upgrade fixture: existing shared table and other locks, but no H8 row.
            try (var statement = connection.createStatement()) {
                statement.executeUpdate("DELETE FROM nx_admin_operation_mutex WHERE lock_key='H8_REWARD'");
            }
            assertThat(lock(connection)).as("reproduces the missing mutex seen by the service").isNull();
            migrate(connection);
            assertThat(lock(connection)).isEqualTo("H8_REWARD");
            assertThat(rows(connection)).hasSize(2).contains("G4_CONFIG|2020-01-01 00:00:00");
            List<String> repaired = rows(connection);
            migrate(connection);
            assertThat(rows(connection)).isEqualTo(repaired);
        }
        assertSerializesUntilCompletion(false);
        assertSerializesUntilCompletion(true);
    }

    private void assertSerializesUntilCompletion(boolean rollback) throws Exception {
        var executor = Executors.newSingleThreadExecutor();
        try (Connection owner = connect()) {
            owner.setAutoCommit(false);
            assertThat(lock(owner)).isEqualTo("H8_REWARD");
            CountDownLatch attempted = new CountDownLatch(1);
            var waiter = executor.submit(() -> {
                try (Connection next = connect()) {
                    next.setAutoCommit(false);
                    attempted.countDown();
                    String key = lock(next);
                    next.commit();
                    return key;
                }
            });
            assertThat(attempted.await(5, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> waiter.get(300, TimeUnit.MILLISECONDS))
                    .as("another H8 writer cannot pass the real mapper lock before completion")
                    .isInstanceOf(TimeoutException.class);
            if (rollback) owner.rollback(); else owner.commit();
            assertThat(waiter.get(5, TimeUnit.SECONDS)).isEqualTo("H8_REWARD");
        } finally {
            executor.shutdownNow();
        }
    }

    private String lock(Connection connection) throws Exception {
        String sql = String.join(" ", ReferralRewardMapper.class.getMethod("lockRewardMutation")
                .getAnnotation(Select.class).value());
        try (var statement = connection.createStatement(); var result = statement.executeQuery(sql)) {
            return result.next() ? result.getString(1) : null;
        }
    }

    private List<String> rows(Connection connection) throws Exception {
        List<String> rows = new ArrayList<>();
        try (var statement = connection.createStatement(); var result = statement.executeQuery(
                "SELECT lock_key,DATE_FORMAT(updated_at,'%Y-%m-%d %H:%i:%s') FROM nx_admin_operation_mutex ORDER BY lock_key")) {
            while (result.next()) rows.add(result.getString(1) + "|" + result.getString(2));
        }
        return rows;
    }

    private void migrate(Connection connection) {
        ScriptUtils.executeSqlScript(connection, new FileSystemResource(Path.of("scripts", "migrations", MIGRATION)));
    }

    private Connection connect() throws Exception {
        return DriverManager.getConnection(System.getenv("NEXION_H8_MUTEX_MYSQL_URL"),
                System.getenv().getOrDefault("NEXION_H8_MUTEX_MYSQL_USER", "root"),
                System.getenv().getOrDefault("NEXION_H8_MUTEX_MYSQL_PASSWORD", ""));
    }
}
