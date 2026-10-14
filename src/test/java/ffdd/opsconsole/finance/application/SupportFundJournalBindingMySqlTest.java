package ffdd.opsconsole.finance.application;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.finance.facade.SupportFundJournalFacade;
import ffdd.opsconsole.finance.facade.SupportFundJournalFacade.Before;
import ffdd.opsconsole.finance.facade.SupportFundJournalFacade.BoundReceipt;
import ffdd.opsconsole.finance.facade.SupportFundJournalFacade.ReceiptInput;
import ffdd.opsconsole.finance.mapper.SupportFundJournalMapper;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.ibatis.executor.statement.StatementHandler;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Signature;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.UnexpectedRollbackException;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;

/** SQL_ONLY fixture: actual SQL/transactions, no production source, birth authorization or READY proof. */
@EnabledIfEnvironmentVariable(named="SUPPORT_FUND_JOURNAL_MYSQL_IT", matches="true")
class SupportFundJournalBindingMySqlTest {
    static final int EXPECTED_TESTS = 14;
    private static final Path RUNTIME = Path.of("D:/CodexData/test-environments/workflow-runs/support-analytics-20261007-r2/runtime");
    private static final String JDBC = "jdbc:mysql://127.0.0.1:33340/fund_test";
    private static final AtomicLong CUSTOMERS = new AtomicLong(System.currentTimeMillis() * 1000);
    private DriverManagerDataSource dataSource;
    private JdbcTemplate jdbc;
    private TransactionTemplate transaction;
    private SupportFundJournalFacade binder;
    private SupportFundJournalMapper mapper;
    private long collidePartyCustomer;
    private final AtomicReference<String> insertedDigest = new AtomicReference<>();
    private final List<Long> walletLocks = Collections.synchronizedList(new ArrayList<>());
    private final AtomicInteger physicalStatements = new AtomicInteger();

    @BeforeEach void exclusiveFixtureAndRealSpringMyBatis() throws Exception {
        String supplied = System.getenv("SUPPORT_FUND_JOURNAL_TEST_CONFIG");
        String sha = System.getenv("SUPPORT_FUND_JOURNAL_TEST_CONFIG_SHA256");
        assertThat(supplied).as("Private fixture config required").isNotBlank();
        assertThat(sha).as("Private fixture config SHA required").matches("[0-9a-f]{64}");
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
        assertThat(config.isObject()).isTrue();
        assertThat(config.size()).isEqualTo(9);
        assertThat(config.path("schemaVersion").isIntegralNumber()).isTrue();
        assertThat(config.path("schemaVersion").canConvertToInt()).isTrue();
        assertThat(config.path("schemaVersion").intValue()).isEqualTo(1);
        assertThat(config.path("purpose").textValue()).isEqualTo("SQL_ONLY");
        String runId = config.path("runId").textValue();
        assertThat(runId).matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
        assertThat(UUID.fromString(runId).toString()).isEqualTo(runId);
        assertThat(path.getParent()).isEqualTo(RUNTIME.resolve("fund-journal-sql-r22-" + runId));
        assertThat(config.path("port").isIntegralNumber()).isTrue();
        assertThat(config.path("port").canConvertToInt()).isTrue();
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
        assertThat(identity.get("uuid")).isEqualTo(serverUuid);
        assertThat(((Number) identity.get("port")).intValue()).isEqualTo(33340);
        assertThat(identity.get("db")).isEqualTo("fund_test"); assertThat(identity.get("version")).isEqualTo("8.0.46");
        assertThat((String) identity.get("account")).startsWith("fund_app@");
        List<Map<String,Object>> markers = jdbc.queryForList("SELECT run_id,purpose FROM nx_sql_fixture_identity");
        assertThat(markers).hasSize(1); assertThat(markers.get(0)).containsEntry("run_id", runId).containsEntry("purpose", "SQL_ONLY");
        Configuration configuration = new Configuration(new Environment("fund-binding-sql-only", new SpringManagedTransactionFactory(), dataSource));
        configuration.addMapper(SupportFundJournalMapper.class);
        configuration.addInterceptor(new PhysicalConnectionCheck());
        mapper = new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(configuration)).getMapper(SupportFundJournalMapper.class);
        var manager = new DataSourceTransactionManager(dataSource);
        transaction = new TransactionTemplate(manager);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        transaction.setTimeout(20);
        var factory = new ProxyFactory(new SupportFundJournalBindingService(mapper, dataSource, new ObjectMapper()));
        factory.addAdvice(new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
        binder = (SupportFundJournalFacade) factory.getProxy();
    }

