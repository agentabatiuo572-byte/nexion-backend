package ffdd.opsconsole.auth.application;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.injector.DefaultSqlInjector;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import ffdd.opsconsole.auth.application.AuthNewAccountCreator.BirthHandle;
import ffdd.opsconsole.auth.mapper.AuthRegistrationBirthMapper;
import ffdd.opsconsole.finance.application.SupportFundAuthBirthAdapter;
import ffdd.opsconsole.finance.application.SupportFundJournalBindingService;
import ffdd.opsconsole.finance.facade.SupportFundAuthBirthFacade;
import ffdd.opsconsole.finance.facade.SupportFundAuthBirthFacade.Outcome;
import ffdd.opsconsole.finance.facade.SupportFundJournalFacade;
import ffdd.opsconsole.finance.mapper.SupportFundJournalMapper;
import ffdd.opsconsole.shared.config.MybatisMetaObjectHandler;
import ffdd.opsconsole.shared.outbox.mapper.EventOutboxMapper;
import ffdd.opsconsole.user.application.UserRegistrationWriteAdapter;
import ffdd.opsconsole.user.facade.UserRegistrationWriteFacade;
import ffdd.opsconsole.user.facade.UserRegistrationWriteFacade.NewAccountCommand;
import ffdd.opsconsole.user.mapper.UserOpsMapper;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.WeekFields;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.ibatis.executor.keygen.Jdbc3KeyGenerator;
import org.apache.ibatis.executor.statement.StatementHandler;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Signature;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.UnexpectedRollbackException;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;

/** SQL_ONLY creator chain, not the full registration API or EventOutboxService publisher. */
@EnabledIfEnvironmentVariable(named="SUPPORT_FUND_JOURNAL_MYSQL_IT", matches="true")
@Execution(ExecutionMode.SAME_THREAD)
class AuthFundBirthMySqlTest {
    static final int EXPECTED_TESTS = 6;
    private static final Path RUNTIME = Path.of("D:/CodexData/test-environments/workflow-runs/support-analytics-20261007-r2/runtime");
    private static final String JDBC = "jdbc:mysql://127.0.0.1:33340/fund_test";
    private final ObjectMapper json = new ObjectMapper();
    private DriverManagerDataSource dataSource;
    private JdbcTemplate jdbc;
    private DataSourceTransactionManager manager;
    private TransactionTemplate transaction;
    private AuthNewAccountCreator creator;
    private AuthNewAccountCreator otherCreator;
    private EventOutboxMapper outbox;
    private final List<String> statements = Collections.synchronizedList(new ArrayList<>());
    private final Map<Long,String> walletNonces = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<Long,String> walletMethods = new java.util.concurrent.ConcurrentHashMap<>();

