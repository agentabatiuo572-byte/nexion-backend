package ffdd.opsconsole.finance.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.auth.mapper.AdminMapper;
import ffdd.opsconsole.finance.mapper.EarningsReleaseMapper;
import ffdd.opsconsole.platform.application.A2RuntimePolicy;
import ffdd.opsconsole.risk.application.RiskReleaseParamsService;
import ffdd.opsconsole.shared.audit.*;
import ffdd.opsconsole.shared.audit.mapper.AuditLogMapper;
import ffdd.opsconsole.shared.config.MybatisMetaObjectHandler;
import ffdd.opsconsole.shared.idempotency.*;
import ffdd.opsconsole.shared.idempotency.mapper.AdminIdempotencyRecordMapper;
import ffdd.opsconsole.shared.outbox.EventOutboxService;
import ffdd.opsconsole.treasury.infrastructure.MybatisTreasuryLedgerRepository;
import ffdd.opsconsole.treasury.mapper.TreasuryLedgerMapper;
import java.math.BigDecimal;
import java.nio.file.*;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;
import javax.sql.DataSource;
import org.apache.ibatis.mapping.Environment;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;

/** Opt-in only. Never creates, drops, or connects to any other database. Fixtures are retained. */
@EnabledIfEnvironmentVariable(named="EARNINGS_SOURCE_RECOVERY_RUNTIME", matches="1")
class EarningsSourceRecoveryMySqlTest {
    private DataSource dataSource;
    private JdbcTemplate jdbc;
    private TransactionTemplate tx;
    private EarningsReleaseService service;
    private EarningsReleaseMapper mapper;
    private MybatisTreasuryLedgerRepository ledger;
    private AuditProperties auditProperties;
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final String marker = "ERIT-" + UUID.randomUUID().toString().substring(0, 12);
    private final List<Map<String,Object>> evidence = new ArrayList<>();
    private long nextUser = 800000000000L + Math.floorMod(UUID.randomUUID().getMostSignificantBits(), 100000000000L);

    @Test void actualRecoveryTransactionsAndConservativeSourceEvidence() throws Exception {
        writeEvidence("RUNNING", null);
        try {
            runRecoveryScenarios();
            writeEvidence("PASS", null);
        } catch (Exception | AssertionError failure) {
            writeEvidence("FAIL", failure.getClass().getSimpleName());
            throw failure;
        }
    }

