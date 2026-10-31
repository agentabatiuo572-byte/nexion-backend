package ffdd.opsconsole.finance.application;

import static org.assertj.core.api.Assertions.*;

import com.baomidou.mybatisplus.core.*;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.auth.mapper.AdminMapper;
import ffdd.opsconsole.auth.application.SupportFundBirthReadService;
import ffdd.opsconsole.auth.mapper.SupportFundBirthReadMapper;
import ffdd.opsconsole.content.application.*;
import ffdd.opsconsole.content.facade.SupportPaymentAttributionFacade;
import ffdd.opsconsole.content.mapper.*;
import ffdd.opsconsole.finance.facade.*;
import ffdd.opsconsole.finance.mapper.*;
import ffdd.opsconsole.platform.application.*;
import ffdd.opsconsole.platform.infrastructure.MybatisPlatformConfigRepository;
import ffdd.opsconsole.platform.mapper.PlatformConfigItemMapper;
import ffdd.opsconsole.shared.audit.*;
import ffdd.opsconsole.shared.audit.mapper.AuditLogMapper;
import ffdd.opsconsole.shared.config.MybatisMetaObjectHandler;
import ffdd.opsconsole.shared.idempotency.*;
import ffdd.opsconsole.shared.idempotency.mapper.AdminIdempotencyRecordMapper;
import ffdd.opsconsole.shared.outbox.*;
import ffdd.opsconsole.shared.outbox.mapper.EventOutboxMapper;
import ffdd.opsconsole.treasury.application.TreasuryLedgerPostingFacadeAdapter;
import ffdd.opsconsole.treasury.infrastructure.MybatisTreasuryLedgerRepository;
import ffdd.opsconsole.treasury.mapper.TreasuryLedgerMapper;
import java.math.BigDecimal;
import java.nio.file.*;
import java.time.Clock;
import java.util.*;
import javax.sql.DataSource;
import org.apache.ibatis.mapping.Environment;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;

/** Real refund/recovery services and persisted journals. Original paid claims are fixture seeds,
 * not a claim of App/PIN/PC approval or historical AUTH birth coverage. No financial collaborator is mocked. */