    @Test void rawBirthAndCompoundRevisionsCommitAndReadBackThroughNewConnections() {
        long customer = customer(); String key = key();
        BoundReceipt receipt = transaction.execute(status -> {
            insertWallet(customer);
            Before token = binder.beginBirth(customer);
            jdbc.update("UPDATE nx_user_wallet SET usdt_available=10,nex_available=4,version=version+1 WHERE user_id=?", customer);
            jdbc.update("UPDATE nx_user_wallet SET usdt_available=6,nex_available=3,pending_withdraw=3,cregis_risk_held=1,version=version+1 WHERE user_id=?", customer);
            return binder.bind(token, input(key, "[]"));
        });
        assertThat(receipt.journalLegs()).isEqualTo(3);
        assertThat(jdbc.queryForList("SELECT revision,observation FROM nx_support_wallet_journal WHERE new_user_id=? ORDER BY revision", customer))
            .extracting(row -> ((Number) row.get("revision")).longValue()).containsExactly(0L, 1L, 2L);
        assertThat(jdbc.queryForObject("SELECT observation FROM nx_support_wallet_journal WHERE new_user_id=? AND revision=0", String.class, customer)).isEqualTo("NEW_ZERO");
        assertThat(jdbc.queryForMap("SELECT usdt_available,nex_available,pending_withdraw,cregis_risk_held,fund_revision,version FROM nx_user_wallet WHERE user_id=?", customer))
            .containsEntry("usdt_available", new java.math.BigDecimal("6.000000")).containsEntry("nex_available", new java.math.BigDecimal("3.000000"))
            .containsEntry("pending_withdraw", new java.math.BigDecimal("3.000000")).containsEntry("cregis_risk_held", new java.math.BigDecimal("1.000000"))
            .containsEntry("fund_revision", 2L).containsEntry("version", 2L);
        persisted(receipt, customer, 0, 1, 2);
        assertThat(physicalStatements.get()).isGreaterThan(0);
    }

