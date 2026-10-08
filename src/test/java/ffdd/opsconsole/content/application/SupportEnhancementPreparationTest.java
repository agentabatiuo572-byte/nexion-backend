package ffdd.opsconsole.content.application;

import ffdd.opsconsole.NexionOpsConsoleApplication;
import ffdd.opsconsole.shared.storage.ObjectStorageService;
import ffdd.opsconsole.shared.storage.StorageProperties;
import io.minio.*;
import io.minio.messages.Item;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.time.Instant;
import java.util.UUID;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.boot.test.context.*;
import org.springframework.boot.actuate.health.HealthEndpoint;
import org.springframework.boot.actuate.health.Status;
import org.springframework.context.annotation.*;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import static org.assertj.core.api.Assertions.*;

/** Preparation only: boots the unchanged product with isolated dependencies and no scheduled jobs. */
@EnabledIfEnvironmentVariable(named="CS_ENHANCE_PREP_ENABLED", matches="true")
@SpringBootTest(classes=NexionOpsConsoleApplication.class,
        webEnvironment=SpringBootTest.WebEnvironment.DEFINED_PORT)
@Import(SupportEnhancementPreparationTest.IsolatedConfiguration.class)
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class SupportEnhancementPreparationTest {
    private static final SupportRuntimeTarget TARGET = SupportRuntimeTarget.current();
    private static final String OWNER = TARGET.owner();
    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate redis;
    @Autowired MinioClient minio;
    @Autowired ObjectStorageService storage;
    @Autowired StorageProperties properties;
    @Autowired ApplicationContext context;
    @Autowired ObjectMapper json;
    @Autowired HealthEndpoint healthEndpoint;



    @DynamicPropertySource static void isolatedBoundary(DynamicPropertyRegistry registry) {
        registry.add("logging.level.org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration",()->"ERROR");
        String url = System.getenv("NEXION_DB_URL");
        TARGET.requireEnvironment(System.getenv());
        registry.add("server.address", () -> "127.0.0.1");
        registry.add("server.port", TARGET::httpPort);
        registry.add("spring.data.redis.database", () -> 0);
        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.hikari.jdbc-url", () -> url);
        registry.add("spring.datasource.username", TARGET::username);
        registry.add("spring.datasource.password", () -> System.getenv("NEXION_DB_PASSWORD"));
        registry.add("spring.datasource.hikari.username", TARGET::username);
        registry.add("spring.datasource.hikari.password", () -> System.getenv("NEXION_DB_PASSWORD"));
        registry.add("spring.data.redis.host", () -> "127.0.0.1");
        registry.add("spring.data.redis.port", TARGET::redisPort);
        registry.add("spring.data.redis.password", () -> System.getenv("NEXION_REDIS_PASSWORD"));
        registry.add("nexion.storage.endpoint", TARGET::storageEndpoint);
        registry.add("nexion.storage.bucket", TARGET::bucket);
        registry.add("nexion.storage.access-key", () -> System.getenv("NEXION_MINIO_ACCESS_KEY"));
        registry.add("nexion.storage.secret-key", () -> System.getenv("NEXION_MINIO_SECRET_KEY"));
    }

    @TestConfiguration(proxyBeanMethods=false)
    static class IsolatedConfiguration {
        @Bean static org.springframework.beans.factory.config.BeanPostProcessor journalSharedMutations(javax.sql.DataSource dataSource) {
            return SharedMutationJournal.bootstrapJournal(dataSource);
        }
        @Bean static BeanFactoryPostProcessor disableScheduledJobs(Environment environment) {
            return factory -> {
                rejectAlternativeConnections(environment);
                ((BeanDefinitionRegistry) factory).removeBeanDefinition(
                        "org.springframework.context.annotation.internalScheduledAnnotationProcessor");
            };
        }
    }

    private static void rejectAlternativeConnections(Environment environment) {
        for (String alternative : java.util.List.of("spring.datasource.jndi-name", "spring.data.redis.url", "spring.data.redis.sentinel.master")) {
            String value = environment.getProperty(alternative);
            if (value != null && !value.isBlank()) throw new IllegalStateException("Alternate connection forbidden before boot: " + alternative);
        }
        for (String alternative : java.util.List.of("spring.data.redis.sentinel.nodes", "spring.data.redis.cluster.nodes")) {
            if (!Binder.get(environment).bind(alternative, Bindable.listOf(String.class)).orElse(java.util.List.of()).isEmpty())
                throw new IllegalStateException("Alternate connection forbidden before boot: " + alternative);
        }
    }

    @Test void alternateConnectionsAreRejected() {
        for (String key : java.util.List.of("spring.datasource.jndi-name", "spring.data.redis.url", "spring.data.redis.sentinel.master",
                "spring.data.redis.sentinel.nodes", "spring.data.redis.cluster.nodes",
                "spring.data.redis.sentinel.nodes[0]", "spring.data.redis.cluster.nodes[0]")) {
            var environment = new StandardEnvironment();
            environment.getPropertySources().addFirst(new MapPropertySource("unsafe-override", java.util.Map.of(key, "127.0.0.1:16329")));
            assertThatThrownBy(() -> rejectAlternativeConnections(environment)).isInstanceOf(IllegalStateException.class);
        }
    }

    @Test void isolatedDependenciesPersistAndReadBack() throws Exception {
        if (TARGET.analytics()) SupportExclusiveRuntimeOwnership.requireActual(
                json.readTree(Path.of(System.getenv("CS_ENHANCE_ACTOR_CONTEXT")).toFile()), TARGET, jdbc);
        assertThat(context.containsBean("org.springframework.context.annotation.internalScheduledAnnotationProcessor")).isFalse();
        assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class)).isEqualTo(TARGET.database());
        assertThat(jdbc.queryForObject("SELECT @@port", Integer.class)).isEqualTo(TARGET.databasePort());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_user", Long.class)).isPositive();
        String probe = (TARGET.analytics() ? "cs-analytics:20261007:preparation:" : "cs-enhance:20261001:preparation:") + UUID.randomUUID();
        String object = "preparation/" + UUID.randomUUID() + ".txt";
        boolean ownProbeTable = TARGET.analytics() && jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_cs_enhance_preparation_probe'", Integer.class) == 0;
        Throwable operationFailure = null;
        if (TARGET.analytics()) Files.writeString(Path.of(System.getenv("CS_ENHANCE_EVIDENCE_DIR"), "preparation-intent.json"),
                json.writeValueAsString(java.util.Map.of("probe", probe, "object", object, "owner", OWNER, "ownProbeTable", ownProbeTable,
                        "contextSha256", System.getenv("CS_ENHANCE_ACTOR_CONTEXT_SHA256"))));
        try {
        jdbc.execute("CREATE TABLE IF NOT EXISTS nx_cs_enhance_preparation_probe (id VARCHAR(100) PRIMARY KEY, value VARCHAR(100) NOT NULL)");
        jdbc.update("INSERT INTO nx_cs_enhance_preparation_probe VALUES (?,?)", probe, OWNER);
        try (var readback = DriverManager.getConnection(System.getenv("NEXION_DB_URL"),
                System.getenv("NEXION_DB_USERNAME"), System.getenv("NEXION_DB_PASSWORD"));
                var statement = readback.prepareStatement("SELECT value FROM nx_cs_enhance_preparation_probe WHERE id=?")) {
            statement.setString(1, probe);
            try (var row = statement.executeQuery()) { assertThat(row.next()).isTrue(); assertThat(row.getString(1)).isEqualTo(OWNER); }
            assertThatThrownBy(() -> readback.createStatement().executeQuery("SELECT id FROM cs_redesign.nx_user LIMIT 1"))
                    .isInstanceOf(SQLException.class).satisfies(error -> assertThat(((SQLException) error).getErrorCode()).isEqualTo(1142));
        }
        redis.opsForValue().set(probe, OWNER, java.time.Duration.ofDays(7));
        assertThat(redis.opsForValue().get(probe)).isEqualTo(OWNER);
        assertThat(jdbc.queryForList("SELECT DISTINCT DEFINER FROM information_schema.TRIGGERS WHERE TRIGGER_SCHEMA=DATABASE()", String.class))
                .containsExactly(TARGET.username() + "@127.0.0.1");
        long customer = jdbc.queryForObject("SELECT MIN(id) FROM nx_user", Long.class);
        try (var triggerWrite = DriverManager.getConnection(System.getenv("NEXION_DB_URL"),
                System.getenv("NEXION_DB_USERNAME"), System.getenv("NEXION_DB_PASSWORD"))) {
            triggerWrite.setAutoCommit(false);
            try {
                for (String sql : java.util.List.of(
                        "INSERT INTO nx_commission_event(user_id,commission_type,currency,status) VALUES (?,'DIRECT','USDT','PENDING')",
                        "INSERT INTO nx_trial_claim(user_id,claim_no,device_name,claimed_at,expires_at) VALUES (?,'" + UUID.randomUUID() + "','Preparation probe',NOW(),DATE_ADD(NOW(),INTERVAL 1 DAY))",
                        "INSERT INTO nx_withdrawal_order(user_id,withdrawal_no,asset,amount,target_address,status,d2_penalty_fee_rate,d2_gross_fee,d2_nex_burned,d2_nex_fee_offset_rate,d2_fee_waived,d2_actual_fee,d2_net_receive) VALUES (?,'" + UUID.randomUUID() + "','USDT',1,'Preparation probe','REJECTED',NULL,NULL,NULL,NULL,NULL,NULL,NULL)")) {
                    try (var insert = triggerWrite.prepareStatement(sql)) {
                        insert.setLong(1, customer);
                        assertThat(insert.executeUpdate()).isEqualTo(1);
                    }
                }
            } finally { triggerWrite.rollback(); }
        }
        String bucket = properties.getBucket();
        String marker = "preparation/ownership.txt";
        int copied = 0;
        Path evidence = Path.of(System.getenv("CS_ENHANCE_EVIDENCE_DIR"));
        Path claim = evidence.resolve("storage-bucket-claim.txt");
        if (!minio.bucketExists(BucketExistsArgs.builder().bucket(bucket).build())) {
            Files.writeString(claim, OWNER);
            minio.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
        }
        // The local claim is written before creation, so interrupted initialization can resume without adopting another bucket.
        assertThat(Files.readString(claim)).isEqualTo(OWNER);
        if (!storage.exists(marker)) {
            byte[] owner = OWNER.getBytes(StandardCharsets.UTF_8);
            storage.put(marker, "text/plain", new ByteArrayInputStream(owner), owner.length);
        }
        try (var owner = storage.get(marker)) { assertThat(new String(owner.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo(OWNER); }
        if (TARGET.analytics()) {
            var manifest = json.readTree(Path.of(System.getenv("SUPPORT_STORAGE_BASELINE_MANIFEST")).toFile());
            assertThat(manifest.isArray()).isTrue();
            assertThat(manifest.size()).isPositive();
            var keys = new java.util.HashSet<String>();
            for (var item : manifest) {
                String key = item.path("key").asText();
                assertThat(key).isNotBlank();
                assertThat(keys.add(key)).isTrue();
                try (var current = storage.get(key)) {
                    byte[] actual = current.readAllBytes();
                    assertThat(actual.length).isEqualTo(item.path("size").asInt(-1));
                    assertThat(java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(actual)))
                            .isEqualTo(item.path("sha256").asText());
                }
            }
        } else {
        MinioClient source = MinioClient.builder().endpoint("http://127.0.0.1:19029")
                .credentials(System.getenv("CS_ENHANCE_SOURCE_STORAGE_ACCESS_KEY"), System.getenv("CS_ENHANCE_SOURCE_STORAGE_SECRET_KEY")).build();
        String complete = "preparation/copy-complete.txt";
        if (!storage.exists(complete)) {
            for (Result<Item> item : source.listObjects(ListObjectsArgs.builder().bucket("cs-redesign-private").recursive(true).build())) {
                String key = item.get().objectName();
                if (!storage.exists(key)) {
                    var metadata = source.statObject(StatObjectArgs.builder().bucket("cs-redesign-private").object(key).build());
                    try (var bytes = source.getObject(GetObjectArgs.builder().bucket("cs-redesign-private").object(key).build())) {
                        storage.put(key, metadata.contentType(), bytes, metadata.size());
                    }
                    copied++;
                }
            }
            byte[] owner = OWNER.getBytes(StandardCharsets.UTF_8);
            storage.put(complete, "text/plain", new ByteArrayInputStream(owner), owner.length);
        }
        String[] baselineKeys=json.readValue(evidence.resolve("storage-baseline-attached-keys.json").toFile(),String[].class);
        assertThat(baselineKeys).hasSize(90);
        for (String key : baselineKeys) {
            try (var old = source.getObject(GetObjectArgs.builder().bucket("cs-redesign-private").object(key).build());
                    var current = storage.get(key)) { assertThat(current.readAllBytes()).isEqualTo(old.readAllBytes()); }
        }
        }
        byte[] bytes = probe.getBytes(StandardCharsets.UTF_8);
        storage.put(object, "text/plain", new ByteArrayInputStream(bytes), bytes.length);
        try (var readback = storage.get(object)) { assertThat(readback.readAllBytes()).isEqualTo(bytes); }
        var http = HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(10)).build();
        assertThat(healthEndpoint.health().getStatus()).isEqualTo(Status.UP);
        var publicRead = http.send(HttpRequest.newBuilder(URI.create(TARGET.httpBase() + "/api/config/platform"))
                .timeout(java.time.Duration.ofSeconds(10)).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(publicRead.statusCode()).isEqualTo(200);
        assertThat(json.readTree(publicRead.body()).path("code").asInt()).isZero();
        var unauthorized = http.send(HttpRequest.newBuilder(URI.create(TARGET.httpBase() + "/api/admin/content/support-agents/rules"))
                .timeout(java.time.Duration.ofSeconds(10)).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(json.readTree(unauthorized.body()).path("code").asInt()).isEqualTo(401);
        var privateObject = http.send(HttpRequest.newBuilder(URI.create(TARGET.storageEndpoint() + "/" + bucket + "/" + marker))
                .timeout(java.time.Duration.ofSeconds(10)).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(privateObject.statusCode()).isEqualTo(403);
        Files.writeString(evidence.resolve("preparation-runtime.json"),
                "{\"timestamp\":\"" + Instant.now() + "\",\"stage\":\"preparation-only\",\"database\":\"" + TARGET.database() + "\","
                + "\"httpPort\":" + TARGET.httpPort() + ",\"redisPort\":" + TARGET.redisPort() + ",\"bucket\":\"" + bucket + "\",\"copiedObjects\":" + copied
                + ",\"sqlReadback\":true,\"sourceDatabaseDenied\":true,\"redisReadback\":true,\"storageReadback\":true,"
                + "\"anonymousStorageDenied\":true,"
                + "\"triggerWritesRolledBack\":3,"
                + "\"baselineAttachmentReadback\":true,\"httpHealth\":true,\"anonymousAdminDenied\":true,\"scheduledJobs\":false,"
                + "\"productImplementationReleased\":false}");
        } catch (Exception | Error failure) { operationFailure = failure; throw failure; }
        finally {
            if (TARGET.analytics()) try { cleanupPreparation(probe, object, ownProbeTable); }
            catch (Exception cleanup) { if (operationFailure != null) operationFailure.addSuppressed(cleanup); else throw cleanup; }
        }
    }

    private void cleanupPreparation(String probe, String object, boolean ownProbeTable) throws Exception {
        SupportExclusiveRuntimeOwnership.requireActual(json.readTree(Path.of(System.getenv("CS_ENHANCE_ACTOR_CONTEXT")).toFile()), TARGET, jdbc);
        var failures = new java.util.ArrayList<Throwable>();
        try {
            if (storage.exists(object)) {
                try (var bytes = storage.get(object)) { assertThat(bytes.readAllBytes()).isEqualTo(probe.getBytes(StandardCharsets.UTF_8)); }
                minio.removeObject(RemoveObjectArgs.builder().bucket(TARGET.bucket()).object(object).build());
            }
            assertThat(storage.exists(object)).isFalse();
        } catch (Exception | AssertionError failure) { failures.add(failure); }
        try {
            String value = redis.opsForValue().get(probe);
            if (value != null) { assertThat(value).isEqualTo(OWNER); redis.delete(probe); }
            assertThat(redis.opsForValue().get(probe)).isNull();
        } catch (Exception | AssertionError failure) { failures.add(failure); }
        try {
            boolean exists = jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_cs_enhance_preparation_probe'", Integer.class) == 1;
            if (exists) {
                var values = jdbc.queryForList("SELECT value FROM nx_cs_enhance_preparation_probe WHERE id=?", String.class, probe);
                if (!values.isEmpty()) {
                    assertThat(values).containsExactly(OWNER);
                    assertThat(jdbc.update("DELETE FROM nx_cs_enhance_preparation_probe WHERE id=? AND value=?", probe, OWNER)).isEqualTo(1);
                }
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_cs_enhance_preparation_probe WHERE id=?", Integer.class, probe)).isZero();
                if (ownProbeTable) {
                    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_cs_enhance_preparation_probe", Integer.class)).isZero();
                    jdbc.execute("DROP TABLE nx_cs_enhance_preparation_probe");
                    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_cs_enhance_preparation_probe'", Integer.class)).isZero();
                }
            }
        } catch (Exception | AssertionError failure) { failures.add(failure); }
        Files.writeString(Path.of(System.getenv("CS_ENHANCE_EVIDENCE_DIR"), "preparation-cleanup.json"),
                json.writeValueAsString(java.util.Map.of("probe", probe, "object", object, "ownProbeTable", ownProbeTable,
                        "contextSha256", System.getenv("CS_ENHANCE_ACTOR_CONTEXT_SHA256"), "complete", failures.isEmpty(), "failures", failures.stream().map(Throwable::toString).toList())));
        if (!failures.isEmpty()) {
            var failure = new IllegalStateException("Preparation cleanup did not verify all owned resources");
            failures.forEach(failure::addSuppressed); throw failure;
        }
    }
}