    private void runRecoveryScenarios() throws Exception {
        setup();
        migrate(); migrate();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.TRIGGERS WHERE TRIGGER_SCHEMA=DATABASE() AND TRIGGER_NAME='nx_wallet_earnings_debit_counter_v1'", Integer.class)).isEqualTo(1);
        for (String asset : List.of("USDT", "NEX")) for (String bucket : List.of("withdrawable", "pending_review", "bonus_locked")) {
            long user = user(); String entry = credit(user, asset, bucket, "10");
            var first = recover(user, entry, asset, "3", marker + entry + "a");
            var second = recover(user, entry, asset, "7", marker + entry + "b");
            assertThat(first.recovered()).isEqualByComparingTo("3");
            assertThat(second.recovered()).isEqualByComparingTo("7");
            assertThat(second.cumulativeRecovered()).isEqualByComparingTo("10");
            assertThat(balance(user, asset)).isZero();
            assertThat(jdbc.queryForObject("SELECT bucket FROM nx_earnings_release_entry WHERE entry_no=?", String.class, entry)).isEqualTo(bucket);
            assertThat(mapper.buckets(user)).allSatisfy(row -> assertThat(row.amount()).isZero());
            assertThat(mapper.protectedEntries(user,"PRODUCTION")).isEmpty();
            assertThat(mapper.protectedEntryViews(user,null,100)).isEmpty();
            assertThat(mapper.release(entry,"manual")).isZero();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_audit_log WHERE action='EARNINGS_SOURCE_RECOVERED' AND resource_id=?", Integer.class, entry)).isEqualTo(2);
            assertThat(jdbc.queryForObject("SELECT SUM(amount) FROM nx_wallet_ledger WHERE user_id=? AND direction='OUT'", BigDecimal.class, user)).isEqualByComparingTo("10");
            assertThat(recover(user, entry, asset, "3", marker + entry + "a")).isEqualTo(first);
            String originalSource=jdbc.queryForObject("SELECT source_ref FROM nx_earnings_release_entry WHERE entry_no=?",String.class,entry);
            assertThat(service.credit(user,"USER:"+user,"PROMOTION_REWARD",originalSource,asset,new BigDecimal("10"),bucket,originalSource)).isEqualTo(entry);
            assertThat(balance(user,asset)).isZero();
            assertThat(jdbc.queryForObject("SELECT source_debit_baseline FROM nx_earnings_release_entry WHERE entry_no=?",BigDecimal.class,entry)).isZero();
            assertThatThrownBy(() -> recover(user, entry, asset, "4", marker + entry + "a"))
                    .hasMessage("IDEMPOTENCY_KEY_PAYLOAD_MISMATCH");
            record("asset-bucket-and-replay", Map.of("asset", asset, "bucket", bucket, "first", first, "second", second));
        }

        for (String asset : List.of("USDT", "NEX")) {
            long multiUser = user();
            String first = credit(multiUser, asset, "withdrawable", "2.125001");
            String second = credit(multiUser, asset, "bonus_locked", "2.125001");
            var firstReceipt = recover(multiUser, first, asset, "2.125001", marker + first);
            var secondReceipt = recover(multiUser, second, asset, "2.125001", marker + second);
            assertThat(firstReceipt.recovered()).isEqualByComparingTo("2.125001");
            assertThat(secondReceipt.recovered()).isEqualByComparingTo("2.125001");
            assertThat(secondReceipt.outstanding()).isZero();
            assertThat(balance(multiUser, asset)).isZero();
            record("different-unspent-sources-recover-independently", Map.of("asset", asset,
                    "first", firstReceipt, "second", secondReceipt));

            long mixedUser = user();
            String earlier = credit(mixedUser, asset, "pending_review", "10");
            String column = "USDT".equals(asset) ? "usdt_available" : "nex_available";
            movement(mixedUser, asset, "-4", "UPDATE nx_user_wallet SET " + column + "=" + column + "-4 WHERE user_id=?");
            String later = credit(mixedUser, asset, "withdrawable", "10");
            var earlierReceipt = recover(mixedUser, earlier, asset, "10", marker + earlier);
            var laterReceipt = recover(mixedUser, later, asset, "10", marker + later);
            assertThat(earlierReceipt.recovered()).isEqualByComparingTo("6");
            assertThat(earlierReceipt.outstanding()).isEqualByComparingTo("4");
            assertThat(laterReceipt.recovered()).isEqualByComparingTo("10");
            assertThat(balance(mixedUser, asset)).isZero();
            movement(mixedUser, asset, "20", "UPDATE nx_user_wallet SET " + column + "=" + column + "+20 WHERE user_id=?");
            assertThat(recover(mixedUser, earlier, asset, "4", marker + earlier + "retry").recovered()).isZero();
            record("other-source-recovery-never-restores-consumed-original", Map.of("asset", asset,
                    "earlier", earlierReceipt, "later", laterReceipt));
        }

        long partialUser = user(); String partial = credit(partialUser, "USDT", "pending_review", "10");
        movement(partialUser,"USDT","-4","UPDATE nx_user_wallet SET usdt_available=usdt_available-4,pending_withdraw=pending_withdraw+4 WHERE user_id=?");
        movement(partialUser,"USDT","4","UPDATE nx_user_wallet SET usdt_available=usdt_available+4,pending_withdraw=pending_withdraw-4 WHERE user_id=?");
        movement(partialUser,"USDT","100","UPDATE nx_user_wallet SET usdt_available=usdt_available+100 WHERE user_id=?");
        credit(partialUser, "USDT", "bonus_locked", "20");
        var partialReceipt = recover(partialUser, partial, "USDT", "10", marker + "partial");
        assertThat(partialReceipt.recovered()).isEqualByComparingTo("6");
        assertThat(partialReceipt.outstanding()).isEqualByComparingTo("4");
        assertThat(balance(partialUser, "USDT")).isEqualByComparingTo("124");
        assertThat(mapper.protectedAmount(partialUser)).isEqualByComparingTo("24");
        record("reservation-thaw-principal-other-reward-do-not-revive-source", Map.of("receipt", partialReceipt));

        long releasedUser = user(); String released = credit(releasedUser, "USDT", "bonus_locked", "10");
        movement(releasedUser,"USDT","-4","UPDATE nx_user_wallet SET usdt_available=usdt_available-4 WHERE user_id=?");
        assertThat(mapper.release(released,"manual")).isEqualTo(1);
        assertThat(mapper.protectedAmount(releasedUser)).isZero();
        var releasedReceipt = recover(releasedUser,released,"USDT","10",marker+"released");
        assertThat(releasedReceipt.bucket()).isEqualTo("withdrawable");
        assertThat(releasedReceipt.recovered()).isEqualByComparingTo("6");
        assertThat(releasedReceipt.outstanding()).isEqualByComparingTo("4");
        record("risk-bucket-release-does-not-revive-spent-source", Map.of("receipt",releasedReceipt));

        long spentUser = user(); String spent = credit(spentUser, "USDT", "withdrawable", "10");
        movement(spentUser,"USDT","-10","UPDATE nx_user_wallet SET usdt_available=0 WHERE user_id=?");
        credit(spentUser, "USDT", "withdrawable", "50");
        var spentReceipt = recover(spentUser, spent, "USDT", "10", marker + "spent");
        assertThat(spentReceipt.recovered()).isZero();
        assertThat(balance(spentUser, "USDT")).isEqualByComparingTo("50");
        record("fully-spent-source-never-uses-another-reward", Map.of("receipt", spentReceipt));

        long isolatedUser = user(); String isolated = credit(isolatedUser, "USDT", "bonus_locked", "10");
        credit(isolatedUser, "NEX", "withdrawable", "20");
        movement(isolatedUser,"NEX","-15","UPDATE nx_user_wallet SET nex_available=nex_available-15 WHERE user_id=?");
        assertThat(recover(isolatedUser, isolated, "USDT", "10", marker + "currency").recovered()).isEqualByComparingTo("10");
        assertThat(balance(isolatedUser, "NEX")).isEqualByComparingTo("5");
        record("currencies-independent", Map.of("userId", isolatedUser));

        long preciseUser=user();String preciseAmount="999999999999.999999";
        String precise=credit(preciseUser,"NEX","withdrawable",preciseAmount);
        var preciseReceipt=recover(preciseUser,precise,"NEX",preciseAmount,marker+"precision");
        assertThat(preciseReceipt.recovered()).isEqualByComparingTo(preciseAmount);
        assertThat(recover(preciseUser,precise,"NEX",preciseAmount,marker+"precision")).isEqualTo(preciseReceipt);
        record("maximum-six-decimal-amount-replays-exactly-through-durable-json",Map.of("receipt",preciseReceipt));

        for (String asset : List.of("USDT", "NEX")) for (String bucket : List.of("withdrawable", "pending_review", "bonus_locked")) {
            long legacyUser = user(); String legacy = "LEGACY-" + UUID.randomUUID();
            jdbc.update("INSERT INTO nx_earnings_release_entry(entry_no,user_id,source_type,source_ref,asset,amount,bucket,idempotency_key) VALUES(?,?,'PROMOTION_REWARD',?,?,10,?,?)", legacy, legacyUser, legacy, asset, bucket, legacy);
            jdbc.update("UPDATE nx_user_wallet SET usdt_available=50,nex_available=50 WHERE user_id=?", legacyUser);
            var legacyReceipt = recover(legacyUser, legacy, asset, "10", marker + legacy);
            assertThat(legacyReceipt.recovered()).isZero();
            assertThat(legacyReceipt.evidenceStatus()).isEqualTo("LEGACY_UNPROVEN");
            record("legacy-fail-closed", Map.of("asset", asset, "bucket", bucket, "receipt", legacyReceipt));
        }

        long rollbackUser = user(); String rollbackEntry = credit(rollbackUser, "NEX", "bonus_locked", "10");
        String rollbackKey = marker + "rollback";
        assertThatThrownBy(() -> tx.execute(status -> {
            var receipt = service.recoverReward(request(rollbackUser, rollbackEntry, "NEX", "10"), rollbackKey);
            post(receipt);
            throw new IllegalStateException("injected downstream transaction failure");
        })).hasMessage("injected downstream transaction failure");
        assertThat(balance(rollbackUser, "NEX")).isEqualByComparingTo("10");
        assertThat(jdbc.queryForObject("SELECT earnings_nex_debited FROM nx_user_wallet WHERE user_id=?", BigDecimal.class, rollbackUser)).isZero();
        assertThat(jdbc.queryForObject("SELECT recovered_amount FROM nx_earnings_release_entry WHERE entry_no=?", BigDecimal.class, rollbackEntry)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_earnings_source_recovery WHERE entry_no=?", Integer.class, rollbackEntry)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_wallet_ledger WHERE user_id=? AND direction='OUT'", Integer.class, rollbackUser)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_audit_log WHERE resource_id=?", Integer.class, rollbackEntry)).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM nx_admin_idempotency_record WHERE idempotency_key=?", String.class, rollbackKey)).isEqualTo("FAILED");
        var retriedRollback = recover(rollbackUser, rollbackEntry, "NEX", "10", rollbackKey);
        assertThat(retriedRollback.recovered()).isEqualByComparingTo("10");
        assertThat(recover(rollbackUser, rollbackEntry, "NEX", "10", rollbackKey)).isEqualTo(retriedRollback);
        assertThat(balance(rollbackUser, "NEX")).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_wallet_ledger WHERE user_id=? AND direction='OUT'", Integer.class, rollbackUser)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_earnings_source_recovery WHERE entry_no=?", Integer.class, rollbackEntry)).isEqualTo(1);
        record("outer-failure-rolls-wallet-counter-entry-receipt-D4-audit-back-and-same-key-retries-once", Map.of("userId", rollbackUser));

        long auditUser = user(); String auditEntry = credit(auditUser, "USDT", "withdrawable", "10");
        auditProperties.setEnabled(false);
        try { assertThatThrownBy(() -> recover(auditUser, auditEntry, "USDT", "10", marker + "audit-fail"))
                .hasMessage("AUDIT_REQUIRED_DISABLED"); }
        finally { auditProperties.setEnabled(true); }
        assertThat(balance(auditUser, "USDT")).isEqualByComparingTo("10");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_earnings_source_recovery WHERE entry_no=?", Integer.class, auditEntry)).isZero();
        record("required-audit-fails-closed", Map.of("userId", auditUser));

        concurrentRecoveries();
        for (String asset : List.of("USDT", "NEX")) {
            concurrentDebitAndRecovery(asset);
            concurrentDifferentSourcesWithOldReadSnapshot(asset);
        }
    }

    private void writeEvidence(String status, String failureType) throws Exception {
        Map<String,Object> result = new LinkedHashMap<>();
        result.put("status", status); result.put("capability", "runtime");
        result.put("database", "growth_promotions_20261007"); result.put("port", 33339);
        result.put("marker", marker); result.put("cases", evidence);
        if (failureType != null) result.put("failureType", failureType);
        result.put("limitations", List.of("Outbox delivery and promotion lifecycle are not tested by this finance primitive suite.",
                "Conservative evidence may leave spent or unproven amounts outstanding; it is not a source-consumption allocation policy."));
        json.writerWithDefaultPrettyPrinter().writeValue(Path.of("target/earnings-source-recovery-runtime.json").toFile(), result);
    }

    private void concurrentRecoveries() throws Exception {
        long user = user(); String entry = credit(user, "USDT", "withdrawable", "10");
        var pool = Executors.newFixedThreadPool(2); var start = new CountDownLatch(1);
        try {
            List<Future<String>> results = new ArrayList<>();
            for (int i=0;i<2;i++) { String key = marker + "race" + i;
                results.add(pool.submit(() -> { start.await(); try { recover(user,entry,"USDT","10",key); return "RECOVERED"; }
                    catch (ffdd.opsconsole.shared.exception.BizException ex) { return ex.getMessage(); } })); }
            start.countDown();
            assertThat(List.of(results.get(0).get(10,TimeUnit.SECONDS),results.get(1).get(10,TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder("RECOVERED","EARNINGS_RECOVERY_EXCEEDS_ORIGINAL");
            assertThat(balance(user,"USDT")).isZero();
            assertThat(jdbc.queryForObject("SELECT SUM(recovered) FROM nx_earnings_source_recovery WHERE entry_no=?",BigDecimal.class,entry)).isEqualByComparingTo("10");
            record("two-recovery-transactions-never-exceed-original",Map.of("userId",user));
        } finally { pool.shutdownNow(); }
    }

    private void concurrentDebitAndRecovery(String asset) throws Exception {
        long user=user();String entry=credit(user,asset,"withdrawable","10");
        var pool=Executors.newSingleThreadExecutor();
        try (var connection=dataSource.getConnection()) {
            connection.setAutoCommit(false);
            String debitSql="USDT".equals(asset)
                    ? "UPDATE nx_user_wallet SET usdt_available=usdt_available-4 WHERE user_id=?"
                    : "UPDATE nx_user_wallet SET nex_available=nex_available-4 WHERE user_id=?";
            try (var statement=connection.prepareStatement(debitSql)) {
                statement.setLong(1,user);statement.executeUpdate();
            }
            try (var statement=connection.prepareStatement("INSERT INTO nx_wallet_ledger(user_id,biz_no,biz_type,asset,direction,amount,balance_after,status,remark) VALUES(?,?,'RECOVERY_SOURCE_TEST',?,'OUT',4,6,'SUCCESS',?)")) {
                statement.setLong(1,user);statement.setString(2,marker+"concurrent-spend-"+asset);statement.setString(3,asset);statement.setString(4,marker);statement.executeUpdate();
            }
            var future=pool.submit(() -> recover(user,entry,asset,"10",marker+"debit-race-"+asset));
            assertThatThrownBy(() -> future.get(300,TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            connection.commit();
            var result=future.get(10,TimeUnit.SECONDS);
            assertThat(result.recovered()).isEqualByComparingTo("6");
            assertThat(result.outstanding()).isEqualByComparingTo("4");
            assertThat(balance(user,asset)).isZero();
            record("direct-debit-serializes-before-recovery-current-read",Map.of("asset",asset,"receipt",result));
        } finally { pool.shutdownNow(); }
    }

    private void concurrentDifferentSourcesWithOldReadSnapshot(String asset) throws Exception {
        long user = user();
        String first = credit(user, asset, "withdrawable", "2.125001");
        String second = credit(user, asset, "bonus_locked", "2.125001");
        var pool = Executors.newSingleThreadExecutor();
        var snapshotOpened = new CountDownLatch(1);
        var waiting = new ArrayList<Future<EarningsReleaseService.RewardRecoveryReceipt>>();
        try {
            var firstReceipt = tx.execute(status -> {
                var receipt = service.recoverReward(request(user, first, asset, "2.125001"), marker + first);
                post(receipt);
                waiting.add(pool.submit(() -> tx.execute(other -> {
                    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_earnings_source_recovery WHERE user_id=?", Integer.class, user)).isZero();
                    snapshotOpened.countDown();
                    var result = service.recoverReward(request(user, second, asset, "2.125001"), marker + second);
                    post(result);
                    return result;
                })));
                try { assertThat(snapshotOpened.await(5, TimeUnit.SECONDS)).isTrue(); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted); }
                assertThatThrownBy(() -> waiting.get(0).get(200, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
                return receipt;
            });
            var secondReceipt = waiting.get(0).get(10, TimeUnit.SECONDS);
            assertThat(firstReceipt.recovered()).isEqualByComparingTo("2.125001");
            assertThat(secondReceipt.recovered()).isEqualByComparingTo("2.125001");
            assertThat(balance(user, asset)).isZero();
            record("concurrent-different-sources-current-read-recovery", Map.of("asset", asset,
                    "first", firstReceipt, "second", secondReceipt));
        } finally { pool.shutdownNow(); }
    }

    private void setup() throws Exception {
        Map<String,String> credentials=new HashMap<>();
        for(String line:Files.readAllLines(Path.of("C:/Users/jason/.codex/workflow-runs/growth-promotions-20261007/mysql/client.private.ini"))) {
            if(!line.contains("=") || line.trim().startsWith("#"))continue;
            String[] pair=line.split("=",2);String value=pair[1].trim();
            if(value.startsWith("\"")&&value.endsWith("\""))value=value.substring(1,value.length()-1);
            credentials.put(pair[0].trim(),value);
        }
        dataSource=new DriverManagerDataSource("jdbc:mysql://127.0.0.1:33339/growth_promotions_20261007?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai",credentials.get("user"),credentials.get("password"));
        jdbc=new JdbcTemplate(dataSource);
        assertThat(jdbc.queryForObject("SELECT DATABASE()",String.class)).isEqualTo("growth_promotions_20261007");
        assertThat(jdbc.queryForObject("SELECT @@port",Integer.class)).isEqualTo(33339);
        tx=new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        var config=new MybatisConfiguration(new Environment("earnings-recovery-runtime",new SpringManagedTransactionFactory(),dataSource));
        var global=new GlobalConfig();global.setDbConfig(new GlobalConfig.DbConfig());global.setMetaObjectHandler(new MybatisMetaObjectHandler(Clock.systemUTC()));GlobalConfigUtils.setGlobalConfig(config,global);
        config.setMapUnderscoreToCamelCase(true);
        for(Class<?> type:List.of(EarningsReleaseMapper.class,AdminIdempotencyRecordMapper.class,AuditLogMapper.class,TreasuryLedgerMapper.class))config.addMapper(type);
        var session=new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(config));
        mapper=session.getMapper(EarningsReleaseMapper.class);
        var idemMapper=session.getMapper(AdminIdempotencyRecordMapper.class);
        var expiry=proxy(new AdminIdempotencyExpiryTransitionExecutor(idemMapper));
        var executor=proxy(new AdminIdempotencyTransactionExecutor(idemMapper,json,expiry));
        var idempotency=new AdminIdempotencyService(executor,Clock.systemUTC());
        auditProperties=new AuditProperties();var auditPolicy=mock(A2RuntimePolicy.class);
        when(auditPolicy.retentionMonths()).thenReturn(24);when(auditPolicy.schemaVersion()).thenReturn("1");
        var audit=new AuditLogService(session.getMapper(AuditLogMapper.class),new AuditLogSanitizer(json),
                new ApplicationNameProperties(),auditProperties,mock(AdminMapper.class),auditPolicy);
        var profile=mock(FundsSandboxProfileGuard.class);when(profile.isStrictProductionRuntime()).thenReturn(true);
        service=proxy(new EarningsReleaseService(mapper,mock(RiskReleaseParamsService.class),idempotency,audit,profile));
        ledger=proxy(new MybatisTreasuryLedgerRepository(session.getMapper(TreasuryLedgerMapper.class),mock(EventOutboxService.class)));
    }

    private void migrate() throws Exception {
        String script=Files.readString(Path.of("scripts/migrations/20261007_earnings_source_recovery.sql")).replaceAll("(?m)^--.*$", "");
        try(var connection=dataSource.getConnection();var statement=connection.createStatement()) {
            for(String sql:script.split(";"))if(!sql.isBlank())statement.execute(sql);
        }
    }
    @SuppressWarnings("unchecked") private <T>T proxy(T target) {
        var factory=new ProxyFactory(target);factory.setProxyTargetClass(true);
        factory.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(dataSource),new AnnotationTransactionAttributeSource()));
        return (T)factory.getProxy();
    }
    private long user() {
        long id=++nextUser;
        jdbc.update("INSERT INTO nx_user(id,country_code,phone,client_ip,password_hash,nickname,referral_code,sandbox) VALUES(?,'00',?,'127.0.0.1','fixture',?,?,0)",id,String.valueOf(id),marker,String.valueOf(id));
        jdbc.update("INSERT INTO nx_user_wallet(user_id,sandbox) VALUES(?,0)",id);return id;
    }
    private String credit(long user,String asset,String bucket,String amount) {
        String source=marker+UUID.randomUUID().toString().substring(0,8);
        return tx.execute(status -> {
            String entry=service.credit(user,"USER:"+user,"PROMOTION_REWARD",source,asset,new BigDecimal(amount),bucket,source);
            ledger.postLedgerEntry(source,user,"PROMOTION_REWARD_TEST",asset,"IN",new BigDecimal(amount),"SUCCESS",marker);
            return entry;
        });
    }
    private EarningsReleaseService.RewardRecoveryRequest request(long user,String entry,String asset,String amount) {
        String source=jdbc.queryForObject("SELECT source_ref FROM nx_earnings_release_entry WHERE entry_no=?",String.class,entry);
        return new EarningsReleaseService.RewardRecoveryRequest(user,entry,"PROMOTION_REWARD",source,asset,"PRODUCTION",new BigDecimal(amount),"isolated approved refund specimen",marker);
    }
    private EarningsReleaseService.RewardRecoveryReceipt recover(long user,String entry,String asset,String amount,String key) {
        return tx.execute(status -> {var receipt=service.recoverReward(request(user,entry,asset,amount),key);post(receipt);return receipt;});
    }
    private void post(EarningsReleaseService.RewardRecoveryReceipt receipt) {
        if(receipt.recovered().signum()>0) {
            ledger.postLedgerEntry(receipt.recoveryNo(),receipt.userId(),"PROMOTION_RECOVERY_TEST",receipt.asset(),"OUT",receipt.recovered(),"SUCCESS",marker);
            assertThat(jdbc.queryForObject("SELECT balance_after FROM nx_wallet_ledger WHERE biz_no=? AND asset=? AND direction='OUT'",BigDecimal.class,receipt.recoveryNo(),receipt.asset())).isEqualByComparingTo(receipt.balanceAfter());
        }
    }
    private void movement(long user,String asset,String amount,String sql) {
        tx.executeWithoutResult(status -> {
            jdbc.update(sql,user);BigDecimal delta=new BigDecimal(amount);
            ledger.postLedgerEntry(marker+UUID.randomUUID(),user,"RECOVERY_SOURCE_TEST",asset,delta.signum()<0?"OUT":"IN",delta.abs(),"SUCCESS",marker);
        });
    }
    private BigDecimal balance(long user,String asset) {
        return jdbc.queryForObject("SELECT CASE ? WHEN 'USDT' THEN usdt_available WHEN 'NEX' THEN nex_available END FROM nx_user_wallet WHERE user_id=?",BigDecimal.class,asset,user);
    }
    private void record(String name,Map<String,Object> details){evidence.add(Map.of("name",name,"result","PASS","details",details));}
}