    @Test void ordinaryBeginBindsAllUpdatesWithoutRetrospectivelyBindingBirth() {
        long customer = customer(); insertWallet(customer); String key = key();
        BoundReceipt receipt = transaction.execute(status -> {
            Before token = binder.begin(List.of(customer));
            jdbc.update("UPDATE nx_user_wallet SET usdt_available=2,version=version+1 WHERE user_id=?", customer);
            jdbc.update("UPDATE nx_user_wallet SET nex_available=3,version=version+1 WHERE user_id=?", customer);
            return binder.bind(token, input(key, deposit(customer)));
        });
        assertThat(receipt.journalLegs()).isEqualTo(2); persisted(receipt, customer, 1, 2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_support_fund_operation_leg l JOIN nx_user_wallet w ON w.fund_incarnation=l.wallet_incarnation WHERE w.user_id=? AND l.revision=0", Long.class, customer)).isZero();
    }

    @Test void exactZeroCashDuplicateReplaysButChangedReceiptRollsBack() {
        long customer = customer(); insertWallet(customer); String key = key();
        BoundReceipt first = transaction.execute(status -> binder.bind(binder.begin(List.of(customer)), input(key, "[]")));
        BoundReceipt replay = transaction.execute(status -> binder.bind(binder.begin(List.of(customer)), input(key, "[]")));
        assertThat(replay).isEqualTo(first); persisted(first, customer);
        assertThat(transaction.<List<Long>>execute(status -> mapper.customerOperations(customer))).containsExactly(first.operationId());
        assertThatThrownBy(() -> transaction.execute(status -> binder.bind(binder.begin(List.of(customer)), input(key, deposit(customer)))))
            .isInstanceOf(IllegalStateException.class).hasMessage("CONFLICTING_FUND_OPERATION_RECEIPT");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_support_fund_operation WHERE op_key=?", Long.class, key)).isEqualTo(1);
        persisted(first, customer);
    }

    @Test void illegalActionCaughtByOuterWriterStillRollsBackAllFourTables() {
        long customer = customer(); insertWallet(customer); String key = key();
        assertThatThrownBy(() -> transaction.execute(status -> {
            Before token = binder.begin(List.of(customer));
            jdbc.update("UPDATE nx_user_wallet SET usdt_available=9,version=version+1 WHERE user_id=?", customer);
            assertThatThrownBy(() -> binder.bind(token, input(key, deposit(customer).replace("\"DEPOSIT\"", "0"))))
                .isInstanceOf(IllegalArgumentException.class);
            return null;
        })).isInstanceOf(UnexpectedRollbackException.class);
        unchanged(customer, key);
    }

    @Test void uniqueLegCollisionRollsBackWalletJournalAndNewOperation() {
        long customer = customer(); insertWallet(customer); String key = key();
        long blocker = seedOperation(key());
        String incarnation = jdbc.queryForObject("SELECT fund_incarnation FROM nx_user_wallet WHERE user_id=?", String.class, customer);
        jdbc.update("INSERT INTO nx_support_fund_operation_leg(operation_id,wallet_incarnation,revision) VALUES(?,?,1)", blocker, incarnation);
        assertThatThrownBy(() -> transaction.execute(status -> {
            Before token = binder.begin(List.of(customer));
            jdbc.update("UPDATE nx_user_wallet SET usdt_available=9,version=version+1 WHERE user_id=?", customer);
            return binder.bind(token, input(key, "[]"));
        })).isInstanceOf(DuplicateKeyException.class);
        unchanged(customer, key);
        assertThat(jdbc.queryForObject("SELECT operation_id FROM nx_support_fund_operation_leg WHERE wallet_incarnation=? AND revision=1", Long.class, incarnation)).isEqualTo(blocker);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_support_fund_operation WHERE id=?", Long.class, blocker)).isEqualTo(1);
    }

    @Test void appCanReadJournalButCannotInsertUpdateOrDeleteIt() {
        long customer = customer(); insertWallet(customer);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_support_wallet_journal WHERE new_user_id=?", Long.class, customer)).isEqualTo(1);
        for (String sql : List.of("INSERT INTO nx_support_wallet_journal SELECT * FROM nx_support_wallet_journal WHERE new_user_id=?",
                "UPDATE nx_support_wallet_journal SET observation=observation WHERE new_user_id=?", "DELETE FROM nx_support_wallet_journal WHERE new_user_id=?")) {
            assertThatThrownBy(() -> jdbc.update(sql, customer)).isInstanceOf(DataAccessException.class)
                .satisfies(error -> assertThat(((SQLException) ((DataAccessException) error).getMostSpecificCause()).getErrorCode()).isEqualTo(1142));
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_support_wallet_journal WHERE new_user_id=?", Long.class, customer)).isEqualTo(1);
    }

    @Test void sortedMultiWalletScopeAndActualMyBatisStatementsSharePhysicalConnection() {
        long first = customer(), second = customer(); insertWallet(first); insertWallet(second); String key = key();
        BoundReceipt receipt = transaction.execute(status -> {
            assertThat(jdbc.queryForObject("SELECT @@transaction_isolation", String.class)).isEqualTo("REPEATABLE-READ");
            long connectionId = jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class);
            Connection physical = ((ConnectionHolder) TransactionSynchronizationManager.getResource(dataSource)).getConnection();
            Before token = binder.begin(List.of(second, first, second));
            jdbc.execute((ConnectionCallback<Void>) connection -> { assertThat(DataSourceUtils.getTargetConnection(connection)).isSameAs(physical); return null; });
            jdbc.update("UPDATE nx_user_wallet SET usdt_available=2,version=version+1 WHERE user_id=?", first);
            jdbc.update("UPDATE nx_user_wallet SET nex_available=3,version=version+1 WHERE user_id=?", second);
            BoundReceipt bound = binder.bind(token, input(key, "[]"));
            assertThat(jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class)).isEqualTo(connectionId);
            return bound;
        });
        assertThat(walletLocks).containsExactly(first, second, first, second);
        assertThat(physicalStatements.get()).isGreaterThanOrEqualTo(10);
        assertThat(receipt.journalLegs()).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_support_fund_operation_leg WHERE operation_id=?", Long.class, receipt.operationId())).isEqualTo(2);
        assertThat(jdbc.queryForList("SELECT customer_id FROM nx_support_fund_operation_party WHERE operation_id=? ORDER BY customer_id", Long.class, receipt.operationId()))
            .containsExactly(first, second);
        assertThat(transaction.<List<Long>>execute(status -> mapper.customerOperations(first))).containsExactly(receipt.operationId());
        assertThat(transaction.<List<Long>>execute(status -> mapper.customerOperations(second))).containsExactly(receipt.operationId());
    }