    @BeforeEach void strictPrivateTargetAndActualPlusSpringWriters() throws Exception {
        String supplied = System.getenv("SUPPORT_FUND_JOURNAL_TEST_CONFIG");
        String sha = System.getenv("SUPPORT_FUND_JOURNAL_TEST_CONFIG_SHA256");
        assertThat(supplied).as("Private SQL_ONLY config required").isNotBlank();
        assertThat(sha).matches("[0-9a-f]{64}");
        Path path = Path.of(supplied).toAbsolutePath().normalize();
        assertThat(path.getFileName().toString()).isEqualTo("binding-private.json");
        assertThat(path.getParent().getParent()).isEqualTo(RUNTIME);
        assertThat(path.getParent().getFileName().toString()).matches("fund-journal-sql-r22-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
        assertThat(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)).isTrue();
        assertThat(path.toRealPath()).isEqualTo(path);
        byte[] bytes = Files.readAllBytes(path);
        assertThat(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))).isEqualTo(sha);
        JsonNode config;
        try {
            config = new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(bytes);
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new IllegalStateException("Malformed private fixture config");
        }
        assertThat(config.isObject()).isTrue(); assertThat(config.size()).isEqualTo(9);
        assertThat(config.path("schemaVersion").isIntegralNumber()).isTrue();
        assertThat(config.path("schemaVersion").canConvertToInt()).isTrue();
        assertThat(config.path("schemaVersion").intValue()).isEqualTo(1);
        assertThat(config.path("purpose").textValue()).isEqualTo("SQL_ONLY");
        String runId = config.path("runId").textValue();
        assertThat(runId).matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
        assertThat(UUID.fromString(runId).toString()).isEqualTo(runId);
        assertThat(path.getParent()).isEqualTo(RUNTIME.resolve("fund-journal-sql-r22-" + runId));
        assertThat(config.path("port").isIntegralNumber()).isTrue(); assertThat(config.path("port").canConvertToInt()).isTrue();
        assertThat(config.path("port").intValue()).isEqualTo(33340);
        assertThat(config.path("database").textValue()).isEqualTo("fund_test");
        assertThat(config.path("jdbcUrl").textValue()).isEqualTo(JDBC);
        assertThat(config.path("username").textValue()).isEqualTo("fund_app");
        String password = config.path("password").textValue();
        if (password == null || password.isBlank()) throw new IllegalStateException("Private fixture credential required");
        String serverUuid = config.path("serverUuid").textValue();
        assertThat(serverUuid).matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
        dataSource = new DriverManagerDataSource(JDBC, "fund_app", password);
        Properties properties = new Properties();
        properties.setProperty("useSSL", "false"); properties.setProperty("allowPublicKeyRetrieval", "true");
        properties.setProperty("serverTimezone", "UTC"); properties.setProperty("connectTimeout", "3000"); properties.setProperty("socketTimeout", "20000");
        dataSource.setConnectionProperties(properties);
        jdbc = new JdbcTemplate(dataSource);
        Map<String,Object> identity = jdbc.queryForMap("SELECT @@server_uuid uuid,@@port port,DATABASE() db,VERSION() version,CURRENT_USER() account");
        assertThat(identity.get("uuid")).isEqualTo(serverUuid); assertThat(((Number) identity.get("port")).intValue()).isEqualTo(33340);
        assertThat(identity.get("db")).isEqualTo("fund_test"); assertThat(identity.get("version")).isEqualTo("8.0.46");
        assertThat((String) identity.get("account")).startsWith("fund_app@");
        List<Map<String,Object>> markers = jdbc.queryForList("SELECT run_id,purpose FROM nx_sql_fixture_identity");
        assertThat(markers).hasSize(1); assertThat(markers.get(0)).containsEntry("run_id", runId).containsEntry("purpose", "SQL_ONLY");

        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        GlobalConfig global = new GlobalConfig(); global.setDbConfig(new GlobalConfig.DbConfig());
        global.setSqlInjector(new DefaultSqlInjector());
        global.setMetaObjectHandler(new MybatisMetaObjectHandler(Clock.system(ZoneId.of("Asia/Shanghai"))));
        GlobalConfigUtils.setGlobalConfig(configuration, global);
        configuration.addInterceptor(new PhysicalWriterCheck());
        for (Class<?> type : List.of(UserOpsMapper.class, AuthRegistrationBirthMapper.class, SupportFundJournalMapper.class, EventOutboxMapper.class))
            configuration.addMapper(type);
        MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(dataSource); factory.setConfiguration(configuration); factory.setGlobalConfig(global);
        factory.setTransactionFactory(new SpringManagedTransactionFactory());
        var session = new SqlSessionTemplate(factory.getObject());
        var userMapper = session.getMapper(UserOpsMapper.class);
        var authMapper = session.getMapper(AuthRegistrationBirthMapper.class);
        var fundMapper = session.getMapper(SupportFundJournalMapper.class);
        outbox = session.getMapper(EventOutboxMapper.class);
        assertThat(configuration.getMappedStatement(UserOpsMapper.class.getName()+".insert").getKeyGenerator()).isInstanceOf(Jdbc3KeyGenerator.class);
        assertThat(configuration.getMappedStatement(UserOpsMapper.class.getName()+".ensureNewRegisteredUserWallet").getBoundSql(Map.of("userId", 1L, "sandbox", 0, "incarnation", UUID.randomUUID().toString())).getParameterMappings())
            .extracting(mapping -> mapping.getProperty()).contains("incarnation");
        manager = new DataSourceTransactionManager(dataSource);
        transaction = template(TransactionDefinition.PROPAGATION_REQUIRED);
        UserRegistrationWriteFacade users = proxy(new UserRegistrationWriteAdapter(userMapper));
        SupportFundJournalFacade binding = proxy(new SupportFundJournalBindingService(fundMapper, dataSource, json));
        SupportFundAuthBirthFacade funds = proxy(new SupportFundAuthBirthAdapter(fundMapper, binding, json));
        creator = proxy(new AuthNewAccountCreator(users, authMapper, funds, dataSource, json));
        otherCreator = proxy(new AuthNewAccountCreator(users, authMapper, funds, dataSource, json));
    }

    @Test void realGeneratedUserAndPrivateWalletNonceCommitOneBirthOperationPartyAndLeg() {
        String event = eventId(); AtomicLong customer = new AtomicLong(), writerConnection = new AtomicLong();
        Outcome outcome = transaction.execute(status -> {
            writerConnection.set(jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class));
            assertThat(jdbc.queryForObject("SELECT @@transaction_isolation", String.class)).isEqualTo("REPEATABLE-READ");
            BirthHandle handle = creator.insertNew(command(0)); customer.set(handle.customerId());
            creator.ensureWallet(handle); sourceEvent(customer.get(), event, "VALID");
            return creator.complete(handle, event);
        });
        assertThat(outcome).isEqualTo(Outcome.BOUND);
        assertThat(jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class)).isNotEqualTo(writerConnection.get());
        persistedBirth(customer.get(), event);
        assertThat(walletMethods.get(customer.get())).isEqualTo("ensureNewRegisteredUserWallet");
        assertThat(walletNonces.get(customer.get())).matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
        assertThat(jdbc.queryForObject("SELECT fund_incarnation FROM nx_user_wallet WHERE user_id=?", String.class, customer.get())).isEqualTo(walletNonces.get(customer.get()));
        assertThat(statements).contains("INSERT INTO nx_user ", "ensureNewRegisteredUserWallet", "insertEvent");
    }

    @Test void realFutureAccountOrphanPreservesOldMoneyAndIdentityWithoutClaimingBirth() {
        for (boolean restored : List.of(false, true)) {
            long candidate = nextUserId();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_user WHERE id=?", Long.class, candidate)).isZero();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_user_wallet WHERE user_id=?", Long.class, candidate)).isZero();
            jdbc.update("INSERT INTO nx_user_wallet(user_id,usdt_available,nex_available,pending_withdraw,cregis_risk_held,lifetime_earned,version,sandbox,is_deleted) VALUES(?,12,8,3,2,6,11,?,?)", candidate, restored ? 1 : 0, restored ? 1 : 0);
            Map<String,Object> old = wallet(candidate); String event = eventId();
            Outcome outcome = transaction.execute(status -> {
                BirthHandle handle = creator.insertNew(command(0)); assertThat(handle.customerId()).isEqualTo(candidate);
                creator.ensureWallet(handle); sourceEvent(candidate, event, "VALID"); return creator.complete(handle, event);
            });
            assertThat(outcome).isEqualTo(Outcome.UNKNOWN);
            Map<String,Object> current = wallet(candidate);
            for (String field : List.of("id", "user_id", "fund_incarnation", "usdt_available", "nex_available", "pending_withdraw", "cregis_risk_held", "lifetime_earned", "version", "created_at"))
                assertThat(current.get(field)).as(field).isEqualTo(old.get(field));
            assertThat(walletNonces.get(candidate)).isNotEqualTo(old.get("fund_incarnation"));
            assertThat(current.get("sandbox")).isEqualTo(false); assertThat(((Number) current.get("is_deleted")).intValue()).isZero();
            assertThat(((Number) current.get("fund_revision")).longValue()).isEqualTo(restored ? 1 : 0);
            assertThat(jdbc.queryForList("SELECT observation FROM nx_support_wallet_journal WHERE new_user_id=? ORDER BY revision", String.class, candidate))
                .containsExactlyElementsOf(restored ? List.of("NEW_UNPROVEN", "CHANGE") : List.of("NEW_UNPROVEN"));
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_user WHERE id=? AND status='ACTIVE' AND sandbox=0 AND is_deleted=0", Long.class, candidate)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_event_outbox WHERE event_id=?", Long.class, event)).isEqualTo(1);
            noBirthBinding(candidate);
        }
    }

    @Test void wrongOriginalOutboxFactsRejectAndRollBackAllNewWritesButPreserveBlocker() {
        String blocker = eventId(); transaction.execute(status -> { sourceEvent(Long.MAX_VALUE, blocker, "VALID"); return null; });
        for (String bad : List.of("MALFORMED_PAYLOAD", "FOREIGN_CUSTOMER", "WRONG_EVENT", "NON_AUTHORITATIVE", "UNREGISTERED", "MISSING")) {
            AtomicLong customer = new AtomicLong(); String event = eventId();
            var failure = assertThatThrownBy(() -> transaction.execute(status -> {
                BirthHandle handle = creator.insertNew(command(0)); customer.set(handle.customerId()); creator.ensureWallet(handle);
                if (!bad.equals("MISSING")) sourceEvent(customer.get(), event, bad);
                if (bad.equals("NON_AUTHORITATIVE")) {
                    assertThatThrownBy(() -> creator.complete(handle, event)).hasMessage("AUTH_BIRTH_EVENT_MISMATCH");
                    return null;
                }
                return creator.complete(handle, event);
            }));
            if (bad.equals("NON_AUTHORITATIVE")) failure.isInstanceOf(UnexpectedRollbackException.class);
            else failure.isInstanceOf(IllegalStateException.class).hasMessage(bad.equals("MALFORMED_PAYLOAD") || bad.equals("FOREIGN_CUSTOMER")
                ? "AUTH_BIRTH_PAYLOAD_MISMATCH" : "AUTH_BIRTH_EVENT_MISMATCH");
            rolledBack(customer.get(), event);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_event_outbox WHERE event_id=?", Long.class, blocker)).isEqualTo(1);
        }
    }

    @Test void laterOuterFailureRollsBackCommittedCandidateFactsAndInvalidatesHandleAtCompletion() {
        AtomicReference<BirthHandle> saved = new AtomicReference<>(); AtomicInteger completion = new AtomicInteger(-1);
        String event = eventId();
        assertThatThrownBy(() -> transaction.execute(status -> {
            BirthHandle handle = creator.insertNew(command(0)); saved.set(handle); creator.ensureWallet(handle);
            sourceEvent(handle.customerId(), event, "VALID");
            assertThat(creator.complete(handle, event)).isEqualTo(Outcome.BOUND);
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCompletion(int result) { completion.set(result); }
            });
            throw new IllegalStateException("SQL_ONLY_OUTER_FAILURE");
        })).hasMessage("SQL_ONLY_OUTER_FAILURE");
        assertThat(completion.get()).isEqualTo(TransactionSynchronization.STATUS_ROLLED_BACK);
        rolledBack(saved.get().customerId(), event);
        assertThatThrownBy(() -> transaction.execute(status -> { creator.ensureWallet(saved.get()); return null; }))
            .hasMessage("AUTH_BIRTH_TOKEN_INVALID");
    }

    @Test void liveHandleRejectsForeignPhysicalTransactionCrossThreadAndExpiredReuse() throws Exception {
        AtomicReference<BirthHandle> active = new AtomicReference<>(); AtomicInteger completion = new AtomicInteger(-1);
        AtomicLong customer = new AtomicLong(); String event = eventId();
        transaction.execute(status -> {
            BirthHandle handle = creator.insertNew(command(0)); active.set(handle); customer.set(handle.customerId());
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCompletion(int result) { completion.set(result); }
            });
            int before = statements.size();
            // Guard rejects before any SQL: the inner transaction never acquires wallet/source locks.
            assertThatThrownBy(() -> template(TransactionDefinition.PROPAGATION_REQUIRES_NEW).execute(inner -> {
                creator.ensureWallet(handle); return null;
            })).hasMessage("AUTH_BIRTH_TOKEN_INVALID");
            assertThat(statements).hasSize(before);
            var worker = Executors.newSingleThreadExecutor();
            try {
                var failure = worker.submit(() -> {
                    try { transaction.execute(other -> { creator.ensureWallet(handle); return null; }); return (Throwable) null; }
                    catch (Throwable error) { return error; }
                });
                assertThat(failure.get(10, TimeUnit.SECONDS)).isInstanceOf(IllegalStateException.class).hasMessage("AUTH_BIRTH_TOKEN_INVALID");
            } catch (Exception ex) { throw new IllegalStateException("SQL_ONLY_HANDLE_WORKER_FAILED", ex); }
            finally {
                worker.shutdownNow();
                try {
                    if (!worker.awaitTermination(25, TimeUnit.SECONDS)) throw new IllegalStateException("SQL_ONLY_HANDLE_WORKER_STILL_RUNNING");
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt(); throw new IllegalStateException("SQL_ONLY_HANDLE_WORKER_INTERRUPTED", ex);
                }
            }
            assertThat(statements).hasSize(before);
            creator.ensureWallet(handle); sourceEvent(customer.get(), event, "VALID");
            return creator.complete(handle, event);
        });
        assertThat(completion.get()).isEqualTo(TransactionSynchronization.STATUS_COMMITTED);
        persistedBirth(customer.get(), event);
        assertThatThrownBy(() -> transaction.execute(status -> { creator.complete(active.get(), event); return null; }))
            .hasMessage("AUTH_BIRTH_TOKEN_INVALID");
        persistedBirth(customer.get(), event);
        BirthHandle expiredUnused = transaction.execute(status -> creator.insertNew(command(0)));
        assertThatThrownBy(() -> transaction.execute(status -> { creator.ensureWallet(expiredUnused); return null; }))
            .hasMessage("AUTH_BIRTH_TOKEN_INVALID");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_user WHERE id=?", Long.class, expiredUnused.customerId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_user_wallet WHERE user_id=?", Long.class, expiredUnused.customerId())).isZero();
        noBirthBinding(expiredUnused.customerId());
        AtomicLong foreignCustomer = new AtomicLong();
        assertThatThrownBy(() -> transaction.execute(status -> {
            BirthHandle handle = creator.insertNew(command(0)); foreignCustomer.set(handle.customerId());
            otherCreator.ensureWallet(handle); return null;
        })).hasMessage("AUTH_BIRTH_TOKEN_INVALID");
        rolledBack(foreignCustomer.get(), eventId());
    }

    @Test void sandboxUsesLegacyTwoParameterWalletAndIsExcludedWithoutFundBinding() {
        AtomicLong customer = new AtomicLong();
        assertThat(transaction.<Outcome>execute(status -> {
            BirthHandle handle = creator.insertNew(command(1)); customer.set(handle.customerId()); creator.ensureWallet(handle);
            return creator.complete(handle, null);
        })).isEqualTo(Outcome.EXCLUDED);
        assertThat(walletMethods.get(customer.get())).isEqualTo("ensureRegisteredUserWallet");
        assertThat(walletNonces).doesNotContainKey(customer.get());
        Map<String,Object> wallet = wallet(customer.get());
        assertThat(wallet.get("sandbox")).isEqualTo(true);
        assertThat(jdbc.queryForObject("SELECT sandbox FROM nx_user WHERE id=?", Integer.class, customer.get())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT observation FROM nx_support_wallet_journal WHERE new_user_id=? AND revision=0", String.class, customer.get())).isEqualTo("NEW_ZERO");
        noBirthBinding(customer.get());
    }

    private void sourceEvent(long customer, String event, String mode) {
        ObjectNode payload = json.createObjectNode(); payload.put("userId", customer); payload.put("user_id", customer);
        payload.put("event_id", event); payload.put("event_name", "auth.register_completed"); payload.put("ts", System.currentTimeMillis());
        for (String nullable : List.of("anon_id", "session_id", "ref", "source")) payload.putNull(nullable);
        payload.put("phase", "SYSTEM"); payload.put("account_age_months", 0);
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Shanghai"));
        String cohort = String.format("%d-W%02d", today.get(WeekFields.ISO.weekBasedYear()), today.get(WeekFields.ISO.weekOfWeekBasedYear()));
        payload.put("cohort", cohort); payload.put("platform", "server"); payload.put("app_version", "backend"); payload.put("locale", "und");
        boolean authoritative = !mode.equals("NON_AUTHORITATIVE"), registered = !mode.equals("UNREGISTERED");
        payload.put("is_server_authoritative", authoritative); payload.put("schema_revision", 7);
        if (mode.equals("MALFORMED_PAYLOAD")) payload.put("user_id", String.valueOf(customer));
        if (mode.equals("FOREIGN_CUSTOMER")) payload.put("userId", customer + 1);
        String type = mode.equals("WRONG_EVENT") ? "SQL_ONLY_WRONG_EVENT" : "auth.register_completed";
        assertThat(outbox.insertEvent(event, "USER_REGISTRATION", String.valueOf(customer), type,
            "auth.register_completed", "auth", "SYSTEM", 0, cohort, authoritative, 7, registered, true, payload.toString())).isEqualTo(1);
    }

    private static NewAccountCommand command(int sandbox) {
        String unique = UUID.randomUUID().toString().replace("-", "");
        return new NewAccountCommand("+999", "sql-only-" + unique.substring(0, 20), "SQL_ONLY",
            "SQL_ONLY_NO_PASSWORD", "SQL_ONLY", unique, null, null, "L1", "V0", "ACTIVE", sandbox, "en-US");
    }
    private static String eventId() { return UUID.randomUUID().toString().replace("-", ""); }
    private long nextUserId() {
        return jdbc.execute((ConnectionCallback<Long>) connection -> {
            try (var statement = connection.createStatement()) {
                // AUTO_INCREMENT metadata is cached; both statements must use this one connection.
                statement.execute("SET SESSION information_schema_stats_expiry=0");
                try (var result = statement.executeQuery("SELECT AUTO_INCREMENT FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_user'")) {
                    assertThat(result.next()).isTrue(); long next = result.getLong(1);
                    assertThat(result.wasNull()).isFalse();
                    assertThat(next).as("Exclusive fixture next generated user ID").isPositive(); return next;
                }
            }
        });
    }
    private Map<String,Object> wallet(long customer) {
        return jdbc.queryForMap("SELECT id,user_id,usdt_available,nex_available,pending_withdraw,cregis_risk_held,lifetime_earned,version,sandbox,is_deleted,created_at,fund_incarnation,fund_revision FROM nx_user_wallet WHERE user_id=?", customer);
    }
    private void persistedBirth(long customer, String event) {
        Map<String,Object> wallet = wallet(customer);
        for (String bucket : List.of("usdt_available", "nex_available", "pending_withdraw", "cregis_risk_held"))
            assertThat((BigDecimal) wallet.get(bucket)).as(bucket).isEqualByComparingTo("0");
        assertThat(((Number) wallet.get("fund_revision")).longValue()).isZero(); assertThat(((Number) wallet.get("version")).longValue()).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_user WHERE id=? AND sandbox=0 AND is_deleted=0 AND status='ACTIVE'", Long.class, customer)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_event_outbox WHERE event_id=? AND aggregate_id=?", Long.class, event, String.valueOf(customer))).isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT observation FROM nx_support_wallet_journal WHERE new_user_id=? ORDER BY revision", String.class, customer)).containsExactly("NEW_ZERO");
        Map<String,Object> operation = jdbc.queryForMap("SELECT id,ordered_events,source_snapshot,source_proof,source_key FROM nx_support_fund_operation WHERE op_key=?", "AUTH_FUND_BIRTH:" + customer);
        long id = ((Number) operation.get("id")).longValue();
        assertThat(operation.get("ordered_events")).isEqualTo("[]"); assertThat(operation.get("source_key")).isEqualTo(event);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_support_fund_operation WHERE op_key=?", Long.class, "AUTH_FUND_BIRTH:" + customer)).isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT customer_id FROM nx_support_fund_operation_party WHERE operation_id=? ORDER BY customer_id", Long.class, id)).containsExactly(customer);
        assertThat(jdbc.queryForList("SELECT revision FROM nx_support_fund_operation_leg WHERE operation_id=? AND wallet_incarnation=? ORDER BY revision", Long.class, id, wallet.get("fund_incarnation"))).containsExactly(0L);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_support_fund_operation_leg WHERE operation_id=?", Long.class, id)).isEqualTo(1);
        assertThat((String) operation.get("source_snapshot")).doesNotContain("phone", "password", "nickname");
        // Re-read through independent, autocommit JDBC connections to prove persisted source/fund joins.
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_support_fund_operation_party p JOIN nx_support_fund_operation_leg l ON l.operation_id=p.operation_id JOIN nx_support_fund_operation o ON o.id=p.operation_id JOIN nx_user_wallet w ON w.user_id=p.customer_id AND w.fund_incarnation=l.wallet_incarnation JOIN nx_user u ON u.id=w.user_id JOIN nx_event_outbox e ON e.event_id=o.source_key WHERE p.customer_id=? AND l.revision=0 AND e.event_id=?", Long.class, customer, event)).isEqualTo(1);
    }
    private void noBirthBinding(long customer) {
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_support_fund_operation WHERE op_key=?", Long.class, "AUTH_FUND_BIRTH:" + customer)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_support_fund_operation_party WHERE customer_id=?", Long.class, customer)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_support_fund_operation_leg l JOIN nx_support_wallet_journal j ON j.wallet_incarnation=l.wallet_incarnation AND j.revision=l.revision WHERE j.new_user_id=?", Long.class, customer)).isZero();
        String nonce = walletNonces.get(customer);
        if (nonce != null)
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_support_fund_operation_leg WHERE wallet_incarnation=?", Long.class, nonce)).isZero();
    }
    private void rolledBack(long customer, String event) {
        assertThat(customer).isPositive();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_user WHERE id=?", Long.class, customer)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_user_wallet WHERE user_id=?", Long.class, customer)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_support_wallet_journal WHERE new_user_id=? OR old_user_id=?", Long.class, customer, customer)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_event_outbox WHERE event_id=?", Long.class, event)).isZero(); noBirthBinding(customer);
    }
    private TransactionTemplate template(int propagation) {
        var result = new TransactionTemplate(manager); result.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        result.setPropagationBehavior(propagation); result.setTimeout(20); return result;
    }
    @SuppressWarnings("unchecked") private <T> T proxy(T target) {
        var factory = new ProxyFactory(target); factory.setProxyTargetClass(true);
        factory.addAdvice(new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource())); return (T) factory.getProxy();
    }

    @Intercepts(@Signature(type=StatementHandler.class, method="prepare", args={Connection.class, Integer.class}))
    public final class PhysicalWriterCheck implements Interceptor {
        @Override public Object intercept(Invocation invocation) throws Throwable {
            Object resource = TransactionSynchronizationManager.getResource(dataSource);
            assertThat(resource).isInstanceOf(ConnectionHolder.class);
            Connection connection = (Connection) invocation.getArgs()[0];
            assertThat(connection).isSameAs(((ConnectionHolder) resource).getConnection()); assertThat(connection.getAutoCommit()).isFalse();
            var statement = ((StatementHandler) invocation.getTarget()).getBoundSql();
            String sql = statement.getSql().stripLeading();
            if (sql.startsWith("INSERT INTO nx_user ")) statements.add("INSERT INTO nx_user ");
            if (sql.startsWith("INSERT INTO nx_user_wallet")) {
                var params = (Map<?,?>) statement.getParameterObject(); long customer = ((Number) params.get("userId")).longValue();
                String method = params.containsKey("incarnation") ? "ensureNewRegisteredUserWallet" : "ensureRegisteredUserWallet";
                walletMethods.put(customer, method); statements.add(method);
                if (params.containsKey("incarnation")) walletNonces.put(customer, (String) params.get("incarnation"));
            }
            if (sql.startsWith("INSERT INTO nx_event_outbox")) statements.add("insertEvent");
            statements.add(sql.startsWith("SELECT") ? "SELECT" : "WRITE");
            return invocation.proceed();
        }
    }
}
