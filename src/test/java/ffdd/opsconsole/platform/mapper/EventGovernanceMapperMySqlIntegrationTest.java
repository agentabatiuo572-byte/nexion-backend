package ffdd.opsconsole.platform.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import ffdd.opsconsole.platform.dto.EventCenterOverview;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Opt-in real-MySQL coverage for the A4 schema-extension mapper.
 *
 * <p>The fixture owns a random schema on the explicitly selected disposable MySQL endpoint.
 * Business database settings are never consulted.
 */
@EnabledIfEnvironmentVariable(named = "NEXION_A4_EVENT_SCHEMA_IT", matches = "true")
class EventGovernanceMapperMySqlIntegrationTest {
    private static final Pattern OWNED_DATABASE = Pattern.compile("nx_a4_schema_test_[0-9a-f]{32}");

    private Connection adminConnection;
    private JdbcTemplate jdbc;
    private String fixtureDatabase;
    private boolean fixtureCreated;
    private TransactionTemplate transaction;
    private EventGovernanceMapper mapper;

    @BeforeEach
    void createOwnedFixtureSchema() throws Exception {
        String endpoint = System.getenv("NEXION_ISOLATED_MYSQL_ENDPOINT");
        fixtureDatabase = "nx_a4_schema_test_" + UUID.randomUUID().toString().replace("-", "");
        String fixtureUrl = isolatedUrl(endpoint, fixtureDatabase);
        adminConnection = DriverManager.getConnection(isolatedUrl(endpoint, ""), "root", "");
        jdbc = new JdbcTemplate(new SingleConnectionDataSource(adminConnection, true));
        assertThat(jdbc.queryForObject("SELECT @@port", Integer.class)).isEqualTo(13306);
        assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class)).isNull();
        try {
            try (var statement = adminConnection.createStatement()) {
                statement.execute("CREATE DATABASE `" + fixtureDatabase
                        + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
            }
            fixtureCreated = true;
            adminConnection.setCatalog(fixtureDatabase);
            assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class)).isEqualTo(fixtureDatabase);
            createFixtureTables();
        } catch (Exception failure) {
            discardFixture();
            throw failure;
        }

        DriverManagerDataSource dataSource = new DriverManagerDataSource(fixtureUrl, "root", "");
        var transactionManager = new org.springframework.jdbc.datasource.DataSourceTransactionManager(dataSource);
        transaction = new TransactionTemplate(transactionManager);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transaction.setTimeout(10);

        Configuration configuration = new Configuration(new Environment("a4-schema-mysql",
                new SpringManagedTransactionFactory(), dataSource));
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(EventGovernanceMapper.class);
        SqlSessionTemplate session = new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(configuration));
        mapper = session.getMapper(EventGovernanceMapper.class);
    }

    @AfterEach
    void discardOwnedFixtureSchema() throws Exception {
        try {
            discardFixture();
        } finally {
            if (adminConnection != null) adminConnection.close();
        }
    }

    @Test
    void carryForwardPromotesOnlyCurrentActivePropertiesAndPreservesTheirMetadata() {
        long schemaId = seedSchema("quest.completed", 316);
        seedProperty(schemaId, "quest_id", "id", 0, 1, 316, 0);
        seedProperty(schemaId, "layer", "enum", 0, 1, 316, 0);
        seedProperty(schemaId, "legacy_hash", "string", 1, 0, 316, 0);
        seedProperty(schemaId, "retired_field", "number", 1, 1, 316, 1);
        // A row from a superseded revision must not be revived or promoted.
        seedProperty(schemaId, "historical_field", "boolean", 0, 1, 315, 0);
        assertThat(mapper.countActiveProperties(schemaId, 316)).isEqualTo(3);
        assertThat(mapper.countActiveProperties(schemaId, 315)).isEqualTo(1);

        transaction.executeWithoutResult(status -> {
            assertThat(mapper.lockCurrentRevision()).isEqualTo(316);
            assertThat(mapper.advanceRevision(316, 317)).isEqualTo(1);
            assertThat(mapper.carryForwardProperties(schemaId, 316, 317)).isEqualTo(3);
            assertThat(mapper.updateSchemaRevision(schemaId, 317, "fixture", "add weekly instance key")).isEqualTo(1);
            assertThat(mapper.insertProperty(schemaId, "instance_key", "string", 317)).isEqualTo(1);
            assertThat(mapper.countActiveProperties(schemaId, 317)).isEqualTo(4);
            assertThat(mapper.countActiveProperties(schemaId, 316)).isZero();
        });

        assertThat(integer("SELECT current_revision FROM nx_event_schema_revision WHERE id=1")).isEqualTo(317);
        assertThat(integer("SELECT current_revision FROM nx_event_schema_registry WHERE id=" + schemaId)).isEqualTo(317);
        assertThat(property(schemaId, "quest_id")).containsEntry("property_type", "id")
                .containsEntry("pii", 0).containsEntry("required_field", 1).containsEntry("registry_revision", 317)
                .containsEntry("is_deleted", 0);
        assertThat(property(schemaId, "layer")).containsEntry("property_type", "enum")
                .containsEntry("pii", 0).containsEntry("required_field", 1).containsEntry("registry_revision", 317)
                .containsEntry("is_deleted", 0);
        assertThat(property(schemaId, "legacy_hash")).containsEntry("property_type", "string")
                .containsEntry("pii", 1).containsEntry("required_field", 0).containsEntry("registry_revision", 317)
                .containsEntry("is_deleted", 0);
        assertThat(property(schemaId, "instance_key")).containsEntry("property_type", "string")
                .containsEntry("pii", 0).containsEntry("required_field", 1).containsEntry("registry_revision", 317)
                .containsEntry("is_deleted", 0);
        assertThat(property(schemaId, "retired_field")).containsEntry("property_type", "number")
                .containsEntry("pii", 1).containsEntry("required_field", 1).containsEntry("registry_revision", 316)
                .containsEntry("is_deleted", 1);
        assertThat(property(schemaId, "historical_field")).containsEntry("property_type", "boolean")
                .containsEntry("registry_revision", 315).containsEntry("is_deleted", 0);
    }

    @Test
    void thrownFailureRollsBackGlobalRevisionSchemaRevisionAndNewPropertyTogether() {
        long schemaId = seedSchema("quest.completed", 316);
        seedProperty(schemaId, "quest_id", "id", 0, 1, 316, 0);
        seedProperty(schemaId, "layer", "enum", 0, 1, 316, 0);

        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            assertThat(mapper.lockCurrentRevision()).isEqualTo(316);
            assertThat(mapper.advanceRevision(316, 317)).isEqualTo(1);
            assertThat(mapper.carryForwardProperties(schemaId, 316, 317)).isEqualTo(2);
            assertThat(mapper.updateSchemaRevision(schemaId, 317, "fixture", "forced rollback")).isEqualTo(1);
            assertThat(mapper.insertProperty(schemaId, "instance_key", "string", 317)).isEqualTo(1);
            throw new IllegalStateException("fixture-after-schema-write-failure");
        })).isInstanceOf(IllegalStateException.class).hasMessage("fixture-after-schema-write-failure");

        assertThat(integer("SELECT current_revision FROM nx_event_schema_revision WHERE id=1")).isEqualTo(316);
        assertThat(integer("SELECT current_revision FROM nx_event_schema_registry WHERE id=" + schemaId)).isEqualTo(316);
        assertThat(property(schemaId, "quest_id")).containsEntry("registry_revision", 316);
        assertThat(property(schemaId, "layer")).containsEntry("registry_revision", 316);
        assertThat(mapper.countActiveProperties(schemaId, 316)).isEqualTo(2);
        assertThat(mapper.countActiveProperties(schemaId, 317)).isZero();
        assertThat(integer("SELECT COUNT(*) FROM nx_event_schema_property WHERE schema_id=" + schemaId
                + " AND property_name='instance_key'")).isZero();
    }

    @Test
    void zeroCustomFieldActiveSchemaRemainsReadableByExactLookupAndAcceptsItsFirstField() {
        long schemaId = seedSchema("app.dau", 316);

        EventCenterOverview.EventSchemaRegistration before = mapper.findSchemaRegistration("app.dau");
        assertThat(before).isNotNull();
        assertThat(before.eventName()).isEqualTo("app.dau");
        assertThat(before.properties()).isNull();
        assertThat(before.version()).isEqualTo("v316");
        assertThat(mapper.countActiveProperties(schemaId, 316)).isZero();

        transaction.executeWithoutResult(status -> {
            assertThat(mapper.lockCurrentRevision()).isEqualTo(316);
            assertThat(mapper.advanceRevision(316, 317)).isEqualTo(1);
            assertThat(mapper.updateSchemaRevision(schemaId, 317, "fixture", "first custom field")).isEqualTo(1);
            assertThat(mapper.insertProperty(schemaId, "first_custom", "string", 317)).isEqualTo(1);
            assertThat(mapper.countActiveProperties(schemaId, 317)).isEqualTo(1);
        });

        EventCenterOverview.EventSchemaRegistration after = mapper.findSchemaRegistration("app.dau");
        assertThat(after).isNotNull();
        assertThat(after.properties()).isEqualTo("first_custom:string");
        assertThat(after.version()).isEqualTo("v317");
    }

    @Test
    void globalRevisionLockSerializesTwoIndependentSchemaExtensions() throws Exception {
        CountDownLatch firstOwnsGlobalRevision = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondAttemptedLock = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> first = executor.submit(() -> transaction.execute(status -> {
                int revision = mapper.lockCurrentRevision();
                firstOwnsGlobalRevision.countDown();
                try {
                    if (!releaseFirst.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("fixture-global-revision-release-timeout");
                    }
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("fixture-global-revision-interrupted", ex);
                }
                if (mapper.advanceRevision(revision, revision + 1) != 1) {
                    throw new IllegalStateException("fixture-first-revision-cas-failed");
                }
                return revision;
            }));
            assertThat(firstOwnsGlobalRevision.await(5, TimeUnit.SECONDS)).isTrue();
            Future<Integer> second = executor.submit(() -> transaction.execute(status -> {
                secondAttemptedLock.countDown();
                int revision = mapper.lockCurrentRevision();
                if (mapper.advanceRevision(revision, revision + 1) != 1) {
                    throw new IllegalStateException("fixture-second-revision-cas-failed");
                }
                return revision;
            }));
            assertThat(secondAttemptedLock.await(5, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> second.get(500, TimeUnit.MILLISECONDS))
                    .isInstanceOf(TimeoutException.class);
            releaseFirst.countDown();

            assertThat(first.get(10, TimeUnit.SECONDS)).isEqualTo(316);
            assertThat(second.get(10, TimeUnit.SECONDS)).isEqualTo(317);
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(15, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(integer("SELECT current_revision FROM nx_event_schema_revision WHERE id=1")).isEqualTo(318);
    }

    private long seedSchema(String eventName, int revision) {
        jdbc.update("INSERT INTO nx_event_schema_registry"
                        + " (event_name,owner_domain,family_key,producer,consumers,is_server_authoritative,sampling_policy,"
                        + "current_revision,status,created_by,reason,created_at,updated_at,is_deleted)"
                        + " VALUES (?,?,?,?,?,?,?,?,'ACTIVE','fixture','fixture seed',NOW(),NOW(),0)",
                eventName, "quest", "engagement", "QuestCompletionFactConsumer", "A4/H3", 1, "100%", revision);
        return jdbc.queryForObject("SELECT id FROM nx_event_schema_registry WHERE event_name=?", Long.class, eventName);
    }

    private void seedProperty(long schemaId, String name, String type, int pii, int required, int revision, int deleted) {
        jdbc.update("INSERT INTO nx_event_schema_property"
                        + " (schema_id,property_name,property_type,pii,required_field,registry_revision,created_at,updated_at,is_deleted)"
                        + " VALUES (?,?,?,?,?,?,NOW(),NOW(),?)",
                schemaId, name, type, pii, required, revision, deleted);
    }

    private Map<String, Object> property(long schemaId, String name) {
        return jdbc.queryForMap("SELECT property_type,pii,required_field,registry_revision,is_deleted"
                + " FROM nx_event_schema_property WHERE schema_id=? AND property_name=?", schemaId, name);
    }

    private int integer(String sql) {
        return jdbc.queryForObject(sql, Integer.class);
    }

    private void createFixtureTables() {
        jdbc.execute("CREATE TABLE nx_event_schema_revision (id TINYINT NOT NULL PRIMARY KEY,"
                + " current_revision INT NOT NULL, updated_at DATETIME NOT NULL)");
        jdbc.execute("INSERT INTO nx_event_schema_revision (id,current_revision,updated_at) VALUES (1,316,NOW())");
        jdbc.execute("CREATE TABLE nx_event_schema_registry (id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,"
                + " event_name VARCHAR(128) NOT NULL, owner_domain VARCHAR(64) NOT NULL, family_key VARCHAR(64) NOT NULL,"
                + " producer VARCHAR(128) NOT NULL, consumers VARCHAR(255) NOT NULL, is_server_authoritative TINYINT NOT NULL,"
                + " sampling_policy VARCHAR(128) NOT NULL, current_revision INT NOT NULL, status VARCHAR(24) NOT NULL,"
                + " created_by VARCHAR(128) NULL, updated_by VARCHAR(128) NULL, reason VARCHAR(512) NULL,"
                + " created_at DATETIME NOT NULL, updated_at DATETIME NOT NULL, is_deleted TINYINT NOT NULL DEFAULT 0,"
                + " UNIQUE KEY uk_event_name (event_name))");
        jdbc.execute("CREATE TABLE nx_event_schema_property (id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,"
                + " schema_id BIGINT NOT NULL, property_name VARCHAR(128) NOT NULL, property_type VARCHAR(32) NOT NULL,"
                + " pii TINYINT NOT NULL DEFAULT 0, required_field TINYINT NOT NULL DEFAULT 1, registry_revision INT NOT NULL,"
                + " created_at DATETIME NOT NULL, updated_at DATETIME NOT NULL, is_deleted TINYINT NOT NULL DEFAULT 0,"
                + " UNIQUE KEY uk_schema_property (schema_id,property_name))");
        jdbc.execute("CREATE TABLE nx_admin_event_lifecycle (id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,"
                + " event_name VARCHAR(128) NOT NULL, lifecycle_state VARCHAR(24) NOT NULL, version BIGINT NOT NULL,"
                + " updated_at DATETIME NOT NULL, is_deleted TINYINT NOT NULL DEFAULT 0)");
    }

    private void discardFixture() throws Exception {
        if (fixtureCreated && fixtureDatabase != null && OWNED_DATABASE.matcher(fixtureDatabase).matches()) {
            assertThat(jdbc.queryForObject("SELECT @@port", Integer.class)).isEqualTo(13306);
            try (var statement = adminConnection.createStatement()) {
                statement.execute("DROP DATABASE IF EXISTS `" + fixtureDatabase + "`");
                fixtureCreated = false;
            }
        }
    }

    static String isolatedUrl(String endpoint, String database) {
        if (!"127.0.0.1:13306".equals(endpoint)) throw new IllegalArgumentException("isolated endpoint required");
        if (database == null || (!database.isEmpty() && !OWNED_DATABASE.matcher(database).matches()))
            throw new IllegalArgumentException("owned UUID schema required");
        return "jdbc:mysql://" + endpoint + "/" + database
                + "?useSSL=false&allowPublicKeyRetrieval=true&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true";
    }
}
