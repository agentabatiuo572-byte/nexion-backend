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
        final AppPayoutAddressMapper addresses;
        final WithdrawalPayoutMapper payouts;
        final AppWithdrawalMapper users;
        final AuditLogService audit = mock(AuditLogService.class);
        final TreasuryLedgerPostingFacade ledger = mock(TreasuryLedgerPostingFacade.class);
        final EventOutboxService outbox = mock(EventOutboxService.class);
        final FinanceSensitiveDataCipher cipher = new FinanceSensitiveDataCipher("isolated-fixture-key-not-a-real-credential");
        final HdPayPayoutTransactions transactions;
        final BankWithdrawalService service;
        final ffdd.opsconsole.auth.application.UserOtpDeliveryService delivery = mock(ffdd.opsconsole.auth.application.UserOtpDeliveryService.class);
        Fixture(String schema) throws Exception {
            source = ds(schema); jdbc = new JdbcTemplate(source);
            assertEquals(schema, jdbc.queryForObject("SELECT DATABASE()", String.class));
            Configuration cfg = new Configuration(new Environment("isolated-bank", new SpringManagedTransactionFactory(), source));
            cfg.addMapper(BankWithdrawalMapper.class); cfg.addMapper(WithdrawalPayoutMapper.class); cfg.addMapper(AppWithdrawalMapper.class);
            cfg.addMapper(AppPayoutAddressMapper.class);
            var session = new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(cfg));
            bank = session.getMapper(BankWithdrawalMapper.class);
            addresses = session.getMapper(AppPayoutAddressMapper.class); payouts = session.getMapper(WithdrawalPayoutMapper.class); users = session.getMapper(AppWithdrawalMapper.class);
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
            jdbc.execute("""
                    CREATE TABLE nx_wallet_ledger(biz_no VARCHAR(128) PRIMARY KEY,user_id BIGINT,biz_type VARCHAR(40),asset VARCHAR(16),
                      direction VARCHAR(8),amount DECIMAL(24,6),status VARCHAR(16),created_at DATETIME(6),is_deleted TINYINT DEFAULT 0)
                    """);
            jdbc.execute("CREATE TABLE nx_config_item(config_key VARCHAR(128),config_value VARCHAR(128),status INT,is_deleted INT)");
            jdbc.execute("CREATE TABLE nx_user_otp_challenge(challenge_no VARCHAR(96) PRIMARY KEY,user_id BIGINT,code_hash CHAR(64),expires_at DATETIME,attempts INT,consumed_at DATETIME NULL,created_at DATETIME,updated_at DATETIME,is_deleted INT)");
            migrate(); migrate();
            jdbc.update("INSERT INTO nx_user VALUES(71,'ACTIVE',0,0),(72,'ACTIVE',0,0)");
            jdbc.update("INSERT INTO nx_user_wallet VALUES(71,900,0,100,0,0,?),(72,900,0,100,0,0,?)", NOW, NOW);
            jdbc.execute("ALTER TABLE nx_user ADD country_code VARCHAR(8) DEFAULT '+84', ADD phone VARCHAR(32) DEFAULT '912345678'");
            when(delivery.available("+84")).thenReturn(true); when(delivery.verificationCode("+84")).thenReturn("123456");
            var transport = mock(HdPayProperties.class);
            var properties = mock(HdPayPayoutProperties.class);
            when(properties.ready(transport)).thenReturn(true); when(properties.getBankCodes()).thenReturn(Set.of("VCB"));
            var config = mock(PayoutVndConfigService.class);
            var values = new HashMap<String,Object>(Map.of("channelEnabled", true, "providerReady", true,
                    "version",1L,"quoteTtlMinWithdraw",5,"minAmountUsd",20,"maxAmountUsd",5000,"feeRatePct",1,"feeMinUsd",1,"feeMaxUsd",25));
            values.put("baseRateVndPerUsdt",25000); values.put("sellSpreadPct",0);
            when(config.overview()).thenReturn(ApiResult.ok(values));
            doAnswer(i -> {
                jdbc.update("INSERT INTO nx_wallet_ledger(biz_no,user_id,biz_type,asset,direction,amount,status,created_at) VALUES(?,?,?,?,?,?,?,?)",
                        i.getArgument(0),i.getArgument(1),i.getArgument(2),i.getArgument(3),i.getArgument(4),i.getArgument(5),i.getArgument(6),NOW);
                return null;
            }).when(ledger).postLedgerEntry(anyString(),anyLong(),anyString(),anyString(),anyString(),any(),anyString(),anyString());
            var finalizer = proxy(new WithdrawalPayoutFinalizer(payouts, audit, ledger, CLOCK));
            transactions = proxy(new HdPayPayoutTransactions(bank, users, payouts, finalizer, cipher, transport, properties, config,
                    mock(OpsFinanceService.class), audit, outbox, CLOCK));
            var withdrawals = mock(AppWithdrawalService.class);
            when(withdrawals.policy(71L)).thenReturn(ApiResult.ok(Map.of("withdrawalEnabled",true,"policyVersion","d5-v1")));
            var idempotency = mock(ffdd.opsconsole.shared.idempotency.AdminIdempotencyService.class);
            when(idempotency.executeRetained(anyString(),anyString(),anyString(),any(),any())).thenAnswer(i ->
                    new org.springframework.transaction.support.TransactionTemplate(new DataSourceTransactionManager(source))
                            .execute(status -> ((java.util.function.Supplier<?>)i.getArgument(4)).get()));
            var env = new org.springframework.mock.env.MockEnvironment(); env.setActiveProfiles("dev");
            service = proxy(new BankWithdrawalService(bank, users, addresses,
                    delivery,proxy(new PayoutAddressOtpAttemptService(addresses)),
                    cipher,withdrawals,config,transport,properties,idempotency,audit,env,CLOCK));
        }
        @SuppressWarnings("unchecked") <T> T proxy(T target) {
            ProxyFactory proxy = new ProxyFactory(target);
            proxy.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(source), new AnnotationTransactionAttributeSource()));
            return (T) proxy.getProxy();
        }
        void migrate() throws Exception {
            try (var connection = source.getConnection()) {
                ScriptUtils.executeSqlScript(connection, new FileSystemResource("scripts/migrations/20260915_hdpay_bank_withdrawal.sql"));
                ScriptUtils.executeSqlScript(connection, new FileSystemResource("scripts/migrations/20260916_bank_beneficiary_verification.sql"));
            }
        }
        void seed() {
            seedBeneficiary();
            var encrypted = cipher.encrypt("0123456789\nNGUYEN VAN A", BankWithdrawalService.quoteAad(71, QN));
            bank.insertQuote(new BankWithdrawalMapper.Quote(QN,71L,"BNK-fixture",0L,"VCB","****6789",encrypted,
                    bd("100"),bd("1"),bd("99"),bd("25000"),bd("2475000"),1L,"d5-v1",NOW,NOW.plusMinutes(5),null));
            bank.useQuote(QN,NO); bank.insertOrder(NO,QN,71,NOW);
            jdbc.update("""
                    INSERT INTO nx_withdrawal_order(id,withdrawal_no,user_id,chain,target_address,amount,d2_net_receive,d2_nex_burned,status,d2_hold_until,
                    d5_payout_due_at,created_at,updated_at) VALUES(1,?,71,'BANK-VND','BANK-VND:BNK-fixture',100,99,0,'REVIEW_PASSED',?,?,?,?)
                    """, NO,NOW,NOW,NOW,NOW);
        }
        void seedBeneficiary() {
            bank.saveBeneficiary(71,"BNK-fixture","","****6789",cipher.encrypt("0123456789\nNGUYEN VAN A",BankWithdrawalService.beneficiaryAad(71,"BNK-fixture")),
                    NOW.minusDays(1),NOW.plusDays(1),0,NOW);
        }
        HdPayPayoutGateway.Order response(int status) { return new HdPayPayoutGateway.Order(NO,123L,status,bd("2475000"),"0123456789","NGUYEN VAN A","2"); }
        void wallet(String available, String pending) {
            assertEquals(0, bd(available).compareTo(jdbc.queryForObject("SELECT usdt_available FROM nx_user_wallet WHERE user_id=71",BigDecimal.class)));
            assertEquals(0, bd(pending).compareTo(jdbc.queryForObject("SELECT pending_withdraw FROM nx_user_wallet WHERE user_id=71",BigDecimal.class)));
        }
        void proofComplement(boolean settled) {
            assertEquals(settled, bank.settlementEvidence(NO) != null);
            assertEquals(!settled, bank.unresolvedOrders(71).stream().anyMatch(order -> NO.equals(order.withdrawalNo())));
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
                assertEquals("CONFIRMED",f.bank.settlementEvidence(NO).status());
                f.proofComplement(true);
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
            assertEquals("FAILED",f.bank.settlementEvidence(NO).status());
            f.proofComplement(true);
            f.jdbc.update("DELETE FROM nx_wallet_ledger WHERE user_id=71");
            assertNull(f.bank.settlementEvidence(NO)); // A provider FAILED label alone is not a refund receipt.
            f.proofComplement(false);
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
    @Test @EnabledIfEnvironmentVariable(named="NEXION_BANK_PAYOUT_IT",matches="true")
    void concurrentQuotesSerializeAndExpirySurvivesReadbackAndLateSubmit() throws Exception {
        isolated(f -> {
            f.seedBeneficiary(); var pool = Executors.newFixedThreadPool(2);
            try {
                Callable<Boolean> quote = () -> { try { f.service.quote(71,bd("100")); return true; }
                    catch (ffdd.opsconsole.shared.exception.BizException conflict) {
                        assertEquals("BANK_WITHDRAWAL_UNRESOLVED_INTENT", conflict.getMessage()); return false; } };
                var a=pool.submit(quote); var b=pool.submit(quote);
                assertEquals(1,(a.get(10,TimeUnit.SECONDS)?1:0)+(b.get(10,TimeUnit.SECONDS)?1:0));
                String q = f.bank.activeQuotes(71).get(0).quoteNo();
                assertEquals(q,f.service.recovery(71).getData().get("quoteNo"));
                f.jdbc.update("UPDATE nx_bank_payout_quote SET expires_at=? WHERE quote_no=?",NOW.minusSeconds(1),q);
                assertEquals("EXPIRED",f.service.recoverQuote(71,q).getData().get("state"));
                assertEquals(1,f.bank.expired(q)); assertNull(f.service.recovery(71).getData());
                f.jdbc.update("UPDATE nx_bank_payout_quote SET expires_at=? WHERE quote_no=?",NOW.plusHours(1),q);
                assertEquals("BANK_QUOTE_EXPIRED",f.service.submit(71,q,"late-after-expiry").getMessage());
                assertEquals(0,f.bank.useQuote(q,"WD-LATE"));
                f.wallet("900","100");
                var next = f.service.quote(71,bd("100")); assertEquals(0,next.getCode());
                String nextQuote = next.getData().get("quoteNo").toString();
                assertEquals("ABANDONED",f.service.abandonQuote(71,nextQuote).getData().get("state"));
                assertEquals("ABANDONED",f.service.recoverQuote(71,nextQuote).getData().get("state"));
                assertThrows(ffdd.opsconsole.shared.exception.BizException.class, () -> f.service.submit(71,nextQuote,"late-after-cancel"));
                assertEquals(0,f.bank.useQuote(nextQuote,"WD-LATE-CANCEL"));
                assertNull(f.service.recovery(71).getData());
            } finally { pool.shutdownNow(); assertTrue(pool.awaitTermination(15,TimeUnit.SECONDS)); }
        });
    }
    @Test @EnabledIfEnvironmentVariable(named="NEXION_BANK_PAYOUT_IT",matches="true")
    void legacyFirstAndReplacementBindingsAreImmediateWithoutVerificationRows() throws Exception {
        isolated(f -> {
            f.seedBeneficiary();
            f.jdbc.update("UPDATE nx_user_wallet SET usdt_available=1000,pending_withdraw=0 WHERE user_id=71");
            for (long version : new long[]{0,1}) {
                f.jdbc.update("UPDATE nx_bank_payout_beneficiary SET effective_at=?,version=? WHERE user_id=71",NOW.plusHours(24),version);
                assertEquals(NOW,f.bank.beneficiary(71L).effectiveAt());
                assertTrue((Boolean)((Map<?,?>)f.service.config(71).getData().get("beneficiary")).get("canWithdraw"));
                assertEquals(0,f.jdbc.queryForObject("SELECT COUNT(*) FROM nx_bank_beneficiary_verification",Integer.class));
                var result=f.service.quote(71,bd("100"));
                assertEquals(0,result.getCode());
                f.service.abandonQuote(71,result.getData().get("quoteNo").toString());
            }
            f.wallet("1000","0");
        });
    }
    @Test @EnabledIfEnvironmentVariable(named="NEXION_BANK_PAYOUT_IT",matches="true")
    void replacingImmediatelyConsumesSmsOnceAndPersistsFailureAttemptsOnRollback() throws Exception {
        isolated(f -> {
            f.seedBeneficiary();
            String challenge = "PAYOUT-BANK-"+"c".repeat(32);
            f.addresses.insertOtp(71L,challenge,"123456");
            var wrong = new BankWithdrawalService.BindRequest("","00123456789","NGUYEN VAN A",challenge,"000000");
            assertThrows(RuntimeException.class,()->f.service.bind(71,wrong,"bad-code"));
            assertEquals(1,f.jdbc.queryForObject("SELECT attempts FROM nx_user_otp_challenge WHERE challenge_no=?",Integer.class,challenge));
            assertEquals(0L,f.bank.beneficiary(71L).version());
            var correct = new BankWithdrawalService.BindRequest("","00123456789","NGUYEN VAN A",challenge,"123456");
            assertEquals(0,f.service.bind(71,correct,"valid-code").getCode());
            assertEquals(1L,f.bank.beneficiary(71L).version());
            assertEquals(NOW,f.bank.beneficiary(71L).effectiveAt());
            assertEquals(NOW,f.bank.beneficiary(71L).nextChangeAt());
            assertThrows(RuntimeException.class,()->f.service.bind(71,correct,"reused-code"));
            assertEquals(1L,f.bank.beneficiary(71L).version());
            for (String scenario : List.of("expired","exhausted","other-owner")) {
                String next="PAYOUT-BANK-"+UUID.randomUUID().toString().replace("-","");
                f.addresses.insertOtp(scenario.equals("other-owner") ? 72L : 71L,next,"123456");
                if (scenario.equals("expired")) f.jdbc.update("UPDATE nx_user_otp_challenge SET expires_at=DATE_SUB(NOW(),INTERVAL 1 SECOND) WHERE challenge_no=?",next);
                if (scenario.equals("exhausted")) f.jdbc.update("UPDATE nx_user_otp_challenge SET attempts=5 WHERE challenge_no=?",next);
                var request=new BankWithdrawalService.BindRequest("","00123456789","NGUYEN VAN A",next,"123456");
                assertThrows(RuntimeException.class,()->f.service.bind(71,request,scenario));
                assertEquals(1L,f.bank.beneficiary(71L).version());
            }
            f.wallet("900","100");
        });
    }
    @Test @EnabledIfEnvironmentVariable(named="NEXION_BANK_PAYOUT_IT",matches="true")
    void uncertainSmsDeliveryKeepsChallengeAndCooldownWithoutChangingBeneficiary() throws Exception {
        isolated(f -> {
            f.seedBeneficiary();
            doThrow(new IllegalStateException("provider timeout")).when(f.delivery).deliver(anyString(),anyString(),anyString(),anyString(),anyInt());
            assertEquals(503,f.service.sendOtp(71).getCode());
            assertEquals(1,f.jdbc.queryForObject("SELECT COUNT(*) FROM nx_user_otp_challenge",Integer.class));
            assertEquals("BANK_CHANGE_OTP_COOLDOWN",assertThrows(RuntimeException.class,()->f.service.sendOtp(71)).getMessage());
            assertEquals(0L,f.bank.beneficiary(71L).version());
            f.wallet("900","100");
        });
    }
    @Test @EnabledIfEnvironmentVariable(named="NEXION_BANK_PAYOUT_IT",matches="true")
    void preDispatchRejectionRequiresActualD2RefundLedgerBeforeIntentCanClear() throws Exception {
        isolated(f -> {
            f.seed(); f.jdbc.update("UPDATE nx_withdrawal_order SET status='REFUNDED' WHERE withdrawal_no=?",NO);
            assertNull(f.bank.settlementEvidence(NO)); assertNotNull(f.service.recovery(71).getData());
            f.proofComplement(false);
            f.jdbc.update("UPDATE nx_user_wallet SET usdt_available=1000,pending_withdraw=0 WHERE user_id=71");
            f.jdbc.update("INSERT INTO nx_wallet_ledger(biz_no,user_id,biz_type,asset,direction,amount,status,created_at) VALUES(?,71,'WITHDRAW_REFUND','USDT','IN',100,'SUCCESS',?)",
                    "D2-REFUND-"+NO,NOW);
            assertEquals("REFUNDED",f.bank.settlementEvidence(NO).status());
            f.proofComplement(true);
            assertNull(f.service.recovery(71).getData());
            f.wallet("1000","0");
        });
    }
    @Test @EnabledIfEnvironmentVariable(named="NEXION_BANK_PAYOUT_IT",matches="true")
    void unresolvedOrdersUseOwnerIndexAndSameProofForEveryMismatch() throws Exception {
        isolated(f -> {
            f.seed(); f.transactions.prepare(NO);
            var staleOrder = f.bank.order(NO);
            f.transactions.reconcile(NO,f.response(3));
            var proof = f.bank.settlementEvidence(NO);
            var receipt = BankWithdrawalService.settlementView(staleOrder,proof);
            assertEquals("paid",receipt.get("status")); assertEquals("123",receipt.get("providerOrderId"));
            assertEquals(3,receipt.get("providerStatus")); // Provider fields come from the same statement as the proof.
            f.proofComplement(true);
            for (String[] mutation : new String[][]{
                    {"UPDATE nx_withdrawal_order SET user_id=72", "UPDATE nx_withdrawal_order SET user_id=71"},
                    {"UPDATE nx_hdpay_payout SET provider_order_id=124", "UPDATE nx_hdpay_payout SET provider_order_id=123"},
                    {"UPDATE nx_withdrawal_payout_ledger SET amount_usdt=98 WHERE status='CONFIRMED'", "UPDATE nx_withdrawal_payout_ledger SET amount_usdt=99 WHERE status='CONFIRMED'"},
                    {"UPDATE nx_hdpay_payout SET state='MANUAL_REVIEW'", "UPDATE nx_hdpay_payout SET state='PAID'"}}) {
                f.jdbc.update(mutation[0]); f.proofComplement(false);
                f.jdbc.update(mutation[1]); f.proofComplement(true);
            }
            for (int i=0; i<256; i++) f.bank.insertOrder("WD-OTHER-"+i,"BQ-OTHER-"+i,72,NOW);
            assertTrue(f.bank.unresolvedOrders(71).isEmpty());
            assertEquals(256,f.bank.unresolvedOrders(72).size());
            assertEquals("user_id,created_at,withdrawal_no",f.jdbc.queryForObject("""
                    SELECT GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX) FROM information_schema.STATISTICS
                    WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_hdpay_payout' AND INDEX_NAME='idx_hdpay_payout_owner'
                    """,String.class));
            f.jdbc.execute("ANALYZE TABLE nx_hdpay_payout");
            String sql = String.join(" ",BankWithdrawalMapper.class.getMethod("unresolvedOrders",long.class)
                    .getAnnotation(org.apache.ibatis.annotations.Select.class).value()).replace("#{userId}","71");
            var payoutPlan = f.jdbc.queryForList("EXPLAIN "+sql).stream().filter(row -> "p".equals(row.get("table"))).findFirst().orElseThrow();
            assertEquals("idx_hdpay_payout_owner",payoutPlan.get("key"));
        });
    }
    @Test @EnabledIfEnvironmentVariable(named="NEXION_BANK_PAYOUT_IT",matches="true")
    void withdrawalMethodCatalogMigrationIsRepeatableAndPreservesExistingRoutes() throws Exception {
        isolated(f -> {
            f.jdbc.execute("""
                    CREATE TABLE nx_behavior_page_catalog(route VARCHAR(128) PRIMARY KEY,title_zh VARCHAR(128),
                      page_level INT,parent_l1 VARCHAR(128),parent_l2 VARCHAR(128),tracked TINYINT,
                      source_revision VARCHAR(64),is_deleted TINYINT)
                    """);
            f.jdbc.update("INSERT INTO nx_behavior_page_catalog VALUES('/pages/me/wallet','existing title',1,'existing parent','existing parent',0,'fixture-original',0)");
            for (int i=0;i<2;i++) try (var connection=f.source.getConnection()) {
                ScriptUtils.executeSqlScript(connection,new FileSystemResource("scripts/migrations/20260916_l6_withdrawal_method_route.sql"));
            }
            assertEquals(2,f.jdbc.queryForObject("SELECT COUNT(*) FROM nx_behavior_page_catalog",Integer.class));
            assertEquals(1,f.jdbc.queryForObject("SELECT tracked FROM nx_behavior_page_catalog WHERE route='/pages/me/wallet-withdraw-method'",Integer.class));
            assertEquals("fixture-original",f.jdbc.queryForObject("SELECT source_revision FROM nx_behavior_page_catalog WHERE route='/pages/me/wallet'",String.class));
            assertEquals(0,f.jdbc.queryForObject("SELECT tracked FROM nx_behavior_page_catalog WHERE route='/pages/me/wallet'",Integer.class));
        });
    }
}
