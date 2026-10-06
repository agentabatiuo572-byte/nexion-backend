package ffdd.opsconsole.team.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.finance.application.*;
import ffdd.opsconsole.finance.mapper.EarningsReleaseMapper;
import ffdd.opsconsole.market.mapper.NexMarketMapper;
import ffdd.opsconsole.platform.application.A2ReplayContext;
import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import ffdd.opsconsole.risk.application.RiskReleaseParamsService;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.config.MybatisMetaObjectHandler;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import ffdd.opsconsole.shared.outbox.EventOutboxService;
import ffdd.opsconsole.team.domain.DirectReferralPolicy;
import ffdd.opsconsole.team.dto.DirectReferralPolicyRequest;
import ffdd.opsconsole.team.mapper.DirectReferralMapper;
import ffdd.opsconsole.team.mapper.F5CommissionMapper;
import ffdd.opsconsole.treasury.application.TreasuryLedgerPostingFacadeAdapter;
import ffdd.opsconsole.treasury.facade.TreasuryCoverageFacade;
import ffdd.opsconsole.treasury.facade.TreasuryCoverageSnapshot;
import ffdd.opsconsole.treasury.infrastructure.MybatisTreasuryLedgerRepository;
import ffdd.opsconsole.treasury.mapper.TreasuryLedgerMapper;
import java.math.BigDecimal;
import java.nio.file.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import org.apache.ibatis.mapping.Environment;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

/** Actual MySQL transactions. This opt-in is restricted to the task's disposable local database. */
@EnabledIfEnvironmentVariable(named="DIRECT_REFERRAL_RUNTIME",matches="1")
class DirectReferralMySqlRuntimeTest {
    private DataSource dataSource;
    private JdbcTemplate jdbc;
    private SqlSessionTemplate session;
    private DirectReferralMapper mapper;
    private DirectReferralPolicyService policies;
    private DirectReferralService service;
    private F5CommissionService f5;
    private OpsTeamService ops;
    private MybatisTreasuryLedgerRepository treasury;
    private TreasuryCoverageFacade coverage;
    private final ObjectMapper json=new ObjectMapper().findAndRegisterModules();
    private final MutableClock clock=new MutableClock(Instant.now().plusSeconds(5).truncatedTo(ChronoUnit.SECONDS));
    private final AtomicBoolean failNex=new AtomicBoolean();
    private final AtomicBoolean changeRisk=new AtomicBoolean();
    private final String marker="DRTEST-"+UUID.randomUUID().toString().substring(0,8);
    private final Map<String,List<Map<String,Object>>> groups=new LinkedHashMap<>();
    private long a,b,c,d;

    @Test void genericRejectCannotReverseEitherAssetInAnyGroupStateOrReplay() throws Exception {
        setUp();
        try {
            for(String state:List.of("UNLOCKED","COOLING","FROZEN")) {
                clock.advance();publish(rule("10",state.equals("UNLOCKED")?0:30),rule("5",state.equals("UNLOCKED")?0:30));
                for(String kind:List.of("direct_purchase","direct_device_earning")) {
                    String source=state+"-"+kind;
                    if(kind.equals("direct_purchase"))order(source,b,"100");
                    else {receipt(source,b,"10","100",false);source="RECEIPT-"+source;}
                    service.settle(kind,ref(source),b);
                    var row=groupRow(source);
                    if(state.equals("FROZEN"))service.changeStatus(((Number)row.get("usdt_event_id")).longValue(),"FROZEN",0L);
                    for(String eventKey:List.of("usdt_event_id","nex_event_id")) {
                        long event=((Number)row.get(eventKey)).longValue();
                        long version=jdbc.queryForObject("SELECT version FROM nx_commission_event WHERE id=?",Long.class,event);
                        String key="F.commission.CM-"+event+".status";
                        for(boolean replay:List.of(false,true)) {
                            var before=rejectionSnapshot(source);
                            if(replay)A2ReplayContext.enterReplay(ref("GENERIC-"+event));
                            Throwable rejected;
                            try {
                                rejected=catchThrowable(()->{
                                    if(replay)ops.replay(new ffdd.opsconsole.platform.domain.AuditReplayCommand("F","f_commission_status",Map.of("key",key,"value","REJECTED","expectedVersion",version)),new ffdd.opsconsole.platform.domain.AuditReplayContext("checker","isolated generic reject attempt",ref("GENERIC-REPLAY-"+event)));
                                    else ops.updateConfig(ref("GENERIC-DIRECT-"+event),new ffdd.opsconsole.team.dto.TeamCommissionConfigUpdateRequest(key,"REJECTED","isolated generic reject attempt","admin",version));
                                });
                            } finally {A2ReplayContext.exitReplay();}
                            var after=rejectionSnapshot(source);
                            Path evidence=Path.of(System.getenv().getOrDefault("DIRECT_REFERRAL_EVIDENCE_DIR","target/direct-referral-runtime"));Files.createDirectories(evidence);
                            json.writerWithDefaultPrettyPrinter().writeValue(evidence.resolve("generic-"+state+"-"+kind+"-"+eventKey+"-"+replay+".json").toFile(),Map.of("state",state,"kind",kind,"replay",replay,"denial",rejected==null?"ALLOWED":rejected.getMessage(),"before",before,"after",after));
                            assertThat(rejected).isInstanceOf(ffdd.opsconsole.shared.exception.BizException.class).hasMessage("DIRECT_REFERRAL_REVERSE_REQUIRES_F5_COMMAND");
                            assertThat(((ffdd.opsconsole.shared.exception.BizException)rejected).getCode()).isEqualTo(409);
                            assertThat(after).isEqualTo(before);
                        }
                    }
                }
            }
            String source="UNLOCKED-direct_purchase";long event=((Number)groupRow(source).get("usdt_event_id")).longValue();
            var before=rejectionSnapshot(source);
            assertThat(f5.reverse("CM-"+event,ref("NO-APPROVAL"),new ffdd.opsconsole.team.dto.F5CommissionReverseRequest(ref(source),"isolated dedicated reversal","admin")).getMessage()).isEqualTo("A2_CONFIRMATION_REQUIRED");
            A2ReplayContext.enterReplay(ref("MISSING-EVIDENCE"));
            try {assertThatThrownBy(()->f5.reverse("CM-"+event,ref("MISSING-EVIDENCE"),new ffdd.opsconsole.team.dto.F5CommissionReverseRequest("","isolated dedicated reversal","checker"))).hasMessage("REFUND_REF_REQUIRED");}
            finally {A2ReplayContext.exitReplay();}
            assertThat(f5Reverse(event,"not-an-evidence-reference","BAD-EVIDENCE").getMessage()).isEqualTo("REFUND_REF_NOT_FOUND");
            assertThat(rejectionSnapshot(source)).isEqualTo(before);
            var reversed=f5Reverse(event,ref(source),"VALID-DEDICATED");assertThat(reversed.getCode()).isZero();
            assertThat(reversed.getData().get("status")).isEqualTo("reversed");
            var after=rejectionSnapshot(source);
            assertThat((BigDecimal)groupRow(source).get("recovered_usdt")).isEqualByComparingTo("6");
            assertThat((BigDecimal)groupRow(source).get("recovered_nex")).isEqualByComparingTo("400");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_wallet_ledger WHERE biz_no LIKE ? AND direction='OUT'",Long.class,group(source)+"%")).isEqualTo(2);
            assertThat(f5Reverse(event,ref(source),"VALID-DEDICATED").getCode()).isZero();
            assertThat(rejectionSnapshot(source)).isEqualTo(after);
            record("generic-rejection","dedicated-approval-evidence-and-idempotency-preserved",Map.of("before",before,"after",after,"response",reversed.getData()));
            Path evidence=Path.of(System.getenv().getOrDefault("DIRECT_REFERRAL_EVIDENCE_DIR","target/direct-referral-runtime"));Files.createDirectories(evidence);
            json.writerWithDefaultPrettyPrinter().writeValue(evidence.resolve("dedicated-f5-evidence.json").toFile(),groups);
        } finally {A2ReplayContext.exitReplay();org.springframework.security.core.context.SecurityContextHolder.clearContext();}
    }

