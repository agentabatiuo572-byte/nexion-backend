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
    private static final String OWNER = "cs_enhance_20261001|codex/cs-enhance-core-20261001";
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
        if (url == null || !url.startsWith("jdbc:mysql://127.0.0.1:33329/cs_enhance_20261001?"))
            throw new IllegalStateException("Independent local database required before boot");
        for (var expected : java.util.Map.of("NEXION_DB_USERNAME", "cs_enhance_runner",
                "NEXION_REDIS_HOST", "127.0.0.1", "NEXION_REDIS_PORT", "16341",
                "NEXION_MINIO_ENDPOINT", "http://127.0.0.1:19041",
                "NEXION_MINIO_BUCKET", "cs-enhance-20261001-private").entrySet()) {
            if (!expected.getValue().equals(System.getenv(expected.getKey())))
                throw new IllegalStateException("Independent runtime setting required: " + expected.getKey());
        }
        registry.add("server.address", () -> "127.0.0.1");
        registry.add("server.port", () -> 18141);
        registry.add("spring.data.redis.database", () -> 0);
        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.hikari.jdbc-url", () -> url);
        registry.add("spring.datasource.username", () -> "cs_enhance_runner");
        registry.add("spring.datasource.password", () -> System.getenv("NEXION_DB_PASSWORD"));
        registry.add("spring.datasource.hikari.username", () -> "cs_enhance_runner");
        registry.add("spring.datasource.hikari.password", () -> System.getenv("NEXION_DB_PASSWORD"));
        registry.add("spring.data.redis.host", () -> "127.0.0.1");
        registry.add("spring.data.redis.port", () -> 16341);
        registry.add("spring.data.redis.password", () -> System.getenv("NEXION_REDIS_PASSWORD"));
        registry.add("nexion.storage.endpoint", () -> "http://127.0.0.1:19041");
        registry.add("nexion.storage.bucket", () -> "cs-enhance-20261001-private");
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
        assertThat(context.containsBean("org.springframework.context.annotation.internalScheduledAnnotationProcessor")).isFalse();
        assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class)).isEqualTo("cs_enhance_20261001");
        assertThat(jdbc.queryForObject("SELECT @@port", Integer.class)).isEqualTo(33329);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_user", Long.class)).isPositive();
        String probe = "cs-enhance:20261001:preparation:" + UUID.randomUUID();
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
                .containsExactly("cs_enhance_runner@127.0.0.1");
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
        byte[] bytes = probe.getBytes(StandardCharsets.UTF_8);
        String object = "preparation/" + UUID.randomUUID() + ".txt";
        storage.put(object, "text/plain", new ByteArrayInputStream(bytes), bytes.length);
        try (var readback = storage.get(object)) { assertThat(readback.readAllBytes()).isEqualTo(bytes); }
        var http = HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(10)).build();
        assertThat(healthEndpoint.health().getStatus()).isEqualTo(Status.UP);
        var publicRead = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:18141/api/config/platform"))
                .timeout(java.time.Duration.ofSeconds(10)).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(publicRead.statusCode()).isEqualTo(200);
        assertThat(json.readTree(publicRead.body()).path("code").asInt()).isZero();
        var unauthorized = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:18141/api/admin/content/support-agents/rules"))
                .timeout(java.time.Duration.ofSeconds(10)).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(json.readTree(unauthorized.body()).path("code").asInt()).isEqualTo(401);
        var privateObject = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:19041/" + bucket + "/" + marker))
                .timeout(java.time.Duration.ofSeconds(10)).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(privateObject.statusCode()).isEqualTo(403);
        Files.writeString(evidence.resolve("preparation-runtime.json"),
                "{\"timestamp\":\"" + Instant.now() + "\",\"stage\":\"preparation-only\",\"database\":\"cs_enhance_20261001\","
                + "\"httpPort\":18141,\"redisPort\":16341,\"bucket\":\"" + bucket + "\",\"copiedObjects\":" + copied
                + ",\"sqlReadback\":true,\"sourceDatabaseDenied\":true,\"redisReadback\":true,\"storageReadback\":true,"
                + "\"anonymousStorageDenied\":true,"
                + "\"triggerWritesRolledBack\":3,"
                + "\"baselineAttachmentReadback\":true,\"httpHealth\":true,\"anonymousAdminDenied\":true,\"scheduledJobs\":false,"
                + "\"productImplementationReleased\":false}");
    }
}