    @Test void zeroEventZeroCashMultiPartyReceiptRemainsEnumerableAfterCommit() {
        long first = customer(), second = customer(); insertWallet(first); insertWallet(second);
        BoundReceipt receipt = transaction.execute(status -> binder.bind(binder.begin(List.of(second, first, second)), input(key(), "[]")));
        assertThat(receipt.journalLegs()).isZero();
        assertThat(jdbc.queryForList("SELECT customer_id FROM nx_support_fund_operation_party WHERE operation_id=? ORDER BY customer_id", Long.class, receipt.operationId()))
            .containsExactly(first, second);
        assertThat(transaction.<List<Long>>execute(status -> mapper.customerOperations(first))).containsExactly(receipt.operationId());
        assertThat(transaction.<List<Long>>execute(status -> mapper.customerOperations(second))).containsExactly(receipt.operationId());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_support_fund_operation_leg WHERE operation_id=?", Long.class, receipt.operationId())).isZero();
    }

    @Test void sameZeroCashKeyWithDifferentParticipantsConflictsWithoutNewParties() {
        long first = customer(), second = customer(); insertWallet(first); insertWallet(second); String key = key();
        BoundReceipt receipt = transaction.execute(status -> binder.bind(binder.begin(List.of(first)), input(key, "[]")));
        assertThatThrownBy(() -> transaction.execute(status -> binder.bind(binder.begin(List.of(second)), input(key, "[]"))))
            .hasMessage("CONFLICTING_FUND_OPERATION_RECEIPT");
        persisted(receipt, first);
        assertThat(transaction.<List<Long>>execute(status -> mapper.customerOperations(second))).isEmpty();
    }