    private Map<String,Object> rejectionSnapshot(String source) {
        String no=group(source);
        return Map.of("wallet",jdbc.queryForMap("SELECT * FROM nx_user_wallet WHERE user_id=?",a),"group",groupRow(source),
                "events",jdbc.queryForList("SELECT * FROM nx_commission_event WHERE remark=? ORDER BY id",no),
                "ledger",jdbc.queryForList("SELECT * FROM nx_wallet_ledger WHERE biz_no LIKE ? ORDER BY id",no+"%"),
                "releaseEntries",jdbc.queryForList("SELECT * FROM nx_earnings_release_entry WHERE source_ref IN (?,?) ORDER BY id",no+":USDT",no+":NEX"),
                "operations",jdbc.queryForList("SELECT * FROM nx_commission_operation ORDER BY id"));
    }

    @Test void actualSourcePolicyWalletRollbackAndRecovery() throws Exception {
        setUp();
        record("backend-policy","DB-clock",jdbc.queryForMap("SELECT @@session.time_zone sessionTimeZone,NOW() databaseNow,UTC_TIMESTAMP() utcNow"));
        order("BEFORE-FIRST",b,"100");
        jdbc.update("UPDATE nx_order SET paid_at=DATE_SUB(paid_at,INTERVAL 1 DAY) WHERE order_no=?",ref("BEFORE-FIRST"));
        publish(rule("10",0),rule("5",0));
        service.settle("direct_purchase",ref("BEFORE-FIRST"),b);
        assertThat(groupRow("BEFORE-FIRST").get("policy_version")).isEqualTo(0L);
        assertWallet(a,"0","0");
        record("backend-policy","TC12-before-first-no-backfill",Map.of("group",groupRow("BEFORE-FIRST"),"wallet",wallet(a)));
        order("B",b,"1000");order("C",c,"100");order("D",d,"100");
        service.settle("direct_purchase",ref("B"),b);service.settle("direct_purchase",ref("C"),c);service.settle("direct_purchase",ref("D"),d);
        assertWallet(a,"60","4000");assertWallet(b,"6","400");assertWallet(c,"6","400");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_commission_event WHERE commission_type='network' AND order_no LIKE ?",Long.class,marker+"%" )).isZero();
        record("backend-recipient","TC01-chain",Map.of("A",wallet(a),"B",wallet(b),"C",wallet(c),"groups",sourceRows()));

        receipt("B",b,"10","100",false);
        var beforeB=wallet(b);service.settle("direct_device_earning",ref("RECEIPT-B"),b);
        assertWallet(a,"60.33","4022");assertThat(wallet(b)).isEqualTo(beforeB);
        for(int retry=0;retry<100;retry++)assertThat(service.settle("direct_device_earning",ref("RECEIPT-B"),b)).isZero();
        assertThat(ledgerCount(group("RECEIPT-B"))).isEqualTo(2);
        receipt("TEST",b,"10","100",true);var beforeA=wallet(a);
        service.settle("direct_device_earning",ref("RECEIPT-TEST"),b);
        assertThat(wallet(a)).isEqualTo(beforeA);
        record("backend-recipient","TC04-real-two-assets-and-test-exclusion",Map.of("beneficiary",wallet(a),"sourceBefore",beforeB,"sourceAfter",wallet(b),"settlements",sourceRows()));
        receipt("C",c,"10","100",false);receipt("D",d,"10","100",false);
        beforeA=wallet(a);var beforeC=wallet(c);beforeB=wallet(b);
        service.settle("direct_device_earning",ref("RECEIPT-C"),c);service.settle("direct_device_earning",ref("RECEIPT-D"),d);
        assertThat(wallet(a)).isEqualTo(beforeA);assertDelta(beforeB,wallet(b),"0.33","22");assertDelta(beforeC,wallet(c),"0.33","22");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_direct_referral_settlement WHERE source_ref LIKE ? AND source_type='direct_device_earning' AND status='UNLOCKED'",Long.class,marker+"%")).isEqualTo(3);
        record("backend-recipient","TC02-device-chain-no-recursion",Map.of("A",wallet(a),"B",wallet(b),"C",wallet(c),"settlements",sourceRows()));
        sourceRejections();

        order("CONCURRENT",c,"100");var pool=Executors.newFixedThreadPool(4);var start=new CountDownLatch(1);
        try{var futures=new ArrayList<Future<Integer>>();for(int i=0;i<8;i++)futures.add(pool.submit(()->{start.await();return service.settle("direct_purchase",ref("CONCURRENT"),c);}));start.countDown();
            int issued=0;for(var future:futures)issued+=future.get(30,TimeUnit.SECONDS);assertThat(issued).isEqualTo(1);
        }finally{pool.shutdownNow();}
        assertThat(ledgerCount(group("CONCURRENT"))).isEqualTo(2);
        var duplicateWallet=wallet(b);jdbc.update("UPDATE nx_user SET sponsor_user_id=? WHERE id=?",a,c);
        for(int retry=0;retry<100;retry++)assertThat(service.settle("direct_purchase",ref("CONCURRENT"),c)).isZero();
        assertThat(wallet(b)).isEqualTo(duplicateWallet);assertThat(ledgerCount(group("CONCURRENT"))).isEqualTo(2);
        record("backend-funds","TC08-concurrent-replay-and-sponsor-change",Map.of("group",groupRow("CONCURRENT"),"wallet",wallet(b),"ledger",ledgerRows(group("CONCURRENT"))));

        order("FAIL-NEX",b,"100");beforeA=wallet(a);failNex.set(true);
        assertThatThrownBy(()->service.settle("direct_purchase",ref("FAIL-NEX"),b)).hasMessageContaining("injected second currency failure");
        assertThat(wallet(a)).isEqualTo(beforeA);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_direct_referral_settlement WHERE source_ref=?",Long.class,ref("FAIL-NEX"))).isZero();
        failNex.set(false);service.settle("direct_purchase",ref("FAIL-NEX"),b);assertThat(ledgerCount(group("FAIL-NEX"))).isEqualTo(2);
        record("backend-funds","TC15-second-currency-rollback",Map.of("walletBefore",beforeA,"walletAfterRetry",wallet(a),"ledger",ledgerRows(group("FAIL-NEX"))));
        riskAndRefundConcurrency();

        publish(rule("10",30),rule("5",30));order("COOL",b,"100");beforeA=wallet(a);service.settle("direct_purchase",ref("COOL"),b);
        assertThat(wallet(a)).isEqualTo(beforeA);String cooling=group("COOL");long event=((Number)groupRow("COOL").get("usdt_event_id")).longValue();
        service.changeStatus(event,"FROZEN",0L);assertThatThrownBy(()->service.release(cooling)).hasMessageContaining("STATE_CONFLICT");
        assertThat(wallet(a)).isEqualTo(beforeA);service.changeStatus(event,"COOLING",1L);
        jdbc.update("UPDATE nx_direct_referral_settlement SET release_at=DATE_SUB(NOW(),INTERVAL 1 DAY) WHERE settlement_no=?",cooling);
        jdbc.update("UPDATE nx_commission_event SET unlock_at=DATE_SUB(NOW(),INTERVAL 1 DAY) WHERE remark=?",cooling);
        service.release(cooling);assertThat(ledgerCount(cooling)).isEqualTo(2);
        record("backend-funds","TC16-cooling-freeze-release",Map.of("before",beforeA,"after",wallet(a),"group",groupRow("COOL")));

        beforeA=wallet(a);var beforeGroup=groupRow("COOL");long beforeLedger=ledgerCount(cooling);
        for(BigDecimal ratio:List.of(BigDecimal.ZERO,new BigDecimal("0.5"),new BigDecimal("1.1"))) {
            assertThatThrownBy(()->service.reverse(cooling,ratio)).hasMessage("DIRECT_REFERRAL_REFUND_RATIO_INVALID");
        }
        assertThat(wallet(a)).isEqualTo(beforeA);assertThat(groupRow("COOL")).isEqualTo(beforeGroup);
        assertThat(ledgerCount(cooling)).isEqualTo(beforeLedger);
        record("backend-funds","TC19-partial-refund-input-refused-without-money-change",Map.of("wallet",wallet(a),"group",groupRow("COOL"),"ledger",ledgerRows(cooling)));

        order("SUSPEND",b,"100");service.settle("direct_purchase",ref("SUSPEND"),b);
        order("FOREIGN-SUSPEND",b,"100");service.settle("direct_purchase",ref("FOREIGN-SUSPEND"),b);
        jdbc.update("UPDATE nx_direct_referral_settlement SET source_environment='SANDBOX',run_id='foreign-run' WHERE settlement_no=?",group("FOREIGN-SUSPEND"));
        assertThat(f5Suspend(true).getData().get("frozenOpenEvents")).isEqualTo(2);
        assertThat(groupRow("SUSPEND").get("status")).isEqualTo("FROZEN");
        assertThat(groupRow("FOREIGN-SUSPEND").get("status")).isEqualTo("COOLING");
        assertThat(groupRow("COOL").get("status")).isEqualTo("UNLOCKED");
        assertThat(wallet(a)).isEqualTo(beforeA);
        long foreignEvent=((Number)groupRow("FOREIGN-SUSPEND").get("usdt_event_id")).longValue();
        assertThatThrownBy(()->service.changeStatus(foreignEvent,"FROZEN",0L)).hasMessage("DIRECT_REFERRAL_ENVIRONMENT_MISMATCH");
        assertThat(f5Suspend(false).getCode()).isZero();
        assertThat(groupRow("SUSPEND").get("status")).isEqualTo("COOLING");
        record("backend-funds","TC21-suspension-group-and-environment-isolation",Map.of("open",groupRow("SUSPEND"),"foreign",groupRow("FOREIGN-SUSPEND"),"credited",groupRow("COOL"),"wallet",wallet(a)));

        jdbc.update("UPDATE nx_user_wallet SET usdt_available=1,nex_available=2 WHERE user_id=?",a);
        jdbc.update("UPDATE nx_order SET payment_status='REFUNDED',order_status='REFUNDED' WHERE order_no=?",ref("COOL"));
        var reverseResponse=f5Reverse(event,ref("COOL"),"PENDING");
        assertThat(reverseResponse.getCode()).isZero();
        assertThat(reverseResponse.getData().get("status")).isEqualTo("recovery_pending");
        assertThat(reverseResponse.getData().get("recoveryPendingUSDT")).isEqualTo(new BigDecimal("5.000000"));
        assertThat(reverseResponse.getData().get("recoveryPendingNEX")).isEqualTo(new BigDecimal("398.000000"));
        assertThat((List<?>)reverseResponse.getData().get("ledgerBizNos")).hasSize(2);
        assertThat(f5Reverse(event,ref("COOL"),"PENDING").getCode()).isZero();
        service.refund(ref("COOL"));service.refund(ref("COOL"));assertWallet(a,"0","0");
        var pending=groupRow("COOL");assertThat(pending.get("status")).isEqualTo("RECOVERY_PENDING");
        assertThat((BigDecimal)pending.get("recovery_pending_usdt")).isEqualByComparingTo("5");
        assertThat((BigDecimal)pending.get("recovery_pending_nex")).isEqualByComparingTo("398");
        jdbc.update("UPDATE nx_user_wallet SET usdt_available=5,nex_available=398 WHERE user_id=?",a);
        service.reverse(cooling,BigDecimal.ONE);assertWallet(a,"0","0");assertThat(groupRow("COOL").get("status")).isEqualTo("REVERSED");
        A2ReplayContext.enterReplay(ref("F5-REISSUE"));
        try{assertThatThrownBy(()->f5.reissue(ref("F5-REISSUE"),new ffdd.opsconsole.team.dto.F5CommissionReissueRequest(List.of("CM-"+event),"isolated group cannot be copied","acceptance")))
                .hasMessage("DIRECT_REFERRAL_REISSUE_REQUIRES_SOURCE_RECONCILIATION");}finally{A2ReplayContext.exitReplay();}
        assertWallet(a,"0","0");assertThat(ledgerCount(cooling)).isEqualTo(6);
        record("backend-funds","TC20-partial-actual-recovery-and-retry",Map.of("f5Response",reverseResponse.getData(),"pending",pending,"final",groupRow("COOL"),"wallet",wallet(a),"ledger",ledgerRows(cooling)));

        order("REFUND-BEFORE",b,"100");service.settle("direct_purchase",ref("REFUND-BEFORE"),b);
        jdbc.update("UPDATE nx_order SET payment_status='REFUNDED',order_status='REFUNDED' WHERE order_no=?",ref("REFUND-BEFORE"));
        service.refund(ref("REFUND-BEFORE"));assertThat(ledgerCount(group("REFUND-BEFORE"))).isZero();assertWallet(a,"0","0");
        assertThatThrownBy(()->service.release(group("REFUND-BEFORE"))).hasMessageContaining("STATE_CONFLICT");
        record("backend-funds","TC18-cancel-before-credit",Map.of("group",groupRow("REFUND-BEFORE"),"wallet",wallet(a)));
        order("LATE",b,"100");long oldPolicy=mapper.latestVersion();

        var firstPage=service.insights(a,"all",1,1,null);var next=service.insights(a,"all",2,1,(String)firstPage.get("snapshotAt"));
        assertThat(((List<?>)firstPage.get("events"))).hasSize(1);assertThat(((List<?>)next.get("events"))).hasSize(1);
        assertThat(next.get("totalRows")).isEqualTo(firstPage.get("totalRows"));
        assertThat(json.writeValueAsString(firstPage)).doesNotContain("sourceUserId");
        record("backend-recipient","TC27-read-only-pagination",Map.of("pageOne",firstPage,"pageTwo",next));

        long version=mapper.latestVersion();
        A2ReplayContext.enterReplay(marker+"-STALE");
        try{assertThatThrownBy(()->policies.publish(marker+"-STALE",new DirectReferralPolicyRequest(version-1,rule("10",0),rule("5",0),"stale version must not overwrite"))).hasMessage("DIRECT_REFERRAL_VERSION_CONFLICT");}
        finally{A2ReplayContext.exitReplay();}
        when(coverage.snapshot()).thenReturn(new TreasuryCoverageSnapshot(new BigDecimal("50"),new BigDecimal("100"),true));
        A2ReplayContext.enterReplay(marker+"-AMPLIFY");
        try{assertThatThrownBy(()->policies.publish(marker+"-AMPLIFY",new DirectReferralPolicyRequest(version,rule("20",30),rule("5",30),"coverage must reject amplification"))).hasMessage("COVERAGE_BELOW_REDLINE");}
        finally{A2ReplayContext.exitReplay();}
        clock.advance();publish(new DirectReferralPolicy.Rule(false,BigDecimal.ZERO,new BigDecimal("50"),0),new DirectReferralPolicy.Rule(false,BigDecimal.ZERO,new BigDecimal("50"),0));
        assertThat(mapper.latestVersion()).isEqualTo(version+1);
        assertThat(policies.current().get("configured")).isEqualTo(true);
        order("DISABLED",b,"100");service.settle("direct_purchase",ref("DISABLED"),b);assertThat(ledgerCount(group("DISABLED"))).isZero();
        service.settle("direct_purchase",ref("LATE"),b);assertThat(groupRow("LATE").get("policy_version")).isEqualTo(oldPolicy);
        assertThat(service.settle("direct_purchase",ref("FAIL-NEX"),b)).isZero();
        assertThat(groupRow("FAIL-NEX").get("policy_version")).isEqualTo(1L);
        record("backend-policy","TC23-CAS-B1-contraction-and-readback",Map.of("current",policies.current(),"persisted",jdbc.queryForList("SELECT policy_version,effective_at,purchase_json,device_earning_json FROM nx_direct_referral_policy")));

        jdbc.update("INSERT INTO nx_commission_event(user_id,commission_type,source_user_id,layer_no,order_no,amount_usdt,amount_nex,currency,status,unlock_at) VALUES(?,'network',?,1,?,3,0,'USDT','UNLOCKED',NOW())",a,b,ref("LEGACY"));
        long legacy=jdbc.queryForObject("SELECT id FROM nx_commission_event WHERE order_no=?",Long.class,ref("LEGACY"));treasury.releaseCommissionFunds(legacy);
        assertWallet(a,"3","0");treasury.releaseCommissionFunds(legacy);assertWallet(a,"3","0");
        var oldNetwork=mock(UnilevelCommissionService.class);
        var legacyConsumer=new VRankPassiveEvaluationConsumer(json,mock(VRankPromotionEngine.class),oldNetwork);
        var message=new ffdd.opsconsole.shared.outbox.EventOutboxMessage();message.setEventType("checkout.completed");message.setEventId(marker);message.setPayload("{\"user_id\":"+b+",\"order_no\":\""+ref("DISABLED")+"\",\"amount_usdt\":100}");
        legacyConsumer.onPassiveEvalTrigger(message);
        verifyNoInteractions(oldNetwork);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_commission_event WHERE commission_type='network' AND order_no=?",Long.class,ref("DISABLED"))).isZero();
        record("backend-legacy","TC29-historical-network-wallet-release",Map.of("wallet",wallet(a),"ledger",jdbc.queryForList("SELECT biz_no,asset,direction,amount,balance_after FROM nx_wallet_ledger WHERE user_id=?",a)));
        subsecondPolicyBoundary();
        concurrentPolicyPublication();
        Path evidence=Path.of(System.getenv().getOrDefault("DIRECT_REFERRAL_EVIDENCE_DIR","target/direct-referral-runtime"));Files.createDirectories(evidence);
        json.writerWithDefaultPrettyPrinter().writeValue(evidence.resolve("scenario-evidence.json").toFile(),Map.of("database",jdbc.queryForObject("SELECT DATABASE()",String.class),"port",jdbc.queryForObject("SELECT @@port",Integer.class),"groups",groups));
    }