@EnabledIfEnvironmentVariable(named="TRIAL_ACCEPTANCE_MYSQL_URL",matches="jdbc:mysql://127\\.0\\.0\\.1:33343/outstanding_trial_.*")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TrialRefundMySqlAcceptanceTest {
    final ObjectMapper json=new ObjectMapper().findAndRegisterModules();
    final List<Map<String,Object>> evidence=new ArrayList<>();
    DataSource source; JdbcTemplate jdbc; DataSourceTransactionManager manager; TransactionTemplate tx;
    SqlSessionTemplate sessions; AdminIdempotencyService commands; EarningsReleaseService earnings;
    E4OrderRefundSettlementFacade refunds; SupportPaymentAttributionFacade attribution;
    TreasuryLedgerPostingFacadeAdapter ledger; SupportPaymentSourceMapper payments;
    SupportFundJournalBindingService binder;
    FinanceSupportPaymentFactsFacade finance;
    long nextUser=700000000000L+Math.floorMod(UUID.randomUUID().getMostSignificantBits(),100000000000L);

    @BeforeAll void connectAndWireProductionServices() {
        source=new DriverManagerDataSource(System.getenv("TRIAL_ACCEPTANCE_MYSQL_URL"),"root","");
        jdbc=new JdbcTemplate(source);
        assertThat(jdbc.queryForObject("SELECT DATABASE()",String.class)).startsWith("outstanding_trial_");
        assertThat(jdbc.queryForObject("SELECT @@port",Integer.class)).isEqualTo(33343);
        manager=new DataSourceTransactionManager(source); tx=new TransactionTemplate(manager);
        var cfg=new MybatisConfiguration(new Environment("trial-real-mysql",new SpringManagedTransactionFactory(),source));
        cfg.setMapUnderscoreToCamelCase(true);
        var global=new GlobalConfig(); global.setDbConfig(new GlobalConfig.DbConfig());
        global.setMetaObjectHandler(new MybatisMetaObjectHandler(Clock.systemUTC())); GlobalConfigUtils.setGlobalConfig(cfg,global);
        for(var type:List.of(EarningsReleaseMapper.class,E4OrderRefundMapper.class,SupportFundJournalMapper.class,
                TreasuryLedgerMapper.class,AdminIdempotencyRecordMapper.class,AuditLogMapper.class,AdminMapper.class,
                PlatformConfigItemMapper.class,EventOutboxMapper.class,SupportPaymentSourceMapper.class,
                SupportPaymentFactMapper.class,SupportPaymentAttributionMapper.class,SupportPaymentCaptureHistoryMapper.class,
                SupportPaymentHistoryBirthMapper.class,SupportFundReadMapper.class,SupportFundBirthReadMapper.class)) cfg.addMapper(type);
        sessions=new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(cfg));
        var repository=new MybatisPlatformConfigRepository(mapper(PlatformConfigItemMapper.class));
        var policy=new A2RuntimePolicy(repository);
        var audit=new AuditLogService(mapper(AuditLogMapper.class),new AuditLogSanitizer(json),
                new ApplicationNameProperties(),new AuditProperties(),mapper(AdminMapper.class),policy);
        var expiry=proxy(new AdminIdempotencyExpiryTransitionExecutor(mapper(AdminIdempotencyRecordMapper.class)));
        commands=new AdminIdempotencyService(proxy(new AdminIdempotencyTransactionExecutor(
                mapper(AdminIdempotencyRecordMapper.class),json,expiry)),Clock.systemUTC());
        var outbox=new EventOutboxService(mapper(EventOutboxMapper.class),json,new OutboxProperties(),new A4RuntimePolicyService(repository));
        // postLedgerEntry does not enter commission capture; all its actual SQL and mutexes remain real.
        var ledgerRepository=proxy(new MybatisTreasuryLedgerRepository(mapper(TreasuryLedgerMapper.class),outbox,null));
        ledger=new TreasuryLedgerPostingFacadeAdapter(ledgerRepository,null);
        binder=proxy(new SupportFundJournalBindingService(mapper(SupportFundJournalMapper.class),source,json));
        var fund=new SupportFundEarningsAdapter(binder,mapper(SupportFundJournalMapper.class),mapper(EarningsReleaseMapper.class),json);
        var env=new MockEnvironment(); env.setActiveProfiles("dev");
        earnings=proxy(new EarningsReleaseService(mapper(EarningsReleaseMapper.class),null,commands,audit,
                new FundsSandboxProfileGuard(new FundsSandboxProperties(),env),fund));
        var history=new SupportPaymentCaptureHistoryService(mapper(SupportPaymentCaptureHistoryMapper.class),mapper(SupportPaymentHistoryBirthMapper.class));
        payments=mapper(SupportPaymentSourceMapper.class);
        finance=proxy(new SupportPaymentSourceService(payments,new SupportPaymentFactService(mapper(SupportPaymentFactMapper.class)),
                source,json,history,binder,mapper(SupportFundJournalMapper.class),mapper(EarningsReleaseMapper.class)));
        attribution=proxy(new SupportPaymentAttributionService(mapper(SupportPaymentAttributionMapper.class),finance,audit,source,json));
        var reversal=proxy(new TrialConversionRewardReversal(mapper(E4OrderRefundMapper.class),mapper(EarningsReleaseMapper.class),
                earnings,ledger,audit,manager));
        refunds=proxy(new E4OrderRefundSettlementFacadeAdapter(attribution,mapper(E4OrderRefundMapper.class),reversal));
    }

    @AfterAll void saveEvidence() throws Exception {
        var path=Path.of(System.getenv("TRIAL_ACCEPTANCE_EVIDENCE")); Files.createDirectories(path.getParent());
        Files.writeString(path,json.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of(
                "database",jdbc.queryForObject("SELECT DATABASE()",String.class),"mysql",jdbc.queryForObject("SELECT VERSION()",String.class),
                "cases",evidence,"scope","Real services/transactions/journal; original paid claim fixture, no HTTP/UI approval or AUTH birth proof")));
    }

    @Test void unspentRewardsRecoverAfterRefundCommitAndOriginalKeyHasNoSecondEffect() {
        var f=paidClaim(true); var before=wallet(f.user);
        var first=refund(f,"unspent");
        assertThat(first.walletAfter().subtract(first.walletBefore())).isEqualByComparingTo("200");
        assertBalance(f.user,"1000","0");
        assertRecovery(f,"65.56","30","0","0");
        assertThat(actions(f)).containsExactly("H2_TRIAL_REFUND_REWARD_PENDING","H2_TRIAL_REFUND_REWARD_REVERSED");
        var durable=witness(f);
        assertThat(refund(f,"unspent")).usingRecursiveComparison()
                .withComparatorForType(BigDecimal::compareTo,BigDecimal.class).isEqualTo(first);
        assertThat(witness(f)).isEqualTo(durable);
        assertJournalBindings(f,2);
        record("08-unspent-and-retained-replay",f,Map.of("before",before,"after",wallet(f.user),"durable",durable));
    }

    @Test void partialSpendingNeverRecoversPrincipalOrOtherRewardsEvenAfterNewCredit() {
        var f=paidClaim(true);
        movement(f,"USDT","-20"); movement(f,"UVT","-7");
        movement(f,"USDT","100");
        String otherUsdt=credit(f,"OTHER_INCOME",f.claim+":OTHER-USDT","USDT","11");
        String otherUvt=credit(f,"OTHER_INCOME",f.claim+":OTHER-UVT","UVT","9");
        refund(f,"partial");
        assertBalance(f.user,"1111","9");
        assertRecovery(f,"45.56","23","20","7");
        assertThat(actions(f)).containsExactly("H2_TRIAL_REFUND_REWARD_PENDING","H2_TRIAL_REFUND_REWARD_MANUAL_REVIEW");
        assertThat(jdbc.queryForList("SELECT recovered_amount FROM nx_earnings_release_entry WHERE entry_no IN (?,?)",BigDecimal.class,otherUsdt,otherUvt))
                .allSatisfy(v->assertThat(v).isZero());
        assertJournalBindings(f,2);
        record("09-partial-principal-other-source-conservation",f,Map.of("after",wallet(f.user),"recoveries",recoveries(f)));
    }

    @Test void originalAccruedRuleRefundLeavesItsRewardsAndCreatesNoRecoveryAudits() {
        var f=paidClaim(false);
        assertThat(jdbc.queryForObject("SELECT settled_trial_nex FROM nx_trial_claim WHERE claim_no=?",BigDecimal.class,f.claim)).isNull();
        refund(f,"legacy");
        assertBalance(f.user,"1065.56","30");
        assertThat(actions(f)).isEmpty(); assertThat(recoveries(f)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_wallet_ledger WHERE user_id=? AND biz_type='TRIAL_BONUS_REVERSAL'",Integer.class,f.user)).isZero();
        record("10-accrued-order-no-recovery",f,Map.of("after",wallet(f.user)));
    }

    @Test void pendingAuditFailureRollsBackRefundCashBillPaymentAndJournalAndNeverRunsAfterCommit() {
        var f=paidClaim(true); var before=witness(f);
        jdbc.execute("CREATE TRIGGER trial_reject_pending BEFORE INSERT ON nx_audit_log FOR EACH ROW BEGIN IF NEW.action='H2_TRIAL_REFUND_REWARD_PENDING' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='trial fixture audit rejection'; END IF; END");
        try { assertThatThrownBy(()->refund(f,"audit-failure")).hasMessageContaining("trial fixture audit rejection"); }
        finally { jdbc.execute("DROP TRIGGER trial_reject_pending"); }
        assertThat(witness(f)).isEqualTo(before);
        record("08-required-pending-audit-rollback",f,Map.of("unchanged",before));
    }

    @Test void realTrialSourceProjectionUsesSettledUvtRatherThanAccruedAndReaderCannotInventReady() {
        var f=paidClaim(true);
        var projection=payments.trialFundSource(f.user,f.claim);
        assertThat(projection).hasSize(1);
        assertThat((BigDecimal)projection.get(0).get("shadowNex")).isEqualByComparingTo("30");
        assertThat(jdbc.queryForObject("SELECT shadow_accrued_nex FROM nx_trial_claim WHERE claim_no=?",BigDecimal.class,f.claim)).isEqualByComparingTo("5");
        var read=new TransactionTemplate(manager); read.setReadOnly(true);
        read.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
        var funds=new SupportFundReadService(mapper(SupportFundReadMapper.class),
                new SupportFundBirthReadService(mapper(SupportFundBirthReadMapper.class)),finance,binder,source);
        var snapshot=read.execute(s->funds.read(List.of(f.user)));
        assertThat(snapshot.customers()).singleElement().satisfies(c->assertThat(c.state()).isEqualTo(SupportFundsReadFacade.EvidenceState.UNKNOWN));
        record("11-source-projection-only-full-ready-not-proven",f,Map.of("projection",projection,"actualReader",snapshot));
    }

    @Test void recoveryLedgerFailureRollsBackOnlyThatRewardAndRefundStaysCommittedWithManualTerminalAudit() {
        var f=paidClaim(true);
        jdbc.execute("CREATE TRIGGER trial_reject_reversal BEFORE INSERT ON nx_wallet_ledger FOR EACH ROW BEGIN IF NEW.biz_type='TRIAL_BONUS_REVERSAL' AND NEW.asset='UVT' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='trial fixture reversal rejection'; END IF; END");
        try {refund(f,"recovery-failure");}
        finally {jdbc.execute("DROP TRIGGER trial_reject_reversal");}
        assertBalance(f.user,"1000","30");
        assertThat(jdbc.queryForObject("SELECT payment_status FROM nx_payment_record WHERE order_no=?",String.class,f.order)).isEqualTo("REFUNDED");
        assertThat(recoveries(f)).singleElement().satisfies(r->assertThat(r.get("asset")).isEqualTo("USDT"));
        assertThat(jdbc.queryForObject("SELECT recovered_amount FROM nx_earnings_release_entry WHERE user_id=? AND asset='UVT'",BigDecimal.class,f.user)).isZero();
        assertThat(actions(f)).containsExactly("H2_TRIAL_REFUND_REWARD_PENDING","H2_TRIAL_REFUND_REWARD_MANUAL_REVIEW");
        assertThat(jdbc.queryForObject("SELECT detail_json FROM nx_audit_log WHERE biz_no=? AND action='H2_TRIAL_REFUND_REWARD_MANUAL_REVIEW'",String.class,f.order))
                .contains("UVT","error","trial fixture reversal rejection");
        assertJournalBindings(f,1);
        record("09-after-commit-reward-failure-isolated-rollback",f,Map.of("after",wallet(f.user),"recoveries",recoveries(f)));
    }

    record Claim(long user,String claim,String order) {}
    Claim paidClaim(boolean full) {
        long user=++nextUser; String suffix=UUID.randomUUID().toString().replace("-","").substring(0,12);
        var f=new Claim(user,"TRIAL-IT-"+suffix,"TRC-IT-"+suffix);
        tx.executeWithoutResult(s->{
            var paidAt=jdbc.queryForObject("SELECT NOW()-INTERVAL 1 DAY",java.time.LocalDateTime.class);
            jdbc.update("INSERT INTO nx_user(id,country_code,phone,client_ip,password_hash,nickname,referral_code,sandbox,status) VALUES(?,'00',?,'127.0.0.1','fixture',?,?,0,'ACTIVE')",user,"trial"+user,"trial fixture",suffix);
            jdbc.update("INSERT INTO nx_user_wallet(user_id,sandbox) VALUES(?,0)",user);
            jdbc.update("UPDATE nx_user_wallet SET usdt_available=800,cumulative_deposit_usdt=1000,version=version+1 WHERE user_id=?",user);
            jdbc.update("INSERT INTO nx_wallet_ledger(user_id,biz_no,biz_type,asset,direction,amount,balance_after,status,created_at) VALUES(?,?,'FIXTURE_PRINCIPAL','USDT','IN',1000,1000,'SUCCESS',NOW()-INTERVAL 2 DAY)",user,f.claim+":PRINCIPAL");
            jdbc.update("INSERT INTO nx_wallet_ledger(user_id,biz_no,biz_type,asset,direction,amount,balance_after,status,created_at) VALUES(?,?,'TRIAL_CHARGE','USDT','OUT',200,800,'POSTED',?)",user,f.claim+":CHARGE",paidAt);
            jdbc.update("INSERT INTO nx_order(user_id,order_no,product_id,amount_usdt,subtotal_usdt,discount_usdt,payment_status,order_status,order_type,paid_at,payment_no) VALUES(?,?,1,200,260,60,'PAID','PAID','TRIAL_CONVERT',?,?)",user,f.order,paidAt,f.order+":PAY");
            jdbc.update("INSERT INTO nx_payment_record(payment_no,order_no,user_id,provider,provider_payment_id,amount_usdt,payment_status,paid_at) VALUES(?,?,?,'UVEL_WALLET',?,200,'PAID',?)",f.order+":PAY",f.order,user,f.order,paidAt);
            jdbc.update("INSERT INTO nx_user_device(user_id,instance_no,name,device_type,status,source_order_no,source_channel,ownership_status,source_environment) VALUES(?,?,'trial fixture','DEVICE','ACTIVE',?,'TRIAL','OWNED','PRODUCTION')",user,f.order+":DEV",f.order);
            long device=jdbc.queryForObject("SELECT id FROM nx_user_device WHERE instance_no=?",Long.class,f.order+":DEV");
            jdbc.update("INSERT INTO nx_trial_claim(user_id,claim_no,device_name,status,claimed_at,expires_at,user_device_id,duration_days,daily_usdt,daily_nex,offset_cap_usdt,price_usdt,shadow_accrued_usdt,shadow_accrued_nex,remainder_usdt,settled_trial_nex,settlement_amount_usdt,settled_at,settlement_snapshot) VALUES(?,?,'trial fixture','REDEEMED',NOW()-INTERVAL 2 DAY,NOW()+INTERVAL 1 DAY,?,3,38.52,10,50,260,19.26,5,65.56,30,200,?,?)",user,f.claim,device,paidAt,"trigger=convert,orderNo="+f.order+",settlementPolicy="+(full?"FULL_TERM_ON_CONVERSION":"ACCRUED_ON_CONVERSION"));
            if(!full) jdbc.update("UPDATE nx_trial_claim SET claimed_at=DATE_SUB(?,INTERVAL 3 DAY),expires_at=?,shadow_accrued_usdt=115.56,shadow_accrued_nex=30,settled_trial_nex=NULL,settlement_snapshot=? WHERE claim_no=?",
                    paidAt,paidAt,"trigger=convert,orderNo="+f.order+",amountUsdt=200",f.claim);
        });
        credit(f,"H2_TRIAL_REMAINDER",f.claim+":REMAINDER","USDT","65.56");
        credit(f,"H2_TRIAL_BONUS",f.claim+":NEX","UVT","30");
        return f;
    }
    String credit(Claim f,String type,String ref,String asset,String amount) {
        return tx.execute(s->{String entry=earnings.credit(f.user,"USER:"+f.user,type,ref,asset,new BigDecimal(amount),"withdrawable",ref);
            ledger.postLedgerEntry(ref,f.user,"TRIAL_BONUS",asset,"IN",new BigDecimal(amount),"POSTED","isolated trial fixture reward");return entry;});
    }
    void movement(Claim f,String asset,String amount) {
        tx.executeWithoutResult(s->{var delta=new BigDecimal(amount); String column=asset.equals("USDT")?"usdt_available":"nex_available";
            jdbc.update("UPDATE nx_user_wallet SET "+column+"="+column+"+?,version=version+1 WHERE user_id=?",delta,f.user);
            ledger.postLedgerEntry(f.claim+":MOVE:"+UUID.randomUUID(),f.user,"FIXTURE_SPEND_OR_PRINCIPAL",asset,
                    delta.signum()<0?"OUT":"IN",delta.abs(),"SUCCESS","isolated ordinary movement");});
    }
    E4OrderRefundSettlementFacade.Settlement refund(Claim f,String key) {
        return commands.executeRetained("E4_ORDER_REFUND","trial-acceptance-"+f.order+":"+key,
                "a".repeat(64),E4OrderRefundSettlementFacade.Settlement.class,()->{
                    var prepared=attribution.prepare(f.user,SupportPaymentFacts.Source.ORDER_REFUND,"E4-REFUND-"+f.order);
                    // The production E4 caller moves the order before invoking its settlement facade.
                    jdbc.update("UPDATE nx_order SET payment_status='REFUNDED',order_status='REFUNDED' WHERE order_no=?",f.order);
                    var result=refunds.settle(f.order,f.user,new BigDecimal("200"),"WALLET","isolated E01 acceptance","trial fixture",key,prepared);
                    assertThat(recoveries(f)).as("recovery must wait for refund commit").isEmpty();
                    return result;
                });
    }
    void assertRecovery(Claim f,String usdt,String uvt,String usdtOut,String uvtOut) {
        var rows=recoveries(f); assertThat(rows).hasSize(2);
        for(var row:rows) {boolean dollar=row.get("asset").equals("USDT");
            assertThat((BigDecimal)row.get("recovered")).isEqualByComparingTo(dollar?usdt:uvt);
            assertThat((BigDecimal)row.get("outstanding")).isEqualByComparingTo(dollar?usdtOut:uvtOut);
            assertThat(((BigDecimal)row.get("recovered")).add((BigDecimal)row.get("outstanding")))
                    .isEqualByComparingTo((BigDecimal)row.get("requested"));
            var original=jdbc.queryForMap("SELECT amount,recovered_amount FROM nx_earnings_release_entry WHERE entry_no=?",row.get("entry_no"));
            assertThat((BigDecimal)original.get("amount")).isEqualByComparingTo((BigDecimal)row.get("requested"));
            assertThat((BigDecimal)original.get("recovered_amount")).isEqualByComparingTo((BigDecimal)row.get("recovered"));
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_wallet_ledger WHERE user_id=? AND biz_type='TRIAL_BONUS_REVERSAL'",Integer.class,f.user)).isEqualTo(2);
        for(String asset:List.of("USDT","UVT")) {
            var amount=jdbc.queryForObject("SELECT amount FROM nx_wallet_ledger WHERE user_id=? AND biz_type='TRIAL_BONUS_REVERSAL' AND asset=?",BigDecimal.class,f.user,asset);
            assertThat(amount).isEqualByComparingTo(asset.equals("USDT")?usdt:uvt);
        }
    }
    void assertJournalBindings(Claim f,int recoveryCount) {
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_support_fund_operation o JOIN nx_support_fund_operation_party p ON p.operation_id=o.id WHERE p.customer_id=? AND o.business_type='SUPPORT_EARNINGS' AND o.phase='RECOVER'",Integer.class,f.user)).isEqualTo(recoveryCount);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_support_fund_operation_leg l JOIN nx_support_fund_operation o ON o.id=l.operation_id JOIN nx_support_fund_operation_party p ON p.operation_id=o.id WHERE p.customer_id=? AND o.business_type='SUPPORT_EARNINGS' AND o.phase='RECOVER'",Integer.class,f.user)).isEqualTo(recoveryCount);
    }
    List<Map<String,Object>> recoveries(Claim f) {return jdbc.queryForList("SELECT * FROM nx_earnings_source_recovery WHERE user_id=? ORDER BY asset",f.user);}
    List<String> actions(Claim f) {return jdbc.queryForList("SELECT action FROM nx_audit_log WHERE biz_no=? AND action LIKE 'H2_TRIAL_REFUND_REWARD_%' ORDER BY id",String.class,f.order);}
    Map<String,Object> wallet(long user) {return jdbc.queryForMap("SELECT usdt_available,nex_available,version,fund_revision,earnings_usdt_debited,earnings_nex_debited FROM nx_user_wallet WHERE user_id=?",user);}
    void assertBalance(long user,String usdt,String uvt) {
        var w=wallet(user);assertThat((BigDecimal)w.get("usdt_available")).isEqualByComparingTo(usdt);assertThat((BigDecimal)w.get("nex_available")).isEqualByComparingTo(uvt);
        for(String asset:List.of("USDT","UVT")) assertThat(jdbc.queryForObject(
                "SELECT COALESCE(SUM(CASE direction WHEN 'IN' THEN amount ELSE -amount END),0) FROM nx_wallet_ledger WHERE user_id=? AND asset=? AND is_deleted=0",BigDecimal.class,user,asset))
                .as("persisted ledger inflows minus outflows equal the wallet").isEqualByComparingTo(asset.equals("USDT")?usdt:uvt);
    }
    Map<String,Object> witness(Claim f) {
        var result=new LinkedHashMap<String,Object>();result.put("wallet",wallet(f.user));
        for(String table:List.of("nx_wallet_ledger","nx_wallet_bill","nx_payment_record","nx_earnings_source_recovery","nx_earnings_release_entry"))
            result.put(table,jdbc.queryForList("SELECT * FROM "+table+" WHERE user_id=? ORDER BY id",f.user));
        result.put("order",jdbc.queryForList("SELECT * FROM nx_order WHERE user_id=? ORDER BY id",f.user));
        result.put("audits",jdbc.queryForList("SELECT * FROM nx_audit_log WHERE user_id=? ORDER BY id",f.user));
        result.put("operations",jdbc.queryForList("SELECT o.* FROM nx_support_fund_operation o JOIN nx_support_fund_operation_party p ON p.operation_id=o.id WHERE p.customer_id=? ORDER BY o.id",f.user));
        result.put("journal",jdbc.queryForList("SELECT * FROM nx_support_wallet_journal WHERE new_user_id=? ORDER BY revision",f.user));return result;
    }
    void record(String name,Claim f,Map<String,Object> values) {evidence.add(Map.of("case",name,"result","PASS","user",f.user,"claim",f.claim,"order",f.order,"details",values));}
    <T>T mapper(Class<T> type) {return sessions.getMapper(type);}
    @SuppressWarnings("unchecked") <T>T proxy(T value) {var factory=new ProxyFactory(value);factory.setProxyTargetClass(true);factory.addAdvice(new TransactionInterceptor(manager,new AnnotationTransactionAttributeSource()));return (T)factory.getProxy();}
}
