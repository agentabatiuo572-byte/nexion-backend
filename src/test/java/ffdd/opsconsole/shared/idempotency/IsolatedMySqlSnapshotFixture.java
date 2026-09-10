package ffdd.opsconsole.shared.idempotency;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.function.Supplier;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

/** Real transactional snapshot race on a private UUID table; never uses business tables. */
public final class IsolatedMySqlSnapshotFixture implements AutoCloseable {
    private final DriverManagerDataSource dataSource = new DriverManagerDataSource(
            isolatedUrl(), System.getenv().getOrDefault("NEXION_TEST_DB_USERNAME", "root"),
            System.getenv().getOrDefault("NEXION_TEST_DB_PASSWORD", ""));
    private final JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    private final String table = "nx_audit_snapshot_" + UUID.randomUUID().toString().replace("-", "");
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final CountDownLatch beforeLock = new CountDownLatch(1);

    public static String isolatedUrl() {
        String url = System.getenv("NEXION_TEST_DB_URL");
        assertThat(url).as("explicit local disposable test database")
                .matches("jdbc:mysql://127\\.0\\.0\\.1:[1-9][0-9]{0,4}/nexion_history_it_[a-f0-9]{32}(\\?.*)?")
                .doesNotContain(":3306/");
        return url;
    }

    public IsolatedMySqlSnapshotFixture() {
        assertThat(table).matches("nx_audit_snapshot_[a-f0-9]{32}");
        jdbc.execute("CREATE TABLE " + table + " (id INT PRIMARY KEY, amount DECIMAL(20,6) NOT NULL) ENGINE=InnoDB");
        try { jdbc.update("INSERT INTO " + table + " VALUES (1,0)"); }
        catch (RuntimeException failure) { jdbc.execute("DROP TABLE " + table); throw failure; }
    }

    public BigDecimal usage() { return jdbc.queryForObject("SELECT SUM(amount) FROM " + table, BigDecimal.class); }

    public void increment() { jdbc.update("UPDATE " + table + " SET amount=amount+1 WHERE id=1"); }

    public void lock() {
        beforeLock.countDown();
        jdbc.queryForObject("SELECT id FROM " + table + " WHERE id=1 FOR UPDATE", Integer.class);
    }

    @SuppressWarnings("unchecked")
    public <T> T transactional(T service) {
        var proxy = new ProxyFactory(service);
        proxy.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(dataSource),
                new AnnotationTransactionAttributeSource()));
        return (T) proxy.getProxy();
    }

    public <T> T afterCompetingCommit(BigDecimal usage, Supplier<T> operation) throws Exception {
        try (var competing = dataSource.getConnection()) {
            competing.setAutoCommit(false);
            try (var statement = competing.createStatement()) {
                statement.executeQuery("SELECT id FROM " + table + " WHERE id=1 FOR UPDATE").close();
                var pending = worker.submit(operation::get);
                assertThat(beforeLock.await(5, TimeUnit.SECONDS)).isTrue();
                try (var insert = competing.prepareStatement("INSERT INTO " + table + " VALUES (2,?)")) {
                    insert.setBigDecimal(1, usage);
                    insert.executeUpdate();
                }
                competing.commit();
                try { return pending.get(10, TimeUnit.SECONDS); }
                catch (ExecutionException failure) {
                    if (failure.getCause() instanceof RuntimeException cause) throw cause;
                    throw failure;
                }
            }
        }
    }

    @Override public void close() throws Exception {
        worker.shutdownNow();
        try { assertThat(worker.awaitTermination(15, TimeUnit.SECONDS)).isTrue(); }
        finally { jdbc.execute("DROP TABLE " + table); }
    }
}