    private void sourceRejections() {
        order("FORGED",b,"100");
        assertThatThrownBy(()->service.settle("direct_purchase",ref("FORGED"),c)).hasMessage("DIRECT_REFERRAL_SOURCE_USER_MISMATCH");
        jdbc.update("UPDATE nx_user SET sandbox=1 WHERE id=?",b);
        assertThatThrownBy(()->service.settle("direct_purchase",ref("FORGED"),b)).hasMessage("DIRECT_REFERRAL_SOURCE_ENVIRONMENT_MISMATCH");
        jdbc.update("UPDATE nx_user SET sandbox=0 WHERE id=?",b);
        var before=wallet(a);
        order("NO-PRICE",b,"100");
        jdbc.update("UPDATE nx_price_index SET status='INACTIVE' WHERE status='ACTIVE'");
        assertThatThrownBy(()->service.settle("direct_purchase",ref("NO-PRICE"),b)).hasMessage("DIRECT_REFERRAL_PRICE_UNAVAILABLE");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_direct_referral_settlement WHERE source_ref=?",Long.class,ref("NO-PRICE"))).isZero();
        assertThat(wallet(a)).isEqualTo(before);
        jdbc.update("UPDATE nx_price_index SET status='ACTIVE' WHERE metric_code='NEX_USDT' AND sampled_at='2099-01-01'");
        service.settle("direct_purchase",ref("NO-PRICE"),b);assertDelta(before,wallet(a),"6","400");
        order("ROOT",a,"100");service.settle("direct_purchase",ref("ROOT"),a);
        jdbc.update("UPDATE nx_user SET sponsor_user_id=id WHERE id=?",d);order("SELF",d,"100");service.settle("direct_purchase",ref("SELF"),d);
        jdbc.update("UPDATE nx_user SET sponsor_user_id=? WHERE id=?",c,d);
        assertThat(groupRow("ROOT").get("reason")).isEqualTo("NO_ELIGIBLE_DIRECT_SPONSOR");
        assertThat(groupRow("SELF").get("reason")).isEqualTo("NO_ELIGIBLE_DIRECT_SPONSOR");
        before=wallet(a);jdbc.update("UPDATE nx_user SET status='SUSPENDED' WHERE id=?",a);
        order("INACTIVE-SPONSOR",b,"100");service.settle("direct_purchase",ref("INACTIVE-SPONSOR"),b);
        assertThat(groupRow("INACTIVE-SPONSOR").get("reason")).isEqualTo("SPONSOR_UNAVAILABLE");
        jdbc.update("UPDATE nx_user SET status='ACTIVE' WHERE id=?",a);
        jdbc.update("UPDATE nx_user_wallet SET sandbox=1 WHERE user_id=?",a);
        order("FOREIGN-WALLET",b,"100");service.settle("direct_purchase",ref("FOREIGN-WALLET"),b);
        assertThat(groupRow("FOREIGN-WALLET").get("reason")).isEqualTo("SPONSOR_UNAVAILABLE");
        jdbc.update("UPDATE nx_user_wallet SET sandbox=0 WHERE user_id=?",a);assertThat(wallet(a)).isEqualTo(before);
        order("DUST",b,"0.000001");service.settle("direct_purchase",ref("DUST"),b);
        assertThat(groupRow("DUST").get("reason")).isEqualTo("BELOW_DUAL_ASSET_PRECISION");
        assertThat((BigDecimal)groupRow("DUST").get("amount_usdt")).isZero();
        assertThat((BigDecimal)groupRow("DUST").get("amount_nex")).isZero();
        assertThat((BigDecimal)groupRow("DUST").get("basis_usdt")).isPositive();
        order("ZERO",b,"0");service.settle("direct_purchase",ref("ZERO"),b);
        assertThat(groupRow("ZERO").get("reason")).isEqualTo("ZERO_PAID_SOURCE");
        receipt("WORKER",b,"1","1",false);
        jdbc.update("INSERT INTO nx_audit_log(service_name,action,resource_type,resource_id,actor_type,detail_json) VALUES('acceptance','TASK','COMPUTE_TASK',?,'TEST_COMPUTE_WORKER','{}')","TASK-"+ref("WORKER"));
        before=wallet(a);service.settle("direct_device_earning",ref("RECEIPT-WORKER"),b);assertThat(wallet(a)).isEqualTo(before);
        assertThat(groupRow("RECEIPT-WORKER").get("reason")).isEqualTo("SOURCE_NOT_ELIGIBLE");
        record("backend-recipient","TC10-TC13-TC16-TC31-source-identity-environment-price-and-exclusions",Map.of("settlements",sourceRows(),"wallet",wallet(a)));
    }