    @Test void legacyExactDigestWithoutPartiesCannotReplayOrBackfill() {
        long customer = customer(); insertWallet(customer); String key = key();
        collidePartyCustomer = customer;
        assertThatThrownBy(() -> transaction.execute(status -> binder.bind(binder.begin(List.of(customer)), input(key, "[]"))))
            .isInstanceOf(DuplicateKeyException.class);
        unchanged(customer, key);
        seedOperation(key, insertedDigest.get());
        assertThatThrownBy(() -> transaction.execute(status -> binder.bind(binder.begin(List.of(customer)), input(key, "[]"))))
            .hasMessage("FUND_OPERATION_PARTY_MISMATCH");
        assertThat(transaction.<List<Long>>execute(status -> mapper.customerOperations(customer))).isEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_support_fund_operation_party p JOIN nx_support_fund_operation o ON o.id=p.operation_id WHERE o.op_key=?", Long.class, key)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_support_fund_operation WHERE op_key=?", Long.class, key)).isEqualTo(1);
    }

    @Test void extraPartyCannotCertifyOtherwiseExactReplay() {
        long first = customer(), extra = customer(); insertWallet(first); String key = key();
        BoundReceipt receipt = transaction.execute(status -> binder.bind(binder.begin(List.of(first)), input(key, "[]")));
        jdbc.update("INSERT INTO nx_support_fund_operation_party(operation_id,customer_id) VALUES(?,?)", receipt.operationId(), extra);
        assertThatThrownBy(() -> transaction.execute(status -> binder.bind(binder.begin(List.of(first)), input(key, "[]"))))
            .hasMessage("FUND_OPERATION_PARTY_MISMATCH");
        assertThat(jdbc.queryForList("SELECT customer_id FROM nx_support_fund_operation_party WHERE operation_id=? ORDER BY customer_id", Long.class, receipt.operationId()))
            .containsExactly(first, extra);
    }

    @Test void latePartyCollisionRollsBackEveryNewFactAndKeepsOldBlocker() { partyCollision(false); }
    @Test void caughtLatePartyCollisionStillFailsOuterCommitAndRollsBackEveryNewFact() { partyCollision(true); }

    private void partyCollision(boolean caught) {
        long first = customer(), second = customer(); insertWallet(first); insertWallet(second); String key = key();
        long blocker = seedOperation(key());
        jdbc.update("INSERT INTO nx_support_fund_operation_party(operation_id,customer_id) VALUES(?,?)", blocker, second);
        collidePartyCustomer = second;
        assertThatThrownBy(() -> transaction.execute(status -> {
            Before token = binder.begin(List.of(second, first, second));
            jdbc.update("UPDATE nx_user_wallet SET usdt_available=9,version=version+1 WHERE user_id IN (?,?)", first, second);
            if (caught) {
                assertThatThrownBy(() -> binder.bind(token, input(key, "[]"))).isInstanceOf(DuplicateKeyException.class);
                return null;
            }
            return binder.bind(token, input(key, "[]"));
        })).isInstanceOf(caught ? UnexpectedRollbackException.class : DuplicateKeyException.class);
        unchanged(first, key); unchanged(second, key);
        assertThat(jdbc.queryForList("SELECT operation_id FROM nx_support_fund_operation_party WHERE customer_id=? ORDER BY operation_id", Long.class, second))
            .containsExactly(blocker);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_support_fund_operation WHERE id=?", Long.class, blocker)).isEqualTo(1);
    }

    @Test void sixPairsOfDistinctWalletsAndNewKeysInSameOperationGapCommitWithoutRetry() throws Exception {
        String prefix = "r22-gap-" + UUID.randomUUID() + "-"; seedOperation(prefix + "0000"); seedOperation(prefix + "9999");
        var workers = Executors.newFixedThreadPool(2);
        try {
            for (int pair = 1; pair <= 6; pair++) {
                long left = customer(), right = customer(); insertWallet(left); insertWallet(right);
                String lower = prefix + String.format("%04d-a", pair * 1000), upper = prefix + String.format("%04d-b", pair * 1000);
                var barrier = new CyclicBarrier(2);
                var one = workers.submit(() -> concurrentBind(left, lower, barrier));
                var two = workers.submit(() -> concurrentBind(right, upper, barrier));
                BoundReceipt first = one.get(25, TimeUnit.SECONDS), second = two.get(25, TimeUnit.SECONDS);
                assertThat(first.operationId()).isNotEqualTo(second.operationId());
                persisted(first, left, 1); persisted(second, right, 1);
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_support_fund_operation WHERE op_key IN (?,?)", Long.class, lower, upper)).isEqualTo(2);
            }
        } finally {
            workers.shutdownNow();
            assertThat(workers.awaitTermination(25, TimeUnit.SECONDS)).isTrue();
        }
    }

    private BoundReceipt concurrentBind(long customer, String key, CyclicBarrier barrier) {
        return transaction.execute(status -> {
            jdbc.execute("SET SESSION innodb_lock_wait_timeout=5");
            assertThat(jdbc.queryForObject("SELECT @@transaction_isolation", String.class)).isEqualTo("REPEATABLE-READ");
            Before token = binder.begin(List.of(customer));
            jdbc.update("UPDATE nx_user_wallet SET usdt_available=1,version=version+1 WHERE user_id=?", customer);
            try { barrier.await(10, TimeUnit.SECONDS); }
            catch (Exception ex) { throw new IllegalStateException("SQL_ONLY concurrency rendezvous failed", ex); }
            return binder.bind(token, input(key, deposit(customer)));
        });
    }
    private void insertWallet(long customer) { assertThat(jdbc.update("INSERT INTO nx_user_wallet(user_id) VALUES(?)", customer)).isEqualTo(1); }
    private static long customer() { return CUSTOMERS.incrementAndGet(); }
    private static String key() { return "r22-bind-" + UUID.randomUUID(); }
    private static ReceiptInput input(String key, String events) {
        return new ReceiptInput(key, "SQL_ONLY", "TEST", "isolated_sql", key, "PRODUCTION", events,
            "{\"purpose\":\"SQL_ONLY\"}", "{\"purpose\":\"SQL_ONLY\"}");
    }
    private static String deposit(long customer) {
        return "[{\"id\":\"sql-only-deposit\",\"action\":\"DEPOSIT\",\"to\":{\"customerId\":" + customer + ",\"currency\":\"USDT\",\"slot\":\"available\"},\"amount\":1}]";
    }
    private long seedOperation(String key) {
        return seedOperation(key, "a".repeat(64));
    }
    private long seedOperation(String key, String digest) {
        jdbc.update("INSERT INTO nx_support_fund_operation(op_key,business_type,phase,source_table,source_key,source_environment,ordered_events,source_snapshot,source_proof,receipt_sha256,protocol_version) VALUES(?,'SQL_ONLY','TEST','isolated_sql',?,'PRODUCTION','[]','{\"purpose\":\"SQL_ONLY\"}','{\"purpose\":\"SQL_ONLY\"}',?,'SUPPORT_FUND_BINDING_V1')", key, key, digest);
        return jdbc.queryForObject("SELECT id FROM nx_support_fund_operation WHERE op_key=?", Long.class, key);
    }
    private void persisted(BoundReceipt receipt, long customer, long... revisions) {
        assertThat(jdbc.queryForObject("SELECT receipt_sha256 FROM nx_support_fund_operation WHERE op_key=?", String.class, receipt.operationKey())).isEqualTo(receipt.receiptSha256());
        assertThat(jdbc.queryForList("SELECT l.revision FROM nx_support_fund_operation_leg l JOIN nx_user_wallet w ON w.fund_incarnation=l.wallet_incarnation WHERE operation_id=? AND w.user_id=? ORDER BY revision", Long.class, receipt.operationId(), customer))
            .containsExactly(java.util.Arrays.stream(revisions).boxed().toArray(Long[]::new));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_support_fund_operation_leg WHERE operation_id=?", Long.class, receipt.operationId())).isEqualTo(receipt.journalLegs());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_support_fund_operation_party WHERE operation_id=? AND customer_id=?", Long.class, receipt.operationId(), customer)).isEqualTo(1);
    }
    private void unchanged(long customer, String key) {
        assertThat(jdbc.queryForObject("SELECT CONCAT(usdt_available,':',fund_revision,':',version) FROM nx_user_wallet WHERE user_id=?", String.class, customer)).isEqualTo("0.000000:0:0");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_support_wallet_journal WHERE new_user_id=?", Long.class, customer)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_support_fund_operation WHERE op_key=?", Long.class, key)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_support_fund_operation_leg l JOIN nx_support_fund_operation o ON o.id=l.operation_id WHERE o.op_key=?", Long.class, key)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_support_fund_operation_party p JOIN nx_support_fund_operation o ON o.id=p.operation_id WHERE o.op_key=?", Long.class, key)).isZero();
    }

    @Intercepts(@Signature(type=StatementHandler.class, method="prepare", args={Connection.class, Integer.class}))
    public final class PhysicalConnectionCheck implements Interceptor {
        @Override public Object intercept(Invocation invocation) throws Throwable {
            Object resource = TransactionSynchronizationManager.getResource(dataSource);
            assertThat(resource).isInstanceOf(ConnectionHolder.class);
            Connection connection = (Connection) invocation.getArgs()[0];
            assertThat(connection).isSameAs(((ConnectionHolder) resource).getConnection());
            assertThat(connection.getAutoCommit()).isFalse();
            var statement = ((StatementHandler) invocation.getTarget()).getBoundSql();
            if (statement.getSql().startsWith("INSERT INTO nx_support_fund_operation (")) {
                var params = (Map<?,?>) statement.getParameterObject();
                insertedDigest.set((String) ((Map<?,?>) params.get("receipt")).get("receiptSha256"));
            }
            if (statement.getSql().startsWith("INSERT INTO nx_support_fund_operation_party ")) {
                var params = (Map<?,?>) statement.getParameterObject();
                long customer = ((Number) params.get("customerId")).longValue();
                if (customer == collidePartyCustomer) {
                    collidePartyCustomer = 0;
                    // Inject a real duplicate after operation/earlier parties exist in this same transaction.
                    try (var collision = connection.prepareStatement("INSERT INTO nx_support_fund_operation_party(operation_id,customer_id) VALUES(?,?)")) {
                        collision.setLong(1, ((Number) params.get("operationId")).longValue());
                        collision.setLong(2, customer); assertThat(collision.executeUpdate()).isEqualTo(1);
                    }
                }
            }
            if (statement.getSql().contains("FROM nx_user_wallet WHERE user_id="))
                walletLocks.add(((Number) ((Map<?,?>) statement.getParameterObject()).get("customerId")).longValue());
            physicalStatements.incrementAndGet();
            return invocation.proceed();
        }
    }
}
