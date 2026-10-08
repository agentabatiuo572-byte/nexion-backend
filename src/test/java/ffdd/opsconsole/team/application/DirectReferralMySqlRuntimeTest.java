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
    private PlatformConfigFacade platform;
    private final ObjectMapper json=new ObjectMapper().findAndRegisterModules();
    private final MutableClock clock=new MutableClock(Instant.now().plusSeconds(5).truncatedTo(ChronoUnit.SECONDS));
    private final AtomicBoolean failNex=new AtomicBoolean();
    private final AtomicBoolean changeRisk=new AtomicBoolean();
    private final AtomicBoolean failRecovery=new AtomicBoolean();
    private final String marker="DRTEST-"+UUID.randomUUID().toString().substring(0,8);
    private final Map<String,List<Map<String,Object>>> groups=new LinkedHashMap<>();
    private long a,b,c,d;

    @Test void sevenLayerRuntimeAcceptance() throws Exception {
        setUp();
        List<String> keys=List.of(DirectReferralPolicyService.CUTOVER_KEY,DirectReferralPolicyService.SEVEN_REVISION_KEY,"commission/cooling-days","team.ui.F.unilevel.depthGate","team.ui.F.unilevel.depthGateRank","team.ui.F.unilevel.mergeExitMaxPct","team.ui.F.influence.clampMin","team.ui.F.influence.clampMax","team.ui.F.promo.weekMultiplier");
        Map<String,String> previous=new LinkedHashMap<>();for(String key:keys)previous.put(key,platform.activeValue(key).orElse(null));
        var originalRates=jdbc.queryForList("SELECT * FROM nx_commission_rule WHERE commission_type='UNILEVEL'");
        try {
            configured("commission/cooling-days","30");configured("team.ui.F.unilevel.depthGate","L4");configured("team.ui.F.unilevel.depthGateRank","V2");configured("team.ui.F.unilevel.mergeExitMaxPct","25");
            configured("team.ui.F.influence.clampMin","1");configured("team.ui.F.influence.clampMax","1");configured("team.ui.F.promo.weekMultiplier","1");
            var percentages=List.of("10","5","3","2","1","0.5","0.5");
            for(int layer=1;layer<=7;layer++)jdbc.update("INSERT INTO nx_commission_rule(commission_type,layer_no,usdt_rate,nex_per_usd,status) VALUES('UNILEVEL',?,?,2,1) ON DUPLICATE KEY UPDATE usdt_rate=VALUES(usdt_rate),nex_per_usd=2,status=1,is_deleted=0",layer,new BigDecimal(percentages.get(layer-1)).movePointLeft(2));
            long[] ancestors={c,b,a,a-1,a-2,a-3,a-4,a-5};
            for(int n=3;n<ancestors.length;n++)user(ancestors[n],n+1<ancestors.length?ancestors[n+1]:null);
            for(int n=0;n<ancestors.length;n++){
                long child=n==0?d:ancestors[n-1];jdbc.update("UPDATE nx_user SET sponsor_user_id=? WHERE id=?",ancestors[n],child);
                jdbc.update("INSERT INTO nx_team_member(user_id,member_user_id,member_no,nickname,level,v_rank) VALUES(?,?,?,'acceptance',1,'V12')",ancestors[n],child,ref("EDGE-"+n));
                jdbc.update("INSERT INTO nx_team_member(user_id,member_user_id,member_no,nickname,level,v_rank) VALUES(?,?,?,'acceptance',0,'V12')",ancestors[n],ancestors[n],ref("SELF-"+n));
            }
            var oldEngine=new UnilevelCommissionService(session.getMapper(ffdd.opsconsole.team.mapper.TeamCommissionMapper.class),mock(ffdd.opsconsole.team.domain.TeamCommissionRepository.class),mock(ffdd.opsconsole.treasury.facade.TreasuryLedgerPostingFacade.class),platform,mock(EventOutboxService.class));
            org.springframework.test.util.ReflectionTestUtils.setField(service,"unilevel",oldEngine);
            configured(DirectReferralPolicyService.CUTOVER_KEY,clock.instant().plusSeconds(1).toString());
            publish(rule("20",30),rule("5",0));orderBasis("OLD-DIRECT",d,"1000");service.settle("direct_purchase",ref("OLD-DIRECT"),d);
            orderBasis("OLD-NETWORK",d,"1000");jdbc.update("INSERT INTO nx_commission_event(user_id,commission_type,source_user_id,layer_no,order_no,amount_usdt,amount_nex,currency,status,unlock_at) VALUES(?,'network',?,1,?,100,0,'USDT','COOLING',DATE_ADD(NOW(),INTERVAL 30 DAY))",c,d,ref("OLD-NETWORK"));
            assertThat(policies.current(2)).containsKey("purchase");assertThat(policies.current(2).get("purchaseSplitConfigured")).isEqualTo(false);
            clock.advance();clock.advance();orderBasis("V1-AFTER-T",d,"1000");service.settle("direct_purchase",ref("V1-AFTER-T"),d);
            var v1After=orderRow("V1-AFTER-T");assertThat(((Number)v1After.get("split_enabled")).intValue()).isZero();assertThat(v1After.get("settlement_mode")).isEqualTo("SEVEN_V2");
            assertThat(layerRow("V1-AFTER-T",1).get("source_type")).isEqualTo("network");assertThat((BigDecimal)layerRow("V1-AFTER-T",1).get("amount_usdt")).isEqualByComparingTo("100");
            service.settle("direct_purchase",ref("OLD-DIRECT"),d);assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_direct_referral_settlement WHERE source_ref=?",Long.class,ref("OLD-DIRECT"))).isEqualTo(1);
            service.settle("direct_purchase",ref("OLD-NETWORK"),d);assertThat(orderRow("OLD-NETWORK").get("settlement_mode")).isEqualTo("LEGACY_7");assertThat(layers("OLD-NETWORK")).isEmpty();assertThat(commissionRows("OLD-NETWORK")).hasSize(1);
            record("funds-history","R25-R26-R41-v1-policy-is-not-v2-split",Map.of("old",groupRow("OLD-DIRECT"),"newOrder",v1After,"newL1",layerRow("V1-AFTER-T",1),"read",policies.current(2)));
            publishV2(true,"60",0);
            currentReadApprovalAndPreparation();
            orderBasis("SEVEN",d,"1000");service.settle("direct_purchase",ref("SEVEN"),d);
            var sevenRows=layers("SEVEN");assertThat(sevenRows).hasSize(7);
            assertThat((BigDecimal)sevenRows.get(0).get("amount_usdt")).isEqualByComparingTo("60");assertThat((BigDecimal)sevenRows.get(0).get("amount_nex")).isEqualByComparingTo("4000");
            for(int layer=2;layer<=7;layer++){assertThat((BigDecimal)sevenRows.get(layer-1).get("amount_usdt")).isEqualByComparingTo(new BigDecimal("1000").multiply(new BigDecimal(percentages.get(layer-1))).movePointLeft(2));assertThat((BigDecimal)sevenRows.get(layer-1).get("amount_nex")).isEqualByComparingTo(((BigDecimal)sevenRows.get(layer-1).get("amount_usdt")).multiply(new BigDecimal("2")));}
            assertThat(sevenRows.stream().map(row->((Number)row.get("layer_no")).intValue()).toList()).doesNotContain(8);
            assertThat(commissionRows("SEVEN")).allSatisfy(row->assertThat((BigDecimal)row.get("order_amount_usd")).isEqualByComparingTo("1000"));
            record("funds-seven-layers","R01-R02-R05-R06-original-deep-layer-amounts",Map.of("order",orderRow("SEVEN"),"groups",sevenRows,"commissionEvents",commissionRows("SEVEN")));
            var networkGroup=layerRow("SEVEN",2);String networkNo=(String)networkGroup.get("settlement_no");Long networkUsdt=((Number)networkGroup.get("usdt_event_id")).longValue(),networkNex=((Number)networkGroup.get("nex_event_id")).longValue();
            var manualBefore=wallet(b);due(networkNo);service.changeStatus(networkUsdt,"UNLOCKED",0L);assertDelta(manualBefore,wallet(b),"50","0");
            assertThat(mapper.eventView(networkNex).get("status")).isEqualTo("COOLING");
            A2ReplayContext.enterReplay(ref("MANUAL-NETWORK"));try{assertThat(f5.reverse("CM-"+networkUsdt,ref("MANUAL-NETWORK"),new ffdd.opsconsole.team.dto.F5CommissionReverseRequest(ref("SEVEN"),"approved single USDT commission only","acceptance")).getCode()).isZero();}finally{A2ReplayContext.exitReplay();}
            assertThat(wallet(b)).isEqualTo(manualBefore);var partial=layerRow("SEVEN",2);assertThat((BigDecimal)partial.get("cancelled_usdt")).isEqualByComparingTo("50");assertThat((BigDecimal)partial.get("cancelled_nex")).isEqualByComparingTo("0");
            String migration=Files.readString(Path.of("scripts/schema.sql"));migration=migration.substring(migration.indexOf("-- One order owns one immutable generation"));
            try(var connection=dataSource.getConnection();var statement=connection.createStatement()){for(String sql:migration.split(";"))if(!sql.isBlank())statement.execute(sql);}
            var afterMigration=layerRow("SEVEN",2);assertThat(afterMigration.get("cancelled_nex")).isEqualTo(partial.get("cancelled_nex"));assertThat(afterMigration.get("credited_nex_at")).isNull();
            assertThat(mapper.eventView(networkUsdt).get("status")).isEqualTo("REVERSED");assertThat(mapper.eventView(networkNex).get("status")).isEqualTo("COOLING");
            service.releaseEvent(networkNex);assertDelta(manualBefore,wallet(b),"0","100");jdbc.update("UPDATE nx_order SET payment_status='REFUNDED',order_status='REFUNDED' WHERE order_no=?",ref("SEVEN"));
            var delayedWallet=wallet(b);due((String)layerRow("SEVEN",1).get("settlement_no"));assertThatThrownBy(()->service.release((String)layerRow("SEVEN",1).get("settlement_no"))).hasMessage("DIRECT_REFERRAL_SOURCE_REFUNDED");assertThatThrownBy(()->service.releaseEvent(networkNex)).hasMessage("DIRECT_REFERRAL_SOURCE_REFUNDED");
            A2ReplayContext.enterReplay(ref("DELAYED-REFUND-REISSUE"));try{assertThatThrownBy(()->f5.reissue(ref("DELAYED-REFUND-REISSUE"),new ffdd.opsconsole.team.dto.F5CommissionReissueRequest(List.of("CM-"+networkUsdt),"late refund notification must prohibit reissue","acceptance"))).hasMessage("COMMISSION_SOURCE_REFUNDED");}finally{A2ReplayContext.exitReplay();}
            assertThat(wallet(b)).isEqualTo(delayedWallet);service.refund(ref("SEVEN"));assertThat(wallet(b)).isEqualTo(manualBefore);
            record("funds-refund","R19-R21-network-manual-action-stays-one-asset-full-refund-stays-chain",Map.of("partial",partial,"final",layerRow("SEVEN",2),"walletBefore",manualBefore,"walletAfter",wallet(b),"events",commissionRows("SEVEN")));
            orderBasis("PRE-CANCEL",d,"1000");service.settle("direct_purchase",ref("PRE-CANCEL"),d);var unpaid=layerRow("PRE-CANCEL",2);
            Long unpaidUsdt=((Number)unpaid.get("usdt_event_id")).longValue(),unpaidNex=((Number)unpaid.get("nex_event_id")).longValue();var unpaidBefore=wallet(b);
            A2ReplayContext.enterReplay(ref("UNPAID-CANCEL"));try{assertThat(f5.reverse("CM-"+unpaidUsdt,ref("UNPAID-CANCEL"),new ffdd.opsconsole.team.dto.F5CommissionReverseRequest(ref("PRE-CANCEL"),"approved unpaid single USDT cancellation","acceptance")).getCode()).isZero();}finally{A2ReplayContext.exitReplay();}
            due((String)unpaid.get("settlement_no"));service.releaseEvent(unpaidNex);assertDelta(unpaidBefore,wallet(b),"0","100");
            var onlyNex=layerRow("PRE-CANCEL",2);assertThat(onlyNex.get("credited_at")).isNotNull();assertThat(onlyNex.get("credited_usdt_at")).isNull();
            try(var connection=dataSource.getConnection();var statement=connection.createStatement()){for(String sql:migration.split(";"))if(!sql.isBlank())statement.execute(sql);}
            assertThat(layerRow("PRE-CANCEL",2).get("credited_usdt_at")).isNull();
            jdbc.update("UPDATE nx_order SET payment_status='REFUNDED',order_status='REFUNDED' WHERE order_no=?",ref("PRE-CANCEL"));service.refund(ref("PRE-CANCEL"));assertThat(wallet(b)).isEqualTo(unpaidBefore);
            assertThat((BigDecimal)layerRow("PRE-CANCEL",2).get("recovered_usdt")).isEqualByComparingTo("0");
            record("funds-refund","R21-repeat-migration-never-credits-or-recovers-unpaid-cancelled-asset",Map.of("unpaid",unpaid,"onlyNex",onlyNex,"final",layerRow("PRE-CANCEL",2),"walletBefore",unpaidBefore,"walletAfter",wallet(b)));
            orderBasis("RECOVERY-FAULT",d,"1000");service.settle("direct_purchase",ref("RECOVERY-FAULT"),d);var fault=layerRow("RECOVERY-FAULT",2);String faultNo=(String)fault.get("settlement_no");
            Long faultUsdt=((Number)fault.get("usdt_event_id")).longValue(),faultNex=((Number)fault.get("nex_event_id")).longValue();var faultBefore=wallet(b);due(faultNo);service.releaseEvent(faultUsdt);assertDelta(faultBefore,wallet(b),"50","0");
            assertThat(layerRow("RECOVERY-FAULT",2).get("credited_at")).isNull();jdbc.update("UPDATE nx_order SET payment_status='REFUNDED',order_status='REFUNDED' WHERE order_no=?",ref("RECOVERY-FAULT"));
            failRecovery.set(true);try{assertThatThrownBy(()->service.refund(ref("RECOVERY-FAULT"))).hasMessageContaining("injected recovery ledger failure");}finally{failRecovery.set(false);}
            var faultPending=layerRow("RECOVERY-FAULT",2);assertThat(faultPending.get("status")).isEqualTo("RECOVERY_PENDING");assertThat((BigDecimal)faultPending.get("recovery_pending_usdt")).isEqualByComparingTo("50");assertThat((BigDecimal)faultPending.get("recovery_pending_nex")).isZero();
            assertThat(mapper.eventView(faultUsdt).get("status")).isEqualTo("RECOVERY_PENDING");assertThat(mapper.eventView(faultNex).get("status")).isEqualTo("REVERSED");assertDelta(faultBefore,wallet(b),"50","0");
            service.refund(ref("RECOVERY-FAULT"));assertThat(wallet(b)).isEqualTo(faultBefore);assertThat(layerRow("RECOVERY-FAULT",2).get("status")).isEqualTo("REVERSED");
            record("funds-refund","R20-R23-partial-credit-ledger-failure-keeps-only-paid-asset-debt",Map.of("pending",faultPending,"final",layerRow("RECOVERY-FAULT",2),"walletBefore",faultBefore,"walletAfter",wallet(b)));
            String frozenCluster=ref("FROZEN-NETWORK");jdbc.update("INSERT INTO nx_admin_risk_multi_account_cluster(cluster_id,dedupe_key,layer_key,layer_label,account_count,strength,span_text,status,note_text,nodes_json) VALUES(?,?,'test','test',4,0,'test','FLAGGED','test',?)",frozenCluster,frozenCluster,json.writeValueAsString(List.of(Map.of("userNo","U"+b))));
            orderBasis("FROZEN-NETWORK",d,"1000");service.settle("direct_purchase",ref("FROZEN-NETWORK"),d);var frozenNetwork=layerRow("FROZEN-NETWORK",2);assertThat(frozenNetwork.get("status")).isEqualTo("FROZEN");Long frozenUsdt=((Number)frozenNetwork.get("usdt_event_id")).longValue(),frozenNex=((Number)frozenNetwork.get("nex_event_id")).longValue();
            assertThatThrownBy(()->service.changeStatus(frozenUsdt,"COOLING",0L)).hasMessage("DIRECT_REFERRAL_SPONSOR_FROZEN");jdbc.update("UPDATE nx_admin_risk_multi_account_cluster SET account_count=1,status='CLOSED' WHERE cluster_id=?",frozenCluster);
            service.changeStatus(frozenUsdt,"COOLING",0L);due((String)frozenNetwork.get("settlement_no"));var frozenBefore=wallet(b);service.releaseEvent(frozenUsdt);assertDelta(frozenBefore,wallet(b),"50","0");assertThat(mapper.eventView(frozenNex).get("status")).isEqualTo("FROZEN");
            record("funds-seven-layers","R18-R32-initially-held-network-recovers-one-approved-asset",Map.of("frozen",frozenNetwork,"final",layerRow("FROZEN-NETWORK",2),"events",commissionRows("FROZEN-NETWORK"),"walletBefore",frozenBefore,"walletAfter",wallet(b)));
            orderBasis("REISSUE-LINEAGE",d,"1000");service.settle("direct_purchase",ref("REISSUE-LINEAGE"),d);long lineageSource=((Number)layerRow("REISSUE-LINEAGE",2).get("usdt_event_id")).longValue();
            assertThat(f5Reverse(lineageSource,ref("REISSUE-LINEAGE"),"LINEAGE-CANCEL").getCode()).isZero();var lineageBefore=wallet(b);
            Long reissued=null;A2ReplayContext.enterReplay(ref("LINEAGE-REISSUE"));try{var result=f5.reissue(ref("LINEAGE-REISSUE"),new ffdd.opsconsole.team.dto.F5CommissionReissueRequest(List.of("CM-"+lineageSource),"approved single network reissue for lineage acceptance","acceptance"));assertThat(result.getCode()).isZero();reissued=jdbc.queryForObject("SELECT result_commission_id FROM nx_commission_operation WHERE operation_type='REISSUE' AND source_commission_id=?",Long.class,lineageSource);}finally{A2ReplayContext.exitReplay();}
            assertThat(mapper.sourceOrderForEvent(reissued)).isEqualTo(ref("REISSUE-LINEAGE"));jdbc.update("UPDATE nx_commission_event SET status='UNLOCKED' WHERE id=?",reissued);treasury.releaseCommissionFunds(reissued);assertDelta(lineageBefore,wallet(b),"50","0");
            jdbc.update("UPDATE nx_order SET payment_status='REFUNDED',order_status='REFUNDED' WHERE order_no=?",ref("REISSUE-LINEAGE"));failRecovery.set(true);try{assertThatThrownBy(()->service.refund(ref("REISSUE-LINEAGE"))).hasMessageContaining("injected recovery ledger failure");}finally{failRecovery.set(false);}
            assertThat(mapper.eventRecovery(reissued)).isNull();assertThat(mapper.reissueRecoveryOrders("PRODUCTION","","")).contains(ref("REISSUE-LINEAGE"));service.recoverOrderReissues(ref("REISSUE-LINEAGE"));assertThat(wallet(b)).isEqualTo(lineageBefore);service.refund(ref("REISSUE-LINEAGE"));assertThat(wallet(b)).isEqualTo(lineageBefore);
            Long lockedReissue=reissued;assertThatThrownBy(()->service.lockEventSources(List.of(lockedReissue),true)).hasMessage("COMMISSION_SOURCE_REFUNDED");
            record("funds-refund","R22-R23-reissue-lineage-source-lock-and-first-failure-recovery",Map.of("originalEvent",lineageSource,"reissuedEvent",reissued,"recovery",mapper.eventRecovery(reissued),"walletBefore",lineageBefore,"walletAfter",wallet(b)));
            configured("team.ui.F.influence.clampMin","5");configured("team.ui.F.influence.clampMax","5");orderBasis("CAP",d,"1000");service.settle("direct_purchase",ref("CAP"),d);
            for(int i=0;i<100;i++)assertThat(service.settle("direct_purchase",ref("CAP"),d)).isZero();
            assertThat(layers("CAP")).hasSize(2);assertThat((BigDecimal)orderRow("CAP").get("allocated_budget_usdt")).isEqualByComparingTo("250");assertThat((BigDecimal)layerRow("CAP",2).get("amount_usdt")).isEqualByComparingTo("150");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_unilevel_order_settlement WHERE order_no=?",Long.class,ref("CAP"))).isEqualTo(1);
            record("funds-seven-layers","R08-R09-100-source-replays-hold-cap",Map.of("order",orderRow("CAP"),"groups",layers("CAP"),"events",commissionRows("CAP")));
            configured("team.ui.F.influence.clampMin","1");configured("team.ui.F.influence.clampMax","1");
            jdbc.update("UPDATE nx_price_index SET status='INACTIVE' WHERE status='ACTIVE'");orderBasis("MISSING-PRICE",d,"1000");service.settle("direct_purchase",ref("MISSING-PRICE"),d);
            var waiting=layerRow("MISSING-PRICE",1);assertThat(waiting.get("status")).isEqualTo("WAITING_CALCULATION");assertThat(waiting.get("nex_usdt_price")).isNull();assertThat(commissionRows("MISSING-PRICE")).hasSize(12);
            jdbc.update("UPDATE nx_user SET sponsor_user_id=? WHERE id=?",a,d);jdbc.update("UPDATE nx_commission_rule SET usdt_rate=0.01 WHERE commission_type='UNILEVEL' AND layer_no=2");
            jdbc.update("UPDATE nx_price_index SET status='ACTIVE' WHERE metric_code='NEX_USDT' AND sampled_at='2099-01-01'");publishV2(true,"80",0);service.settle("direct_purchase",ref("MISSING-PRICE"),d);
            var resolved=layerRow("MISSING-PRICE",1);assertThat(resolved.get("beneficiary_user_id")).isEqualTo(c);assertThat((BigDecimal)resolved.get("amount_usdt")).isEqualByComparingTo("60");assertThat((BigDecimal)resolved.get("amount_nex")).isEqualByComparingTo("4000");assertThat(resolved.get("release_at")).isEqualTo(waiting.get("release_at"));
            assertThat((BigDecimal)layerRow("MISSING-PRICE",2).get("amount_usdt")).isEqualByComparingTo("50");jdbc.update("UPDATE nx_user SET sponsor_user_id=? WHERE id=?",c,d);jdbc.update("UPDATE nx_commission_rule SET usdt_rate=0.05 WHERE commission_type='UNILEVEL' AND layer_no=2");
            record("funds-direct-split","R13-R14-missing-price-persists-source-before-retry",Map.of("waiting",waiting,"resolved",resolved,"order",orderRow("MISSING-PRICE")));
            configured("commission/cooling-days","0");publishV2(true,"60",0);orderBasis("ATOMIC",d,"1000");var cBefore=wallet(c);failNex.set(true);
            assertThatThrownBy(()->service.settle("direct_purchase",ref("ATOMIC"),d)).hasMessageContaining("second currency failure");assertThat(wallet(c)).isEqualTo(cBefore);assertThat(orderRow("ATOMIC")).isNotEmpty();assertThat(layerRow("ATOMIC",1).get("usdt_event_id")).isNull();
            failNex.set(false);service.settle("direct_purchase",ref("ATOMIC"),d);assertDelta(cBefore,wallet(c),"60","4000");
            record("funds-direct-split","R18-second-asset-failure-keeps-snapshot-and-no-half-wallet",Map.of("before",cBefore,"after",wallet(c),"order",orderRow("ATOMIC"),"groups",layers("ATOMIC")));
            receipt("DEVICE",b,"10","100",false);var bBefore=wallet(b);var aBefore=wallet(a);service.settle("direct_device_earning",ref("RECEIPT-DEVICE"),b);assertThat(wallet(b)).isEqualTo(bBefore);assertDelta(aBefore,wallet(a),"0.33","22");
            record("funds-direct-split","R03-R04-R07-device-extra-share-only-direct",Map.of("ownerBefore",bBefore,"ownerAfter",wallet(b),"inviterBefore",aBefore,"inviterAfter",wallet(a),"group",groupRow("RECEIPT-DEVICE")));
            var refundBefore=wallet(c);jdbc.update("UPDATE nx_user_wallet SET usdt_available=10,nex_available=100 WHERE user_id=?",c);jdbc.update("UPDATE nx_order SET payment_status='REFUNDED',order_status='REFUNDED' WHERE order_no=?",ref("ATOMIC"));service.refund(ref("ATOMIC"));
            var pending=layerRow("ATOMIC",1);assertThat(pending.get("status")).isEqualTo("RECOVERY_PENDING");assertThat((BigDecimal)pending.get("recovery_pending_usdt")).isEqualByComparingTo("50");assertThat((BigDecimal)pending.get("recovery_pending_nex")).isEqualByComparingTo("3900");
            assertThat(layers("ATOMIC").stream().skip(1).allMatch(row->"REVERSED".equals(row.get("status")))).isTrue();
            assertThatThrownBy(()->service.release((String)pending.get("settlement_no"))).isInstanceOf(RuntimeException.class);service.refund(ref("ATOMIC"));assertWallet(c,"0","0");
            jdbc.update("UPDATE nx_user_wallet SET usdt_available=50,nex_available=3900 WHERE user_id=?",c);service.refund(ref("ATOMIC"));assertWallet(c,"0","0");assertThat(layerRow("ATOMIC",1).get("status")).isEqualTo("REVERSED");service.refund(ref("ATOMIC"));assertWallet(c,"0","0");
            record("funds-refund","R19-R20-R21-R23-full-chain-recovery-and-idempotent-debt",Map.of("before",refundBefore,"pending",pending,"final",layers("ATOMIC"),"order",orderRow("ATOMIC"),"ledger",ledgerRows((String)pending.get("settlement_no"))));
            publishV2(false,"60",0);orderBasis("SPLIT-OFF",d,"1000");service.settle("direct_purchase",ref("SPLIT-OFF"),d);assertThat(orderRow("SPLIT-OFF").get("settlement_mode")).isEqualTo("SEVEN_V2");assertThat(layerRow("SPLIT-OFF",1).get("source_type")).isEqualTo("network");
            assertThat((BigDecimal)layerRow("SPLIT-OFF",1).get("amount_usdt")).isEqualByComparingTo("100");assertThat((BigDecimal)layerRow("SPLIT-OFF",1).get("amount_nex")).isEqualByComparingTo("200");
            jdbc.update("UPDATE nx_order SET payment_status='REFUNDED',order_status='REFUNDED' WHERE order_no=?",ref("SPLIT-OFF"));service.refund(ref("SPLIT-OFF"));assertThat(layers("SPLIT-OFF").stream().allMatch(row->Set.of("REVERSED","RECOVERY_PENDING").contains(row.get("status")))).isTrue();
            assertThatThrownBy(()->policies.current(1)).hasMessage("TEAM_SCHEMA_UPDATE_REQUIRED");assertThatThrownBy(()->publish(rule("10",0),rule("5",0))).hasMessage("TEAM_SCHEMA_UPDATE_REQUIRED");
            record("funds-history","R26-R41-split-off-retains-new-generation-and-refund",Map.of("order",orderRow("SPLIT-OFF"),"groups",layers("SPLIT-OFF"),"policy",policies.current(2)));
            var request=new DirectReferralPolicyRequest(2,mapper.latestVersion(),policies.sevenLayerRevision()+1,new DirectReferralPolicyRequest.PurchaseSplit(true,new BigDecimal("60")),null,rule("5",0),"stale seven layer reference must reject");
            A2ReplayContext.enterReplay(ref("STALE-SEVEN"));try{assertThatThrownBy(()->policies.publish(ref("STALE-SEVEN"),request)).hasMessage("SEVEN_LAYER_REVISION_CONFLICT");}finally{A2ReplayContext.exitReplay();}
            record("funds-history","R27-R28-version-lock-and-v1-replay-rejection",Map.of("revision",policies.sevenLayerRevision(),"version",mapper.latestVersion(),"current",policies.current(2)));
            Path evidence=Path.of(System.getenv().getOrDefault("DIRECT_REFERRAL_EVIDENCE_DIR","target/direct-referral-runtime"));Files.createDirectories(evidence);
            json.writerWithDefaultPrettyPrinter().writeValue(evidence.resolve("seven-layer-funds.json").toFile(),Map.of("database",jdbc.queryForObject("SELECT DATABASE()",String.class),"port",33335,"groups",groups));
        } finally {
            failNex.set(false);
            for(var entry:previous.entrySet())if(entry.getValue()==null)jdbc.update("DELETE FROM nx_config_item WHERE config_key=?",entry.getKey());else configured(entry.getKey(),entry.getValue());
            jdbc.update("DELETE FROM nx_commission_rule WHERE commission_type='UNILEVEL'");
            for(var row:originalRates)jdbc.update("INSERT INTO nx_commission_rule(id,commission_type,layer_no,usdt_rate,nex_per_usd,status,is_deleted) VALUES(?,'UNILEVEL',?,?,?,?,?)",row.get("id"),row.get("layer_no"),row.get("usdt_rate"),row.get("nex_per_usd"),row.get("status"),row.get("is_deleted"));
        }
    }
    private void configured(String key,String value){jdbc.update("INSERT INTO nx_config_item(config_key,config_value,value_type,config_group,visibility,status,is_deleted) VALUES(?,?,'TEXT','team','ADMIN',1,0) ON DUPLICATE KEY UPDATE config_value=VALUES(config_value),status=1,is_deleted=0",key,value);}
    private void publishV2(boolean enabled,String share,int deviceDays){clock.advance();long before=mapper.latestVersion();String op=ref("V2-"+before);A2ReplayContext.enterReplay(op);try{var approved=policies.publish(op,new DirectReferralPolicyRequest(2,before,policies.sevenLayerRevision(),new DirectReferralPolicyRequest.PurchaseSplit(enabled,new BigDecimal(share)),null,rule("5",deviceDays),"isolated seven layer funds acceptance"));assertThat(approved.get("approvedPolicyVersion")).isEqualTo(before+1);assertThat(policies.current(2).get("policyVersion")).isEqualTo(before+1);assertThat(policies.current(2).get("purchaseSplitConfigured")).isEqualTo(true);assertThat(policies.current(2).get("purchaseSplit")).isEqualTo(Map.of("enabled",enabled,"usdtSharePct",new BigDecimal(share)));}finally{A2ReplayContext.exitReplay();}}
    private void orderBasis(String name,long user,String amount){order(name,user,amount);jdbc.update("UPDATE nx_order SET subtotal_usdt=? WHERE order_no=?",new BigDecimal(amount),ref(name));}
    private Map<String,Object> orderRow(String name){return jdbc.queryForMap("SELECT * FROM nx_unilevel_order_settlement WHERE order_no=?",ref(name));}
    private List<Map<String,Object>> layers(String name){return jdbc.queryForList("SELECT * FROM nx_direct_referral_settlement WHERE source_ref=? AND source_type IN ('network','direct_purchase') ORDER BY layer_no",ref(name));}
    private Map<String,Object> layerRow(String name,int layer){return layers(name).stream().filter(row->((Number)row.get("layer_no")).intValue()==layer).findFirst().orElseThrow();}
    private List<Map<String,Object>> commissionRows(String name){return jdbc.queryForList("SELECT id,user_id,commission_type,layer_no,order_no,order_amount_usd,currency,amount_usdt,amount_nex,status FROM nx_commission_event WHERE order_no=? ORDER BY layer_no,currency",ref(name));}
    private void separately(Runnable action){var executor=Executors.newSingleThreadExecutor();try{executor.submit(()->new org.springframework.transaction.support.TransactionTemplate(new DataSourceTransactionManager(dataSource)).executeWithoutResult(tx->action.run())).get(30,TimeUnit.SECONDS);}catch(Exception e){throw new IllegalStateException("independent current-read fixture",e);}finally{executor.shutdownNow();}}
    private void currentReadApprovalAndPreparation() {
        var transaction=new org.springframework.transaction.support.TransactionTemplate(new DataSourceTransactionManager(dataSource));
        assertThatThrownBy(()->transaction.executeWithoutResult(tx->{
            assertThat(policies.at(LocalDateTime.ofInstant(clock.instant(),ZoneId.of("Asia/Shanghai"))).purchase().enabled()).isTrue();
            separately(()->publishV2(false,"60",0));when(coverage.snapshot()).thenReturn(new TreasuryCoverageSnapshot(new BigDecimal("50"),new BigDecimal("100"),true));
            long version=mapper.latestVersionForUpdate();String op=ref("RR-BASELINE");A2ReplayContext.enterReplay(op);try{policies.publish(op,new DirectReferralPolicyRequest(2,version,policies.lockSevenLayerRevision(),new DirectReferralPolicyRequest.PurchaseSplit(true,new BigDecimal("60")),null,rule("5",0),"current baseline must detect NEX amplification"));}finally{A2ReplayContext.exitReplay();}
        })).hasMessage("COVERAGE_BELOW_REDLINE");
        transaction.executeWithoutResult(tx->{
            assertThat(policies.sevenLayerReference().get("legacyNexPerUsd")).isEqualTo(new BigDecimal("2.000000"));
            separately(()->{policies.lockSevenLayerRevision();jdbc.update("UPDATE nx_commission_rule SET nex_per_usd=100 WHERE commission_type='UNILEVEL' AND layer_no=1");configured("commission/cooling-days","45");policies.bumpSevenLayerRevision();});
            long revision=policies.lockSevenLayerRevision(),version=mapper.latestVersionForUpdate();String op=ref("RR-REFERENCE");A2ReplayContext.enterReplay(op);
            try{var approved=policies.publish(op,new DirectReferralPolicyRequest(2,version,revision,new DirectReferralPolicyRequest.PurchaseSplit(true,new BigDecimal("60")),null,rule("5",0),"current seven reference permits actual contraction"));
                assertThat(((Map<?,?>)approved.get("sevenLayerReference")).get("legacyNexPerUsd")).isEqualTo(new BigDecimal("100.000000"));assertThat(((Map<?,?>)approved.get("sevenLayerReference")).get("coolingDays")).isEqualTo(45);
                var saved=json.readTree(jdbc.queryForObject("SELECT purchase_json FROM nx_direct_referral_policy WHERE policy_version=?",String.class,version+1));assertThat(saved.path("sevenLayerRevision").asLong()).isEqualTo(revision);assertThat(saved.path("sevenLayerReference").path("legacyNexPerUsd").decimalValue()).isEqualByComparingTo("100");
            }catch(com.fasterxml.jackson.core.JsonProcessingException e){throw new IllegalStateException(e);}finally{A2ReplayContext.exitReplay();}
        });
        when(coverage.snapshot()).thenReturn(new TreasuryCoverageSnapshot(new BigDecimal("200"),new BigDecimal("100"),true));
        separately(()->{policies.lockSevenLayerRevision();jdbc.update("UPDATE nx_commission_rule SET nex_per_usd=2 WHERE commission_type='UNILEVEL' AND layer_no=1");configured("commission/cooling-days","30");policies.bumpSevenLayerRevision();});
        transaction.executeWithoutResult(tx->{
            policies.sevenLayerActive(LocalDateTime.ofInstant(clock.instant(),ZoneId.of("Asia/Shanghai")));session.getMapper(ffdd.opsconsole.team.mapper.TeamCommissionMapper.class).unilevelRates();
            separately(()->{policies.lockSevenLayerRevision();jdbc.update("UPDATE nx_commission_rule SET usdt_rate=0.07 WHERE commission_type='UNILEVEL' AND layer_no=2");configured("commission/cooling-days","40");policies.bumpSevenLayerRevision();publishV2(false,"60",0);});
            orderBasis("RR-PREPARE",d,"1000");service.settle("direct_purchase",ref("RR-PREPARE"),d);var prepared=orderRow("RR-PREPARE");assertThat(((Number)prepared.get("split_enabled")).intValue()).isZero();assertThat(prepared.get("policy_version")).isEqualTo(mapper.latestVersionForUpdate());assertThat(prepared.get("seven_layer_revision")).isEqualTo(policies.lockSevenLayerRevision());
            assertThat((BigDecimal)layerRow("RR-PREPARE",2).get("amount_usdt")).isEqualByComparingTo("70");try{assertThat(json.readTree((String)prepared.get("chain_and_rules")).path("coolingDays").asInt()).isEqualTo(40);}catch(com.fasterxml.jackson.core.JsonProcessingException e){throw new IllegalStateException(e);}
        });
        record("funds-history","R27-R28-old-RR-view-cannot-change-approval-baseline-or-order-reference",Map.of("order",orderRow("RR-PREPARE"),"groups",layers("RR-PREPARE"),"policy",policies.current(2)));
        separately(()->{policies.lockSevenLayerRevision();jdbc.update("UPDATE nx_commission_rule SET usdt_rate=0.05 WHERE commission_type='UNILEVEL' AND layer_no=2");configured("commission/cooling-days","30");policies.bumpSevenLayerRevision();});publishV2(true,"60",0);
    }

    @Test void realSqlCalculationAndReissueScansDeliverThe101stSourcePastPermanentFirstPageFailures() {
        setUp();String run=ref("CURSOR");var delivery=mock(DirectReferralService.class);var scope=mock(DirectReferralPolicyService.class);
        when(scope.scope()).thenReturn(new DirectReferralPolicyService.Scope("SANDBOX",run,1));
        for(int index=1;index<=101;index++) {
            String suffix=String.format("%03d",index),no=ref("WAIT-"+suffix),order=ref("CURSOR-ORDER-"+suffix);
            jdbc.update("INSERT INTO nx_direct_referral_settlement(settlement_no,source_environment,run_id,source_type,source_ref,source_user_id,beneficiary_user_id,source_occurred_at,policy_version,policy_snapshot,status,release_at) VALUES(?,'SANDBOX',?,'direct_device_earning',?,?,?,NOW(),1,'{}','WAITING_CALCULATION',NOW())",no,run,ref("CURSOR-RECEIPT-"+suffix),d,a);
            jdbc.update("INSERT INTO nx_unilevel_order_settlement(source_environment,run_id,order_no,source_user_id,source_occurred_at,prepared_at,settlement_mode,policy_version,seven_layer_revision,policy_snapshot,chain_and_rules,status,refund_confirmed) VALUES('SANDBOX',?,?,?,NOW(),NOW(),'SEVEN_V2',2,0,'{}','{}','REFUNDED',1)",run,order,d);
            jdbc.update("INSERT INTO nx_commission_event(user_id,commission_type,source_user_id,layer_no,order_no,amount_usdt,amount_nex,currency,status,unlock_at) VALUES(?,'network',?,2,?,50,0,'USDT','REVERSED',NOW())",a,d,order);
            long original=jdbc.queryForObject("SELECT id FROM nx_commission_event WHERE order_no=?",Long.class,order);
            jdbc.update("INSERT INTO nx_commission_event(user_id,commission_type,source_user_id,layer_no,order_no,amount_usdt,amount_nex,currency,status,unlock_at) VALUES(?,'network',?,2,?,50,0,'USDT','UNLOCKED',NOW())",a,d,order+"-REISSUE");
            long reissued=jdbc.queryForObject("SELECT id FROM nx_commission_event WHERE order_no=?",Long.class,order+"-REISSUE");
            session.getMapper(F5CommissionMapper.class).insertOperation(ref("CURSOR-OP-"+suffix),"REISSUE",original,reissued,a,"network",new BigDecimal("50"),"USDT",null,"isolated scheduling fixture","acceptance",ref("CURSOR-IDEM-"+suffix));
            mapper.saveEventRecovery(reissued,order,"SANDBOX",run,a,"USDT",new BigDecimal("50"),BigDecimal.ZERO,new BigDecimal("50"));
            if(index<=100){doThrow(new IllegalStateException("permanent missing source fixture")).when(delivery).resumeGroup(no);doThrow(new IllegalStateException("permanent empty wallet fixture")).when(delivery).recoverOrderReissues(order);}
        }
        assertThat(mapper.waitingCalculation("SANDBOX",run,"")).hasSize(100);assertThat(mapper.waitingCalculation("SANDBOX",run,ref("WAIT-100"))).containsExactly(ref("WAIT-101"));
        assertThat(mapper.reissueRecoveryOrders("SANDBOX",run,"")).hasSize(100);assertThat(mapper.reissueRecoveryOrders("SANDBOX",run,ref("CURSOR-ORDER-100"))).containsExactly(ref("CURSOR-ORDER-101"));
        var scheduler=new DirectReferralRecoveryScheduler(mapper,delivery,scope);scheduler.recover();scheduler.recover();scheduler.recover();
        verify(delivery).resumeGroup(ref("WAIT-101"));verify(delivery).recoverOrderReissues(ref("CURSOR-ORDER-101"));
        verify(delivery,times(2)).resumeGroup(ref("WAIT-001"));verify(delivery,times(2)).recoverOrderReissues(ref("CURSOR-ORDER-001"));
    }

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
        assertThat(groupRow("FAIL-NEX").get("status")).isEqualTo("COOLING");
        assertThat(groupRow("FAIL-NEX").get("usdt_event_id")).isNull();
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
                .hasMessage("COMMISSION_SOURCE_REFUNDED");}finally{A2ReplayContext.exitReplay();}
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
        var legacyConsumer=new VRankPassiveEvaluationConsumer(json,mock(VRankPromotionEngine.class),oldNetwork,mock(ffdd.opsconsole.team.mapper.TeamCommissionMapper.class));
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
        service.settle("direct_purchase",ref("NO-PRICE"),b);
        assertThat(groupRow("NO-PRICE").get("status")).isEqualTo("WAITING_CALCULATION");
        assertThat(groupRow("NO-PRICE").get("beneficiary_user_id")).isEqualTo(a);
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
        assertThat(groupRow("RISK").get("status")).isEqualTo("COOLING");
        assertThat(groupRow("RISK").get("usdt_event_id")).isNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_earnings_release_entry WHERE source_ref LIKE ?",Long.class,group("RISK")+"%")).isZero();
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
        for(Class<?> type:List.of(DirectReferralMapper.class,ffdd.opsconsole.team.mapper.TeamCommissionMapper.class,EarningsReleaseMapper.class,TreasuryLedgerMapper.class,NexMarketMapper.class,F5CommissionMapper.class,ffdd.opsconsole.risk.mapper.RiskOpsMapper.class,ffdd.opsconsole.shared.idempotency.mapper.AdminIdempotencyRecordMapper.class))config.addMapper(type);
        session=new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(config));mapper=session.getMapper(DirectReferralMapper.class);
        session.getMapper(ffdd.opsconsole.risk.mapper.RiskOpsMapper.class).createMultiAccountClusterTable();
        platform=mock(PlatformConfigFacade.class);
        when(platform.activeValue(anyString())).thenAnswer(call->{var values=jdbc.queryForList("SELECT config_value FROM nx_config_item WHERE config_key=? AND status=1 AND is_deleted=0",String.class,(Object)call.getArgument(0));return values.isEmpty()?Optional.empty():Optional.of(values.get(0));});
        doAnswer(call->{return jdbc.update("INSERT IGNORE INTO nx_config_item(config_key,config_value) VALUES(?,?)",(String)call.getArgument(0),(String)call.getArgument(1)) == 1;}).when(platform).insertAdminValueIfMissing(anyString(),anyString(),anyString(),anyString(),anyString());
        when(platform.activeValueForUpdate(anyString())).thenAnswer(call->{var values=jdbc.queryForList("SELECT config_value FROM nx_config_item WHERE config_key=? AND status=1 AND is_deleted=0 FOR UPDATE",String.class,(Object)call.getArgument(0));return values.isEmpty()?Optional.empty():Optional.of(values.get(0));});
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
        var ledger=spy(new TreasuryLedgerPostingFacadeAdapter(treasury, null));
        doAnswer(call->{if(failRecovery.get()&&call.<String>getArgument(0).contains("-RECOVER-"))throw new IllegalStateException("injected recovery ledger failure");call.callRealMethod();return null;})
                .when(ledger).postLedgerEntry(anyString(),anyLong(),anyString(),anyString(),anyString(),any(BigDecimal.class),anyString(),anyString());
        service=proxy(new DirectReferralService(mapper,policies,earnings,releaseMapper,risk,ledger,outbox,json,clock));
        var self=mock(org.springframework.beans.factory.ObjectProvider.class);when(self.getObject()).thenReturn(service);org.springframework.test.util.ReflectionTestUtils.setField(service,"self",self);
        var idempotencyMapper=session.getMapper(ffdd.opsconsole.shared.idempotency.mapper.AdminIdempotencyRecordMapper.class);
        var expiry=proxy(new ffdd.opsconsole.shared.idempotency.AdminIdempotencyExpiryTransitionExecutor(idempotencyMapper));
        var executor=proxy(new ffdd.opsconsole.shared.idempotency.AdminIdempotencyTransactionExecutor(idempotencyMapper,json,expiry));
        var idempotency=new AdminIdempotencyService(executor,clock);
        var provider=mock(org.springframework.beans.factory.ObjectProvider.class);when(provider.getIfAvailable()).thenReturn(service);
        f5=new F5CommissionService(session.getMapper(F5CommissionMapper.class),platform,coverage,ledger,audit,outbox,idempotency,provider);
        var commissions=mock(ffdd.opsconsole.team.domain.TeamCommissionRepository.class);
        when(commissions.commissionEvents(anyInt())).thenAnswer(call->jdbc.queryForList("SELECT CONCAT('CM-',id) id,commission_type kind,CONCAT('U',user_id) user,CASE WHEN currency='NEX' THEN amount_nex ELSE amount_usdt END amount,currency,status rawStatus,version FROM nx_commission_event WHERE order_no LIKE ? ORDER BY id LIMIT 100",marker+"%"));
        when(commissions.recordCommissionOperation(anyString(),anyString(),anyString(),anyLong(),anyString(),anyString())).thenReturn(true);
        var permissions=mock(ffdd.opsconsole.shared.security.AdminPermissionCache.class);
        when(permissions.getPermissionCodes(anyLong())).thenReturn(Set.of("network_f5_commission_dispose","network_f5_commission_reject"));
        ops=proxy(new OpsTeamService(platform,coverage,ledger,audit,ffdd.opsconsole.shared.seed.OpsReadTimeSeedPolicy.enabledForDirectConstruction(),mock(ffdd.opsconsole.team.domain.TeamFulfillmentQueueRepository.class),commissions,permissions,mock(ffdd.opsconsole.platform.mapper.AuditObjectLockMapper.class),mock(VRankPromotionEngine.class),mock(VRankRewardDispatcher.class),outbox,mock(LeadershipPoolService.class),f5,idempotency,null,provider,mock(VRankSkuFulfillmentService.class), null));
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
