package ffdd.opsconsole.finance.application;

import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import ffdd.opsconsole.finance.hdpay.*;
import ffdd.opsconsole.finance.mapper.*;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.outbox.EventOutboxService;
import ffdd.opsconsole.treasury.facade.TreasuryLedgerPostingFacade;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real InnoDB, production mappers/finalizer/transactions; no network provider or business schema. */
class BankWithdrawalMySqlTest {
    static final String PREFIX = "nexion_bank_payout_it_";
    static final String NO = "WD-ISOLATED", QN = "BQ-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-15T00:00:00Z"), ZoneOffset.UTC);
    static final LocalDateTime NOW = LocalDateTime.now(CLOCK);
    static BigDecimal bd(String value) { return new BigDecimal(value); }
    static String url(String endpoint, String schema) {
        if (!"127.0.0.1:13306".equals(endpoint) || schema == null || (!schema.isEmpty() && !schema.matches(PREFIX + "[a-f0-9]{32}")))
            throw new IllegalArgumentException("owned isolated MySQL endpoint/schema required");
        return "jdbc:mysql://" + endpoint + "/" + schema + "?useSSL=false&allowPublicKeyRetrieval=true&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true";
    }
    static DriverManagerDataSource ds(String schema) {
        return new DriverManagerDataSource(url(System.getenv("NEXION_ISOLATED_MYSQL_ENDPOINT"), schema), "root",
                System.getenv().getOrDefault("NEXION_ISOLATED_MYSQL_PASSWORD", ""));
    }
    @Test void fixtureCannotConnectToProductionOrBusinessDatabase() {
        assertThrows(IllegalArgumentException.class, () -> url("127.0.0.1:3306", PREFIX + "a".repeat(32)));
        assertThrows(IllegalArgumentException.class, () -> url("18.142.169.24:13306", PREFIX + "a".repeat(32)));
        assertThrows(IllegalArgumentException.class, () -> url("127.0.0.1:13306", "nexion"));
    }
    interface Work { void run(Fixture f) throws Exception; }
    static void isolated(Work work) throws Exception {
        String schema = PREFIX + UUID.randomUUID().toString().replace("-", "");
        var admin = new JdbcTemplate(ds(""));
        assertEquals(13306, admin.queryForObject("SELECT @@port", Integer.class));
        admin.execute("CREATE DATABASE " + schema);
        try { work.run(new Fixture(schema)); }
        finally { admin.execute("DROP DATABASE " + schema); }
    }
    static class Fixture {
        final DriverManagerDataSource source;
        final JdbcTemplate jdbc;
        final BankWithdrawalMapper bank;
        final WithdrawalPayoutMapper payouts;
        final AppWithdrawalMapper users;
        final AuditLogService audit = mock(AuditLogService.class);
        final TreasuryLedgerPostingFacade ledger = mock(TreasuryLedgerPostingFacade.class);
        final EventOutboxService outbox = mock(EventOutboxService.class);
        final FinanceSensitiveDataCipher cipher = new FinanceSensitiveDataCipher("isolated-fixture-key-not-a-real-credential");
        final HdPayPayoutTransactions transactions;
        Fixture(String schema) throws Exception {
            source = ds(schema); jdbc = new JdbcTemplate(source);
            assertEquals(schema, jdbc.queryForObject("SELECT DATABASE()", String.class));
            Configuration cfg = new Configuration(new Environment("isolated-bank", new SpringManagedTransactionFactory(), source));
            cfg.addMapper(BankWithdrawalMapper.class); cfg.addMapper(WithdrawalPayoutMapper.class); cfg.addMapper(AppWithdrawalMapper.class);
            var session = new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(cfg));
            bank = session.getMapper(BankWithdrawalMapper.class); payouts = session.getMapper(WithdrawalPayoutMapper.class); users = session.getMapper(AppWithdrawalMapper.class);
            jdbc.execute("CREATE TABLE nx_user(id BIGINT PRIMARY KEY,status VARCHAR(32),sandbox TINYINT,is_deleted TINYINT)");
            jdbc.execute("CREATE TABLE nx_user_wallet(user_id BIGINT PRIMARY KEY,usdt_available DECIMAL(24,6),nex_available DECIMAL(24,6),pending_withdraw DECIMAL(24,6),version BIGINT,is_deleted TINYINT,updated_at DATETIME(6))");
            jdbc.execute("""
                    CREATE TABLE nx_withdrawal_order(id BIGINT PRIMARY KEY,withdrawal_no VARCHAR(96) UNIQUE,user_id BIGINT,chain VARCHAR(16),target_address VARCHAR(100),
                    amount DECIMAL(24,6),d2_net_receive DECIMAL(24,6),d2_nex_burned DECIMAL(24,6),status VARCHAR(32),d2_hold_until DATETIME(6),
                    d5_payout_due_at DATETIME(6),d5_provider_cid BIGINT,d5_provider_idempotency_key VARCHAR(128),d5_payout_source VARCHAR(32),
                    chain_broadcast_attempts INT DEFAULT 0,d5_payout_lease_until DATETIME(6),next_broadcast_at DATETIME(6),chain_submitted_at DATETIME(6),
                    failure_reason VARCHAR(128),last_broadcast_error VARCHAR(128),chain_tx_hash VARCHAR(128),completed_at DATETIME(6),failed_at DATETIME(6),
                    created_at DATETIME(6),updated_at DATETIME(6),version BIGINT DEFAULT 0,is_deleted TINYINT DEFAULT 0)
                    """);
            jdbc.execute("""
                    CREATE TABLE nx_withdrawal_payout_ledger(event_no VARCHAR(96) PRIMARY KEY,withdrawal_no VARCHAR(96),provider_cid BIGINT,
                    event_type VARCHAR(32),status VARCHAR(32),source VARCHAR(32),amount_usdt DECIMAL(24,6),txid VARCHAR(128),payload_hash CHAR(64),created_at DATETIME(6))
                    """);
            migrate(); migrate();
            jdbc.update("INSERT INTO nx_user VALUES(71,'ACTIVE',0,0),(72,'ACTIVE',0,0)");
            jdbc.update("INSERT INTO nx_user_wallet VALUES(71,900,0,100,0,0,?),(72,900,0,100,0,0,?)", NOW, NOW);
            var transport = mock(HdPayProperties.class);
            var properties = mock(HdPayPayoutProperties.class);
            when(properties.ready(transport)).thenReturn(true); when(properties.getBankCodes()).thenReturn(Set.of("VCB"));
            var config = mock(PayoutVndConfigService.class);
            when(config.overview()).thenReturn(ApiResult.ok(Map.of("channelEnabled", true, "providerReady", true)));
            var finalizer = proxy(new WithdrawalPayoutFinalizer(payouts, audit, ledger, CLOCK));
            transactions = proxy(new HdPayPayoutTransactions(bank, users, payouts, finalizer, cipher, transport, properties, config,
                    mock(OpsFinanceService.class), audit, outbox, CLOCK));
        }
        @SuppressWarnings("unchecked") <T> T proxy(T target) {
            ProxyFactory proxy = new ProxyFactory(target);
            proxy.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(source), new AnnotationTransactionAttributeSource()));
            return (T) proxy.getProxy();
        }
        void migrate() throws Exception {
            try (var connection = source.getConnection()) {
                ScriptUtils.executeSqlScript(connection, new FileSystemResource("scripts/migrations/20260915_hdpay_bank_withdrawal.sql"));
            }
        }
        void seed() {
            var encrypted = cipher.encrypt("0123456789\nNGUYEN VAN A", BankWithdrawalService.quoteAad(71, QN));
            bank.insertQuote(new BankWithdrawalMapper.Quote(QN,71L,"BNK-fixture",0L,"VCB","****6789",encrypted,
                    bd("100"),bd("1"),bd("99"),bd("25000"),bd("2475000"),1L,"d5-v1",NOW,NOW.plusMinutes(5),null));
            bank.useQuote(QN,NO); bank.insertOrder(NO,QN,71,NOW);
            jdbc.update("""
                    INSERT INTO nx_withdrawal_order(id,withdrawal_no,user_id,chain,target_address,amount,d2_net_receive,d2_nex_burned,status,d2_hold_until,
                    d5_payout_due_at,created_at,updated_at) VALUES(1,?,71,'BANK-VND','BANK-VND:BNK-fixture',100,99,0,'REVIEW_PASSED',?,?,?,?)
                    """, NO,NOW,NOW,NOW,NOW);
        }
        HdPayPayoutGateway.Order response(int status) { return new HdPayPayoutGateway.Order(NO,123L,status,bd("2475000"),"0123456789","NGUYEN VAN A","2"); }
        void wallet(String available, String pending) {
            assertEquals(0, bd(available).compareTo(jdbc.queryForObject("SELECT usdt_available FROM nx_user_wallet WHERE user_id=71",BigDecimal.class)));
            assertEquals(0, bd(pending).compareTo(jdbc.queryForObject("SELECT pending_withdraw FROM nx_user_wallet WHERE user_id=71",BigDecimal.class)));
        }
    }
    @Test @EnabledIfEnvironmentVariable(named="NEXION_BANK_PAYOUT_IT",matches="true")
    void migrationConstraintsAndCryptoClaimsCannotCrossRails() throws Exception {
        isolated(f -> {
            f.seed(); assertEquals(4, f.bank.schemaTables());
            assertEquals(List.of(NO), f.bank.ready(NOW));
            assertTrue(f.payouts.claimable(NOW,10).isEmpty());
            assertTrue(f.payouts.claimableDevelopment(NOW,10).isEmpty());
            assertEquals(0, f.payouts.claim(NO,NOW,NOW.plusMinutes(1)));
            assertThrows(RuntimeException.class, () -> f.jdbc.update("UPDATE nx_bank_payout_quote SET net_usdt=100"));
            assertThrows(RuntimeException.class, () -> f.jdbc.update("UPDATE nx_hdpay_payout SET state='TYPO'"));
            f.wallet("900","100");
        });
    }
    @Test @EnabledIfEnvironmentVariable(named="NEXION_BANK_PAYOUT_IT",matches="true")
    void concurrentDispatchAndSuccessSettleExactlyOnce() throws Exception {
        isolated(f -> {
            f.seed(); var pool = Executors.newFixedThreadPool(2);
            try {
                var a = pool.submit(() -> f.transactions.prepare(NO));
                var b = pool.submit(() -> f.transactions.prepare(NO));
                assertEquals(1, (a.get(10,TimeUnit.SECONDS) == null ? 0 : 1) + (b.get(10,TimeUnit.SECONDS) == null ? 0 : 1));
                assertEquals("DISPATCHING",f.bank.order(NO).state());
                f.wallet("900","100");
                var callback = new HdPayPayoutCallbackVerifier.Callback(NO,123,3,bd("2475000"),"a".repeat(64));
                assertEquals("success",f.transactions.accept(callback)); assertEquals("success",f.transactions.accept(callback));
                assertEquals(1,f.jdbc.queryForObject("SELECT COUNT(*) FROM nx_hdpay_payout_callback",Integer.class));
                // A success callback can precede provider query convergence; pending must remain recoverable.
                f.transactions.reconcile(NO,f.response(1)); f.wallet("900","100");
                assertEquals("PENDING",f.bank.order(NO).state());
                var c = pool.submit(() -> f.transactions.reconcile(NO,f.response(3)));
                var d = pool.submit(() -> f.transactions.reconcile(NO,f.response(3)));
                c.get(10,TimeUnit.SECONDS); d.get(10,TimeUnit.SECONDS);
                f.wallet("900","0"); assertEquals("CONFIRMED",f.payouts.payout(NO).status());
                assertEquals("PAID",f.bank.order(NO).state());
                assertEquals(1,f.jdbc.queryForObject("SELECT COUNT(*) FROM nx_withdrawal_payout_ledger WHERE status='CONFIRMED'",Integer.class));
                verify(f.outbox,times(1)).publish(eq("WITHDRAWAL"),eq(NO),eq("withdraw.confirmed"),any());
            } finally { pool.shutdownNow(); assertTrue(pool.awaitTermination(15,TimeUnit.SECONDS)); }
        });
    }
    @Test @EnabledIfEnvironmentVariable(named="NEXION_BANK_PAYOUT_IT",matches="true")
    void failureAndProviderConfirmedReturnRefundGrossIncludingFeeOnce() throws Exception {
        isolated(f -> {
            f.seed(); f.transactions.prepare(NO); f.transactions.reconcile(NO,f.response(5)); f.transactions.reconcile(NO,f.response(5));
            f.wallet("1000","0"); assertEquals("FAILED",f.payouts.payout(NO).status());
            verify(f.ledger,times(1)).postLedgerEntry(eq(NO+":PAYOUT:USDT:REFUND"),eq(71L),eq("WITHDRAW_PAYOUT_REFUND"),eq("USDT"),eq("IN"),argThat(n -> n.compareTo(bd("100"))==0),eq("POSTED"),anyString());
        });
        isolated(f -> {
            f.seed(); f.transactions.prepare(NO);
            f.transactions.accept(new HdPayPayoutCallbackVerifier.Callback(NO,123,4,bd("2475000"),"b".repeat(64)));
            f.wallet("900","100"); verifyNoInteractions(f.ledger,f.outbox);
            f.transactions.reconcile(NO,f.response(4)); f.transactions.reconcile(NO,f.response(4));
            f.wallet("1000","0"); assertEquals("FAILED",f.payouts.payout(NO).status());
            assertEquals("FAILED",f.bank.order(NO).state());
            assertEquals(1,f.jdbc.queryForObject("SELECT COUNT(*) FROM nx_withdrawal_payout_ledger WHERE status='FAILED'",Integer.class));
        });
    }
    @Test @EnabledIfEnvironmentVariable(named="NEXION_BANK_PAYOUT_IT",matches="true")
    void queryOnlyRecoverySettlesOnceAndRefusesStaleVersionOrConflictingEvidence() throws Exception {
        isolated(f -> {
            f.seed(); f.transactions.prepare(NO);
            f.transactions.reconcile(NO,new HdPayPayoutGateway.Order(NO,123L,3,bd("2475000"),"9999999999","NGUYEN VAN A","2"));
            assertEquals("MANUAL_REVIEW",f.bank.order(NO).state()); f.wallet("900","100");
            long version = f.bank.version(NO);
            assertEquals("PAID",f.transactions.recover(NO,version,f.response(3),"fixture-admin","provider corrected recipient evidence"));
            f.wallet("900","0");
            assertThrows(RuntimeException.class,() -> f.transactions.recover(NO,version,f.response(3),"fixture-admin","same stale operator command"));
            f.wallet("900","0");
            assertEquals(1,f.jdbc.queryForObject("SELECT COUNT(*) FROM nx_withdrawal_payout_ledger WHERE status='CONFIRMED'",Integer.class));
        });
        isolated(f -> {
            f.seed(); f.transactions.prepare(NO);
            f.transactions.accept(new HdPayPayoutCallbackVerifier.Callback(NO,123,3,bd("2475000"),"c".repeat(64)));
            f.transactions.reconcile(NO,f.response(5));
            long version = f.bank.version(NO);
            assertEquals("MANUAL_REVIEW",f.transactions.recover(NO,version,f.response(5),"fixture-admin","check contradictory provider result"));
            f.wallet("900","100"); verifyNoInteractions(f.ledger,f.outbox);
        });
    }
    @Test @EnabledIfEnvironmentVariable(named="NEXION_BANK_PAYOUT_IT",matches="true")
    void auditFailureRollsBackWalletOrderAndLedgerTogetherThenRetrySucceeds() throws Exception {
        isolated(f -> {
            f.seed(); f.transactions.prepare(NO); f.transactions.reconcile(NO,f.response(1));
            doThrow(new IllegalStateException("fixture-audit-unavailable")).when(f.audit).recordRequired(any());
            assertThrows(RuntimeException.class,() -> f.transactions.reconcile(NO,f.response(3)));
            f.wallet("900","100"); assertEquals("SENT",f.payouts.payout(NO).status()); assertEquals("PENDING",f.bank.order(NO).state());
            assertEquals(0,f.jdbc.queryForObject("SELECT COUNT(*) FROM nx_withdrawal_payout_ledger WHERE status='CONFIRMED'",Integer.class));
            reset(f.audit); f.transactions.reconcile(NO,f.response(3)); f.wallet("900","0");
        });
    }
}
