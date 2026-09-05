package ffdd.opsconsole.shared.outbox;

import static org.mockito.Mockito.mock;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import ffdd.opsconsole.platform.application.A4RuntimePolicyService;
import ffdd.opsconsole.shared.config.DateTimeFormatConfig;
import ffdd.opsconsole.shared.outbox.mapper.EventOutboxMapper;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.mapping.Environment;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

/** Owned, disposable database. Copies table definitions only, never real business rows. */
public final class CanonicalEventSchemaMySqlFixture implements AutoCloseable {
    public static final String MIGRATION = "20260831_business_event_schema_closure.sql";
    private final String database = "nx_event_closure_test_" + UUID.randomUUID().toString().replace("-", "");
    private final DriverManagerDataSource dataSource;
    private final JdbcTemplate jdbc;
    private final MybatisConfiguration configuration;
    private final SqlSessionTemplate session;
    private final EventOutboxService outbox;
    private boolean created;

    public CanonicalEventSchemaMySqlFixture(String... additionalLiveTableNames) throws Exception {
        String sourceUrl = System.getenv().getOrDefault("NEXION_TEST_DB_URL",
                "jdbc:mysql://127.0.0.1:3306/nexion?useUnicode=true&characterEncoding=utf8"
                        + "&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true");
        String username = System.getenv().getOrDefault("NEXION_TEST_DB_USERNAME", "root");
        String password = System.getenv("NEXION_TEST_DB_PASSWORD");
        int slash = sourceUrl.indexOf('/', "jdbc:mysql://".length());
        int query = sourceUrl.indexOf('?', slash);
        if (!sourceUrl.matches("^jdbc:mysql://(127\\.0\\.0\\.1|localhost|\\[::1\\])(?::[0-9]{1,5})?/[^?]+(?:\\?.*)?$")
                || slash < 0 || password == null || password.isBlank()) {
            throw new IllegalStateException("Explicit loopback MySQL test connection required");
        }
        assertOwnedDatabase();
        String fixtureUrl = sourceUrl.substring(0, slash + 1) + database
                + (query < 0 ? "" : sourceUrl.substring(query));
        dataSource = new DriverManagerDataSource(fixtureUrl, username, password);
        jdbc = new JdbcTemplate(dataSource);
        var tables = new LinkedHashSet<>(List.of("nx_event_schema_revision", "nx_event_schema_registry",
                "nx_event_schema_property", "nx_admin_event_lifecycle", "nx_event_outbox"));
        tables.addAll(List.of(additionalLiveTableNames));
        try (Connection source = DriverManager.getConnection(sourceUrl, username, password)) {
            JdbcTemplate setup = new JdbcTemplate(new SingleConnectionDataSource(source, true));
            List<String> definitions = tables.stream().map(table -> {
                if (!table.matches("nx_[a-z0-9_]+")) throw new IllegalArgumentException("Invalid fixture table");
                return setup.queryForObject("SHOW CREATE TABLE `" + table + "`", (rs, row) -> rs.getString(2));
            }).toList();
            setup.execute("CREATE DATABASE `" + database + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
            created = true;
            setup.execute("USE `" + database + "`");
            for (String definition : definitions) setup.execute(definition);
        } catch (Exception failure) {
            try { close(); } catch (Exception cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
        try {
            configuration = new MybatisConfiguration();
            configuration.setEnvironment(new Environment("event-schema-closure", new SpringManagedTransactionFactory(), dataSource));
            configuration.setMapUnderscoreToCamelCase(true);
            configuration.addMapper(EventOutboxMapper.class);
            session = new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(configuration));
            Jackson2ObjectMapperBuilder builder = new Jackson2ObjectMapperBuilder()
                    .featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
            new DateTimeFormatConfig().nexionDateTimeJacksonCustomizer().customize(builder);
            ObjectMapper json = builder.build();
            outbox = new EventOutboxService(mapper(EventOutboxMapper.class), json,
                    new OutboxProperties(), mock(A4RuntimePolicyService.class));
        } catch (RuntimeException failure) {
            try { close(); } catch (Exception cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    public JdbcTemplate jdbc() { return jdbc; }
    public EventOutboxService outbox() { return outbox; }

    public <T> T mapper(Class<T> type) {
        if (!configuration.hasMapper(type)) configuration.addMapper(type);
        return session.getMapper(type);
    }

    public void migrate() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            if (!database.equals(connection.getCatalog())) throw new IllegalStateException("Wrong migration database");
            ScriptUtils.executeSqlScript(connection, new FileSystemResource("scripts/migrations/" + MIGRATION));
        }
    }

    @SuppressWarnings("unchecked")
    public <T> T transactional(T service) {
        ProxyFactory proxy = new ProxyFactory(service);
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(dataSource),
                new AnnotationTransactionAttributeSource()));
        return (T) proxy.getProxy();
    }

    private void assertOwnedDatabase() {
        if (!database.matches("nx_event_closure_test_[0-9a-f]{32}")) {
            throw new IllegalStateException("Refusing non-fixture database operation");
        }
    }

    @Override
    public void close() throws Exception {
        if (!created) return;
        assertOwnedDatabase();
        try (Connection connection = dataSource.getConnection()) {
            if (!database.equals(connection.getCatalog())) throw new IllegalStateException("Wrong cleanup database");
            try (var statement = connection.createStatement()) {
                statement.execute("USE information_schema");
                statement.execute("DROP DATABASE `" + database + "`");
            }
            created = false;
        }
    }
}
