package ffdd.opsconsole.content.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** Actual current-read and PC-write exclusion on an explicit disposable local MySQL server. */
@EnabledIfEnvironmentVariable(named = "NEXION_LEARNING_COURSE_IT", matches = "true")
class LearningCourseConcurrencyMySqlTest {
    private static final String OPTIONS = "?useSSL=false&allowPublicKeyRetrieval=true&characterEncoding=utf8";

    @Test
    void courseCurrentReadObservesPcChangesAndHoldsThemUntilAwardTransactionEnds() throws Exception {
        inSchema((admin, schema) -> {
            try (Connection app = connect(schema); Connection pc = connect(schema)) {
                app.createStatement().execute("CREATE TABLE nx_help_article (id BIGINT PRIMARY KEY, article_code VARCHAR(128), "
                        + "status INT, reward_nex DECIMAL(20,6), revision BIGINT, is_deleted INT) ENGINE=InnoDB");
                app.createStatement().execute("INSERT INTO nx_help_article VALUES (1,'learn.basics.test-course',1,20,1,0)");
                app.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
                app.setAutoCommit(false);
                try (var old = app.createStatement().executeQuery("SELECT reward_nex FROM nx_help_article WHERE id=1")) {
                    assertThat(old.next()).isTrue();
                    assertThat(old.getBigDecimal(1)).isEqualByComparingTo("20");
                }
                pc.createStatement().executeUpdate("UPDATE nx_help_article SET reward_nex=0,revision=2 WHERE id=1");
                String lock = String.join(" ", HelpArticleMapper.class.getMethod("lockLearningCourse", String.class)
                        .getAnnotation(Select.class).value()).replace("#{courseId}", "?");
                for (String invalid : new String[] {"%", "_", "test%", "TEST-COURSE"}) {
                    try (PreparedStatement statement = app.prepareStatement(lock)) {
                        statement.setString(1, invalid);
                        try (var result = statement.executeQuery()) { assertThat(result.next()).isFalse(); }
                    }
                }
                try (PreparedStatement statement = app.prepareStatement(lock)) {
                    statement.setString(1, "test-course");
                    try (var current = statement.executeQuery()) {
                        assertThat(current.next()).isTrue();
                        assertThat(current.getBigDecimal("reward_nex")).isZero();
                        assertThat(current.getLong("revision")).isEqualTo(2);
                    }
                }
                var executor = Executors.newSingleThreadExecutor();
                CountDownLatch attempted = new CountDownLatch(1);
                try {
                    var archive = executor.submit(() -> {
                        attempted.countDown();
                        return pc.createStatement().executeUpdate("UPDATE nx_help_article SET status=2,revision=3 WHERE id=1");
                    });
                    assertThat(attempted.await(2, TimeUnit.SECONDS)).isTrue();
                    assertThatThrownBy(() -> archive.get(200, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
                    app.commit();
                    assertThat(archive.get(5, TimeUnit.SECONDS)).isEqualTo(1);
                    try (PreparedStatement statement = app.prepareStatement(lock)) {
                        statement.setString(1, "test-course");
                        try (var archived = statement.executeQuery()) {
                            assertThat(archived.next()).isTrue();
                            assertThat(archived.getInt("status")).isEqualTo(2);
                        }
                    }
                } finally {
                    app.rollback();
                    executor.shutdownNow();
                    executor.awaitTermination(5, TimeUnit.SECONDS);
                }
            }
        });
    }

    static String jdbcUrl(String endpoint, String schema) {
        if (!"127.0.0.1:13306".equals(endpoint)) {
            throw new IllegalArgumentException("explicit isolated MySQL endpoint required");
        }
        if (schema == null || (!schema.isEmpty() && !schema.matches("nexion_learning_course_it_[a-f0-9]{32}"))) {
            throw new IllegalArgumentException("unsafe test schema");
        }
        return "jdbc:mysql://" + endpoint + "/" + schema + OPTIONS;
    }

    private static Connection connect(String schema) throws Exception {
        return DriverManager.getConnection(jdbcUrl(System.getenv("NEXION_ISOLATED_MYSQL_ENDPOINT"), schema),
                "root", System.getenv().getOrDefault("NEXION_ISOLATED_MYSQL_PASSWORD", ""));
    }

    private static void inSchema(SchemaTest test) throws Exception {
        String schema = "nexion_learning_course_it_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection admin = connect("")) {
            try (var statement = admin.createStatement()) { statement.execute("CREATE DATABASE " + schema); }
            try (Connection connection = connect(schema)) { test.run(connection, schema); }
            finally {
                try (var statement = admin.createStatement()) { statement.execute("DROP DATABASE " + schema); }
            }
        }
    }

    @FunctionalInterface
    private interface SchemaTest { void run(Connection connection, String schema) throws Exception; }
}