    private void riskAndRefundConcurrency() throws Exception {
        order("RISK",b,"100");var before=wallet(a);
        jdbc.update("INSERT INTO nx_admin_risk_multi_account_cluster(cluster_id,dedupe_key,layer_key,layer_label,account_count,strength,span_text,status,note_text,nodes_json) VALUES(?,?,'test','test',1,0,'test','CLOSED','test',?)",marker,marker,json.writeValueAsString(List.of(Map.of("userNo","U"+a))));
        changeRisk.set(true);
        assertThatThrownBy(()->service.settle("direct_purchase",ref("RISK"),b)).hasMessage("DIRECT_REFERRAL_SPONSOR_FROZEN");
        changeRisk.set(false);assertThat(wallet(a)).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT account_count FROM nx_admin_risk_multi_account_cluster WHERE cluster_id=?",Integer.class,marker)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_direct_referral_settlement WHERE source_ref=?",Long.class,ref("RISK"))).isZero();
        service.settle("direct_purchase",ref("RISK"),b);
        record("backend-funds","TC17-risk-bucket-change-rollback",Map.of("walletBefore",before,"walletAfterRetry",wallet(a),"entries",jdbc.queryForList("SELECT asset,bucket,status,amount FROM nx_earnings_release_entry WHERE source_ref LIKE ?",group("RISK")+"%")));

        order("FULL-REFUND",b,"100");before=wallet(a);service.settle("direct_purchase",ref("FULL-REFUND"),b);
        jdbc.update("UPDATE nx_order SET payment_status='REFUNDED',order_status='REFUNDED' WHERE order_no=?",ref("FULL-REFUND"));service.refund(ref("FULL-REFUND"));service.refund(ref("FULL-REFUND"));
        assertThat(wallet(a)).isEqualTo(before);assertThat(ledgerCount(group("FULL-REFUND"))).isEqualTo(4);
        record("backend-funds","TC18-full-wallet-refund",Map.of("walletBefore",before,"walletAfter",wallet(a),"group",groupRow("FULL-REFUND"),"ledger",ledgerRows(group("FULL-REFUND"))));

        publish(rule("10",30),rule("5",30));order("RACE",b,"100");service.settle("direct_purchase",ref("RACE"),b);String no=group("RACE");
        due(no);before=wallet(a);
        String raceNo=no;
        List<String> race=concurrent(()->service.release(raceNo),()->new org.springframework.transaction.support.TransactionTemplate(new DataSourceTransactionManager(dataSource)).execute(status->{jdbc.update("UPDATE nx_order SET payment_status='REFUNDED',order_status='REFUNDED' WHERE order_no=?",ref("RACE"));service.refund(ref("RACE"));return true;}));
        service.refund(ref("RACE"));assertThat(wallet(a)).isEqualTo(before);assertThat(groupRow("RACE").get("status")).isEqualTo("REVERSED");
        record("backend-funds","TC19-refund-release-race",Map.of("outcomes",race,"walletBefore",before,"walletAfter",wallet(a),"group",groupRow("RACE"),"ledger",ledgerRows(no)));

        order("F5-RACE",b,"100");service.settle("direct_purchase",ref("F5-RACE"),b);no=group("F5-RACE");due(no);
        long id=((Number)groupRow("F5-RACE").get("usdt_event_id")).longValue();String groupNo=no;
        race=concurrent(()->service.release(groupNo),()->{service.changeStatus(id,"UNLOCKED",0L);return true;});
        assertThat(ledgerCount(no)).isEqualTo(2);assertThat(groupRow("F5-RACE").get("status")).isEqualTo("UNLOCKED");
        record("backend-funds","TC17-F5-and-scheduled-release-race",Map.of("outcomes",race,"group",groupRow("F5-RACE"),"ledger",ledgerRows(no)));

        order("F5-REVERSE-RACE",b,"100");service.settle("direct_purchase",ref("F5-REVERSE-RACE"),b);
        String reverseNo=group("F5-REVERSE-RACE");due(reverseNo);before=wallet(a);
        long reverseId=((Number)groupRow("F5-REVERSE-RACE").get("nex_event_id")).longValue();
        assertThat(f5Reverse(reverseId,"unconfirmed-reference","INVALID").getCode()).isEqualTo(422);
        assertThat(groupRow("F5-REVERSE-RACE").get("status")).isEqualTo("COOLING");
        race=concurrent(()->service.release(reverseNo),()->{
            var response=f5Reverse(reverseId,ref("F5-REVERSE-RACE"),"RACE");
            assertThat(response.getCode()).isZero();assertThat(response.getData().get("status")).isEqualTo("reversed");return response.getData();});
        assertThat(wallet(a)).isEqualTo(before);assertThat(groupRow("F5-REVERSE-RACE").get("status")).isEqualTo("REVERSED");
        assertThat(jdbc.queryForList("SELECT DISTINCT status FROM nx_commission_event WHERE remark=?",String.class,reverseNo)).containsExactly("REVERSED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_commission_operation WHERE source_commission_id=? AND operation_type='REVERSE'",Long.class,reverseId)).isEqualTo(1);
        record("backend-funds","TC21-real-F5-reverse-release-race",Map.of("outcomes",race,"walletBefore",before,"walletAfter",wallet(a),"group",groupRow("F5-REVERSE-RACE"),"ledger",ledgerRows(reverseNo)));
    }

    private void concurrentPolicyPublication() throws Exception {
        long expected=mapper.latestVersion();clock.advance();
        List<String> outcomes=concurrent(()->publishExpected(expected,"A"),()->publishExpected(expected,"B"));
        assertThat(outcomes.stream().filter(s->s.startsWith("success:")).count()).isEqualTo(1);
        assertThat(outcomes).contains("DIRECT_REFERRAL_VERSION_CONFLICT");
        assertThat(mapper.latestVersion()).isEqualTo(expected+1);
        assertThat(policies.current().get("purchase")).isEqualTo(rule("7",0));
        assertThat(policies.current().get("deviceEarning")).isEqualTo(rule("3",0));
        record("backend-policy","TC23-two-publishers-one-atomic-version",Map.of("outcomes",outcomes,"current",policies.current()));
    }
    private Object publishExpected(long expected,String suffix){
        A2ReplayContext.enterReplay(ref("CONCURRENT-POLICY-"+suffix));
        try{return policies.publish(ref("CONCURRENT-POLICY-"+suffix),new DirectReferralPolicyRequest(expected,rule("7",0),rule("3",0),"concurrent full policy acceptance"));}
        finally{A2ReplayContext.exitReplay();}
    }
    private void subsecondPolicyBoundary(){
        when(coverage.snapshot()).thenReturn(new TreasuryCoverageSnapshot(new BigDecimal("200"),new BigDecimal("100"),true));
        clock.now=clock.instant().plusSeconds(1).truncatedTo(ChronoUnit.SECONDS).plusNanos(400123000);
        order("MICRO-BEFORE",b,"100");
        long oldVersion=mapper.latestVersion();
        clock.now=clock.instant().plusNanos(100000);
        publish(rule("10",0),rule("5",0));long newVersion=mapper.latestVersion();
        clock.now=clock.instant().plusNanos(100000);
        order("MICRO-AFTER",b,"100");receipt("MICRO-AFTER",b,"10","100",false);
        var before=wallet(a);
        service.settle("direct_purchase",ref("MICRO-BEFORE"),b);
        assertThat(groupRow("MICRO-BEFORE").get("policy_version")).isEqualTo(oldVersion);
        assertThat(wallet(a)).isEqualTo(before);
        service.settle("direct_purchase",ref("MICRO-AFTER"),b);
        service.settle("direct_device_earning",ref("RECEIPT-MICRO-AFTER"),b);
        assertThat(groupRow("MICRO-AFTER").get("policy_version")).isEqualTo(newVersion);
        assertThat(groupRow("RECEIPT-MICRO-AFTER").get("policy_version")).isEqualTo(newVersion);
        assertDelta(before,wallet(a),"6.33","422");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_compute_task t JOIN nx_compute_receipt r ON t.task_no=r.task_no AND t.completed_at=r.completed_at WHERE r.receipt_no=?",Long.class,ref("RECEIPT-MICRO-AFTER"))).isEqualTo(1);
        assertThat(mapper.purchase(ref("MICRO-AFTER")).sourceOccurredAt().getNano()).isEqualTo(400323000);
        jdbc.update("INSERT INTO nx_binary_paid_order_volume(order_no,owner_user_id,order_user_id,root_member_user_id,leg,amount_usdt,paid_at) SELECT order_no,?,?,?,'A',amount_usdt,paid_at FROM nx_order WHERE order_no=?",a,b,b,ref("MICRO-AFTER"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_binary_paid_order_volume v JOIN nx_order o ON v.order_no=o.order_no AND v.paid_at=o.paid_at WHERE v.order_no=?",Long.class,ref("MICRO-AFTER"))).isEqualTo(1);
        record("backend-policy","TC12-same-second-approval-and-source-ordering",Map.of("before",groupRow("MICRO-BEFORE"),"afterPurchase",groupRow("MICRO-AFTER"),"afterEarning",groupRow("RECEIPT-MICRO-AFTER"),"wallet",wallet(a)));
    }
    private ffdd.opsconsole.shared.api.ApiResult<Map<String,Object>> f5Reverse(long event,String evidence,String key){
        A2ReplayContext.enterReplay(ref("F5-"+key));
        try{return f5.reverse("CM-"+event,ref("F5-"+key),new ffdd.opsconsole.team.dto.F5CommissionReverseRequest(evidence,"isolated F5 reverse acceptance","acceptance"));}
        finally{A2ReplayContext.exitReplay();}
    }
    private ffdd.opsconsole.shared.api.ApiResult<Map<String,Object>> f5Suspend(boolean suspended){
        A2ReplayContext.enterReplay(ref("F5-SUSPEND-"+suspended));
        try{return f5.suspend(a,ref("F5-SUSPEND-"+suspended),new ffdd.opsconsole.team.dto.F5CommissionSuspensionRequest(List.of("direct_purchase"),suspended,"isolated F5 suspension acceptance","acceptance"));}
        finally{A2ReplayContext.exitReplay();}
    }
    private void due(String no){jdbc.update("UPDATE nx_direct_referral_settlement SET release_at=DATE_SUB(NOW(),INTERVAL 1 DAY) WHERE settlement_no=?",no);jdbc.update("UPDATE nx_commission_event SET unlock_at=DATE_SUB(NOW(),INTERVAL 1 DAY) WHERE remark=?",no);}
    private List<String> concurrent(Callable<?> first,Callable<?> second) throws Exception {
        var start=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
        try{var futures=new ArrayList<Future<String>>();for(var operation:List.of(first,second))futures.add(pool.submit(()->{start.await();try{return "success:"+operation.call();}catch(ffdd.opsconsole.shared.exception.BizException conflict){if(!Set.of("DIRECT_REFERRAL_SOURCE_REFUNDED","DIRECT_REFERRAL_RELEASE_STATE_CONFLICT","F5_COMMISSION_VERSION_CONFLICT","DIRECT_REFERRAL_VERSION_CONFLICT").contains(conflict.getMessage()))throw conflict;return conflict.getMessage();}}));start.countDown();var result=new ArrayList<String>();for(var future:futures)result.add(future.get(30,TimeUnit.SECONDS));return result;}finally{pool.shutdownNow();}
    }
    private void assertDelta(Map<String,Object> before,Map<String,Object> after,String usdt,String nex){assertThat(((BigDecimal)after.get("usdt_available")).subtract((BigDecimal)before.get("usdt_available"))).isEqualByComparingTo(usdt);assertThat(((BigDecimal)after.get("nex_available")).subtract((BigDecimal)before.get("nex_available"))).isEqualByComparingTo(nex);}

    private void setUp(){
        String url=System.getenv().getOrDefault("DIRECT_REFERRAL_MYSQL_URL","jdbc:mysql://127.0.0.1:33335/direct_referral_acceptance_20261005?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai");
        boolean ownedUuid=url.matches("jdbc:mysql://127\\.0\\.0\\.1:13306/direct_referral_it_[a-f0-9]{32}\\?.+");
        if(!ownedUuid&&!url.startsWith("jdbc:mysql://127.0.0.1:33335/direct_referral_acceptance_20261005?"))throw new IllegalStateException("isolated database guard");
        dataSource=new DriverManagerDataSource(url,"root","");jdbc=new JdbcTemplate(dataSource);
        String database=url.substring(url.indexOf('/',"jdbc:mysql://".length())+1,url.indexOf('?'));
        assertThat(jdbc.queryForObject("SELECT DATABASE()",String.class)).isEqualTo(database);
        if(ownedUuid)assertThat(jdbc.queryForObject("SELECT @@port",Integer.class)).isEqualTo(13306);
        jdbc.update("DELETE FROM nx_direct_referral_policy");
        var config=new MybatisConfiguration(new Environment("direct-referral-runtime",new SpringManagedTransactionFactory(),dataSource));
        var global=new GlobalConfig();global.setDbConfig(new GlobalConfig.DbConfig());global.setMetaObjectHandler(new MybatisMetaObjectHandler(clock));GlobalConfigUtils.setGlobalConfig(config,global);config.setMapUnderscoreToCamelCase(true);
        for(Class<?> type:List.of(DirectReferralMapper.class,EarningsReleaseMapper.class,TreasuryLedgerMapper.class,NexMarketMapper.class,F5CommissionMapper.class,ffdd.opsconsole.risk.mapper.RiskOpsMapper.class,ffdd.opsconsole.shared.idempotency.mapper.AdminIdempotencyRecordMapper.class))config.addMapper(type);
        session=new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(config));mapper=session.getMapper(DirectReferralMapper.class);
        session.getMapper(ffdd.opsconsole.risk.mapper.RiskOpsMapper.class).createMultiAccountClusterTable();
        var platform=mock(PlatformConfigFacade.class);
        doAnswer(call->{return jdbc.update("INSERT IGNORE INTO nx_config_item(config_key,config_value) VALUES(?,?)",(String)call.getArgument(0),(String)call.getArgument(1)) == 1;}).when(platform).insertAdminValueIfMissing(anyString(),anyString(),anyString(),anyString(),anyString());
        when(platform.activeValueForUpdate(anyString())).thenAnswer(call->Optional.of(jdbc.queryForObject("SELECT config_value FROM nx_config_item WHERE config_key=? FOR UPDATE",String.class,(Object)call.getArgument(0))));
        doAnswer(call->{jdbc.update("UPDATE nx_config_item SET config_value=? WHERE config_key=?",(String)call.getArgument(1),(String)call.getArgument(0));return null;}).when(platform).upsertAdminValue(anyString(),anyString(),anyString(),anyString(),anyString());
        coverage=mock(TreasuryCoverageFacade.class);when(coverage.snapshot()).thenReturn(new TreasuryCoverageSnapshot(new BigDecimal("200"),new BigDecimal("100"),true));
        var audit=mock(AuditLogService.class);var outbox=mock(EventOutboxService.class);var env=new MockEnvironment();env.setActiveProfiles("dev");
        policies=proxy(new DirectReferralPolicyService(mapper,platform,session.getMapper(NexMarketMapper.class),coverage,audit,json,clock,env));
        var risk=mock(RiskReleaseParamsService.class);when(risk.pendingFrom()).thenReturn(3);when(risk.freezeFrom()).thenReturn(5);
        var profile=mock(FundsSandboxProfileGuard.class);when(profile.isStrictProductionRuntime()).thenReturn(true);
        var releaseMapper=session.getMapper(EarningsReleaseMapper.class);
        var earnings=spy(new EarningsReleaseService(releaseMapper,risk,mock(AdminIdempotencyService.class),audit,profile));
        doAnswer(call->{
            if("NEX".equals(call.getArgument(3))&&failNex.get()){
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_earnings_release_entry WHERE source_ref=?",Long.class,call.<String>getArgument(2).replace(":NEX",":USDT"))).isEqualTo(1);
                throw new IllegalStateException("injected second currency failure");
            }
            if("NEX".equals(call.getArgument(3))&&changeRisk.get())jdbc.update("UPDATE nx_admin_risk_multi_account_cluster SET account_count=4 WHERE cluster_id=?",marker);
            return call.callRealMethod();})
                .when(earnings).creditReward(anyLong(),anyString(),anyString(),anyString(),any(BigDecimal.class),anyString(),anyString());
        treasury=proxy(new MybatisTreasuryLedgerRepository(session.getMapper(TreasuryLedgerMapper.class),outbox));
        var ledger=new TreasuryLedgerPostingFacadeAdapter(treasury, null);
        service=proxy(new DirectReferralService(mapper,policies,earnings,releaseMapper,risk,ledger,outbox,json,clock));
        var idempotencyMapper=session.getMapper(ffdd.opsconsole.shared.idempotency.mapper.AdminIdempotencyRecordMapper.class);
        var expiry=proxy(new ffdd.opsconsole.shared.idempotency.AdminIdempotencyExpiryTransitionExecutor(idempotencyMapper));
        var executor=proxy(new ffdd.opsconsole.shared.idempotency.AdminIdempotencyTransactionExecutor(idempotencyMapper,json,expiry));
        var idempotency=new AdminIdempotencyService(executor,clock);
        var provider=mock(org.springframework.beans.factory.ObjectProvider.class);when(provider.getIfAvailable()).thenReturn(service);
        f5=new F5CommissionService(session.getMapper(F5CommissionMapper.class),platform,coverage,ledger,audit,outbox,idempotency,provider);
        var commissions=mock(ffdd.opsconsole.team.domain.TeamCommissionRepository.class);
        when(commissions.commissionEvents(anyInt())).thenAnswer(call->jdbc.queryForList("SELECT CONCAT('CM-',id) id,commission_type kind,CONCAT('U',user_id) user,CASE WHEN currency='NEX' THEN amount_nex ELSE amount_usdt END amount,currency,status rawStatus,version FROM nx_commission_event WHERE remark LIKE ? ORDER BY id LIMIT 100","DR-%"));
        when(commissions.recordCommissionOperation(anyString(),anyString(),anyString(),anyLong(),anyString(),anyString())).thenReturn(true);
        var permissions=mock(ffdd.opsconsole.shared.security.AdminPermissionCache.class);
        when(permissions.getPermissionCodes(anyLong())).thenReturn(Set.of("network_f5_commission_dispose","network_f5_commission_reject"));
        ops=proxy(new OpsTeamService(platform,coverage,ledger,audit,ffdd.opsconsole.shared.seed.OpsReadTimeSeedPolicy.enabledForDirectConstruction(),mock(ffdd.opsconsole.team.domain.TeamFulfillmentQueueRepository.class),commissions,permissions,mock(ffdd.opsconsole.platform.mapper.AuditObjectLockMapper.class),mock(VRankPromotionEngine.class),mock(VRankRewardDispatcher.class),outbox,mock(LeadershipPoolService.class),f5,idempotency,null,provider));
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(1L,null,List.of()));
        long base=900000000L+System.currentTimeMillis()%100000000;a=base;b=base+1;c=base+2;d=base+3;
        user(a,null);user(b,a);user(c,b);user(d,c);
        jdbc.update("INSERT INTO nx_price_index(metric_code,metric_label,unit_label,price_usdt,status,sampled_at) VALUES('NEX_USDT','acceptance','USDT',0.01,'ACTIVE','2099-01-01')");
    }
    @SuppressWarnings("unchecked") private <T>T proxy(T target){var factory=new ProxyFactory(target);factory.setProxyTargetClass(true);factory.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(dataSource),new AnnotationTransactionAttributeSource()));return(T)factory.getProxy();}
    private void user(long id,Long sponsor){jdbc.update("INSERT INTO nx_user(id,country_code,phone,client_ip,password_hash,nickname,referral_code,sponsor_user_id,sandbox) VALUES(?,'00',?,'127.0.0.1','fixture','Member',?,?,0)",id,String.valueOf(id),String.valueOf(id),sponsor);jdbc.update("INSERT INTO nx_user_wallet(user_id) VALUES(?)",id);}
    private DirectReferralPolicy.Rule rule(String rate,int days){return new DirectReferralPolicy.Rule(true,new BigDecimal(rate),new BigDecimal("60"),days);}
    private void publish(DirectReferralPolicy.Rule purchase,DirectReferralPolicy.Rule earning){String operation=marker+"-POLICY-"+mapper.latestVersion();A2ReplayContext.enterReplay(operation);try{policies.publish(operation,new DirectReferralPolicyRequest(mapper.latestVersion(),purchase,earning,"isolated database acceptance policy"));}finally{A2ReplayContext.exitReplay();}}
    private String ref(String name){return marker+"-"+name;}
    private void order(String name,long user,String amount){jdbc.update("INSERT INTO nx_order(user_id,order_no,product_id,subtotal_usdt,amount_usdt,payment_status,order_status,paid_at) VALUES(?,?,1,2000,?,'PAID','PAID',?)",user,ref(name),new BigDecimal(amount),LocalDateTime.ofInstant(clock.instant(),ZoneId.of("Asia/Shanghai")));}
    private void receipt(String name,long user,String usdt,String nex,boolean test){
        String receipt=ref("RECEIPT-"+name),task=(test?"DEV-TASK-":"TASK-")+ref(name);long device=user+500;
        jdbc.update("INSERT IGNORE INTO nx_user_device(id,user_id,product_id,name,device_type,instance_no,status,source_environment,run_id) VALUES(?,?,1,'acceptance','PHONE',?,'ACTIVE','PRODUCTION','')",device,user,ref("DEVICE-"+name));
        jdbc.update("INSERT INTO nx_compute_receipt(user_id,user_device_id,task_no,receipt_no,task_type,client_name,reward_usdt,reward_nex,earning_status,source_environment,proof_hash,completed_at) VALUES(?,?,?,?,'TEST','acceptance',?,?,'CREDITED','PRODUCTION','proof',?)",user,device,task,receipt,new BigDecimal(usdt),new BigDecimal(nex),LocalDateTime.ofInstant(clock.instant(),ZoneId.of("Asia/Shanghai")));
        jdbc.update("INSERT INTO nx_compute_task(task_no,user_id,user_device_id,task_type,client_name,status,completed_at) VALUES(?,?,?,'TEST','acceptance','COMPLETED',?)",task,user,device,LocalDateTime.ofInstant(clock.instant(),ZoneId.of("Asia/Shanghai")));
        for(String asset:List.of("USDT","NEX"))jdbc.update("INSERT INTO nx_earning_event(event_no,user_id,user_device_id,receipt_no,asset,amount,status,wallet_posted_at) VALUES(?,?,?,?,?,?,'POSTED',NOW())",receipt+asset,user,device,receipt,asset,new BigDecimal("USDT".equals(asset)?usdt:nex));
        jdbc.update("UPDATE nx_user_wallet SET usdt_available=usdt_available+?,nex_available=nex_available+? WHERE user_id=?",new BigDecimal(usdt),new BigDecimal(nex),user);
    }
    private Map<String,Object>wallet(long user){return jdbc.queryForMap("SELECT usdt_available,nex_available FROM nx_user_wallet WHERE user_id=?",user);}
    private void assertWallet(long user,String usdt,String nex){var row=wallet(user);assertThat((BigDecimal)row.get("usdt_available")).isEqualByComparingTo(usdt);assertThat((BigDecimal)row.get("nex_available")).isEqualByComparingTo(nex);}
    private String group(String source){return jdbc.queryForObject("SELECT settlement_no FROM nx_direct_referral_settlement WHERE source_ref=?",String.class,ref(source));}
    private Map<String,Object>groupRow(String source){return jdbc.queryForMap("SELECT * FROM nx_direct_referral_settlement WHERE source_ref=?",ref(source));}
    private long ledgerCount(String no){return jdbc.queryForObject("SELECT COUNT(*) FROM nx_wallet_ledger WHERE biz_no LIKE ?",Long.class,no+"%");}
    private List<Map<String,Object>>ledgerRows(String no){return jdbc.queryForList("SELECT biz_no,asset,direction,amount,balance_after,status FROM nx_wallet_ledger WHERE biz_no LIKE ? ORDER BY id",no+"%");}
    private List<Map<String,Object>>sourceRows(){return jdbc.queryForList("SELECT source_type,source_ref,source_user_id,beneficiary_user_id,basis_usdt,amount_usdt,amount_nex,status,reason FROM nx_direct_referral_settlement WHERE source_ref LIKE ? ORDER BY id",marker+"%");}
    private void record(String group,String id,Map<String,Object> observations){groups.computeIfAbsent(group,k->new ArrayList<>()).add(Map.of("id",id,"passed",true,"observations",observations));}
    private static class MutableClock extends Clock{private Instant now;MutableClock(Instant now){this.now=now;}public ZoneId getZone(){return ZoneId.of("Asia/Shanghai");}public Clock withZone(ZoneId zone){return this;}public Instant instant(){return now;}void advance(){now=now.plusSeconds(1);}}
}
