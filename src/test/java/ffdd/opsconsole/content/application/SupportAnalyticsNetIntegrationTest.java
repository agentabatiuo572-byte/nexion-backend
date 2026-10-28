package ffdd.opsconsole.content.application;

import ffdd.opsconsole.content.domain.SupportAnalyticsStats.*;
import ffdd.opsconsole.content.domain.SupportGroupFacts.ReadMode;
import ffdd.opsconsole.content.domain.SupportGroupFacts.ReadScope;
import ffdd.opsconsole.content.mapper.SupportAnalyticsMapper;
import ffdd.opsconsole.finance.facade.FinanceSupportPaymentFactsFacade;
import ffdd.opsconsole.finance.facade.SupportFundsReadFacade;
import ffdd.opsconsole.finance.facade.SupportFundsReadFacade.*;
import ffdd.opsconsole.finance.facade.SupportPaymentFacts;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Trusted facade fixtures verify API aggregation, not a new replay or native cash proof. */
class SupportAnalyticsNetIntegrationTest {
    private final SupportOwnershipService ownership=mock(SupportOwnershipService.class);
    private final FinanceSupportPaymentFactsFacade finance=mock(FinanceSupportPaymentFactsFacade.class);
    private final SupportAnalyticsMapper mapper=mock(SupportAnalyticsMapper.class);
    private final SupportFundsReadFacade funds=mock(SupportFundsReadFacade.class);
    private SupportAnalyticsService service;
    @BeforeEach void setup() throws Exception {
        // Compile/run unchanged source for the genuine RED; select the narrow dependency constructor once it exists.
        try {service=SupportAnalyticsService.class.getConstructor(SupportOwnershipService.class,FinanceSupportPaymentFactsFacade.class,SupportAnalyticsMapper.class,SupportFundsReadFacade.class).newInstance(ownership,finance,mapper,funds);}
        catch(NoSuchMethodException absent){service=new SupportAnalyticsService(ownership,finance,mapper);}
        var scope=new ReadScope(7L,ReadMode.PERSONAL,null,null);
        when(ownership.queryScope(ReadMode.PERSONAL,null,null)).thenReturn(scope);
        when(mapper.currentCustomers(any())).thenReturn(List.of(new SupportAnalyticsMapper.CurrentCustomer(1L,"BOUND","GROUPED",0)));
        when(mapper.attributions(any(),any())).thenReturn(List.of());
        when(finance.readHistory(any())).thenReturn(new SupportPaymentFacts.Snapshot(List.of(),List.of(),List.of(),"Asia/Shanghai",Instant.parse("2026-10-09T05:00:00Z")));
        when(funds.readNetContributions(any())).thenReturn(new NetSnapshot(List.of(new NetCustomer(1L,EvidenceState.READY,List.of(),BigDecimal.ZERO,BigDecimal.ZERO)),List.of(),List.of()));
    }
    @Test void provenZeroUsdtComesFromFacadeRatherThanUnknownOrOldRechargeArithmetic(){readyZero("USDT");}
    @Test void provenZeroNexComesFromSameFacadeEvidence(){readyZero("UVT");}
    private void readyZero(String currency){
        var result=service.summarize(new Query(ReadMode.PERSONAL,null,null,Basis.CURRENT_CUSTOMER_HISTORY,null,null,"Asia/Shanghai",currency));
        var net=result.financialSummary().currencies().get(0).net();
        assertThat(net.status()).isEqualTo(Status.AVAILABLE);assertThat(net.confirmedAmount()).isEqualByComparingTo("0");
        verify(funds).readNetContributions(List.of(1L));
    }
    @Test void unknownSourceDoesNotBecomeZero(){
        when(funds.readNetContributions(any())).thenReturn(new NetSnapshot(List.of(new NetCustomer(1L,EvidenceState.UNKNOWN,List.of("SOURCE"),null,null)),List.of(),List.of()));
        var result=service.summarize(new Query(ReadMode.PERSONAL,null,null,Basis.CURRENT_CUSTOMER_HISTORY,null,null,"Asia/Shanghai","USDT"));
        assertThat(result.financialSummary().currencies().get(0).net().confirmedAmount()).isNull();
        assertThat(result.financialSummary().currencies().get(0).net().status()).isEqualTo(Status.UNKNOWN);
    }
    @Test void currentAssetDoesNotReadOrInventNet(){
        var result=service.summarize(new Query(ReadMode.PERSONAL,null,null,Basis.CURRENT_ASSET,null,null,"Asia/Shanghai","USDT"));
        assertThat(result.financialSummary().status()).isEqualTo(Status.UNAVAILABLE);verifyNoInteractions(funds);
    }
    @Test void privateVersionCannotCertifyUnknownNetObservation(){
        SupportAnalyticsPrivateQueryServiceTest.authenticate("service_m1_read");
        try {
        var f=new SupportAnalyticsPrivateQueryServiceTest.Fixture();
        f.capture.net=new NetSnapshot(List.of(new NetCustomer(100L,EvidenceState.UNKNOWN,List.of("SOURCE"),null,null)),List.of(),List.of());
        assertThat(f.service.query(java.util.Map.of())).containsEntry("versionState","UNKNOWN").containsEntry("queryVersion",null).containsEntry("canContinue",false);
        } finally {org.springframework.security.core.context.SecurityContextHolder.clearContext();}
    }
    @Test void currentLifetimeUsesFacadeNetAndPeriodUsesActualNegativeChangeTimeAndSavedOwner() throws Exception {
        paidOrigin(7L);
        var history=service.summarize(new Query(ReadMode.PERSONAL,null,null,Basis.CURRENT_CUSTOMER_HISTORY,null,null,"Asia/Shanghai","USDT"));
        assertThat(history.currentCustomers().get(0).metrics().lifetime().get(0).net().confirmedAmount()).isEqualByComparingTo("7");
        var period=service.summarize(new Query(ReadMode.PERSONAL,null,null,Basis.PERIOD_EVENT,java.time.LocalDateTime.of(2026,10,1,0,0),java.time.LocalDateTime.of(2026,10,10,0,0),"Asia/Shanghai","USDT"));
        assertThat(period.financialSummary().currencies().get(0).net().confirmedAmount()).isEqualByComparingTo("-3");
        verify(mapper,atLeastOnce()).attributionProofs(eq(new ReadScope(7L,ReadMode.PERSONAL,null,null)),eq(List.of("DEPOSIT:11")));
    }
    @Test void periodDoesNotGuessCurrentAdvisorForOriginalOwner() throws Exception {
        paidOrigin(9L);
        var period=service.summarize(new Query(ReadMode.PERSONAL,null,null,Basis.PERIOD_EVENT,java.time.LocalDateTime.of(2026,10,1,0,0),java.time.LocalDateTime.of(2026,10,10,0,0),"Asia/Shanghai","USDT"));
        assertThat(period.financialSummary().currencies().get(0).net().confirmedAmount()).isEqualByComparingTo("0");
    }
    @Test void missingSavedProofCannotCertifyKnownSubtotal() throws Exception {
        paidOrigin(7L);when(mapper.attributionProofs(any(),any())).thenReturn(List.of());
        var r=service.summarize(new Query(ReadMode.PERSONAL,null,null,Basis.CURRENT_CUSTOMER_HISTORY,null,null,"Asia/Shanghai","USDT"));
        assertThat(r.financialSummary().currencies().get(0).net().confirmedAmount()).isNull();
        assertThat(r.financialSummary().currencies().get(0).net().observedAmount()).isNull();
    }
    @Test void formalGeneralConsumptionContributesNetButCannotBecomeFirstPayment() throws Exception {
        paidOrigin(7L,true);
        var r=service.summarize(new Query(ReadMode.PERSONAL,null,null,Basis.CURRENT_CUSTOMER_HISTORY,null,null,"Asia/Shanghai","USDT"));
        assertThat(r.currentCustomers().get(0).first().state()).isEqualTo(FirstState.NONE);
        assertThat(r.financialSummary().firstCandidates().confirmedValue()).isEqualTo(0);
        assertThat(r.financialSummary().currencies().get(0).net().confirmedAmount()).isEqualByComparingTo("7");
    }
    @Test void failedCurrentEnumerationCannotBecomeProvenEmptyNet() {
        when(mapper.currentCustomers(any())).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("enumeration failed"));when(funds.readNetContributions(any())).thenReturn(new NetSnapshot(List.of(),List.of(),List.of()));
        var r=service.summarize(new Query(ReadMode.PERSONAL,null,null,Basis.CURRENT_CUSTOMER_HISTORY,null,null,"Asia/Shanghai","USDT"));
        assertThat(r.financialSummary().currencies().get(0).net().confirmedAmount()).isNull();
    }
    @Test void failedAllHistoryEventEnumerationCannotCertifyAZeroMonth() {
        when(mapper.eventCandidates(any())).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("events failed"));
        var r=service.summarize(new Query(ReadMode.PERSONAL,null,null,Basis.PERIOD_EVENT,java.time.LocalDateTime.of(2026,10,1,0,0),java.time.LocalDateTime.of(2026,10,10,0,0),"Asia/Shanghai","USDT"));
        assertThat(r.financialSummary().currencies().get(0).net().confirmedAmount()).isNull();
    }
    @Test void scopedProofSqlKeepsSameCurrentAndSavedEventAuthorizationBoundary() {
        var configuration=new org.apache.ibatis.session.Configuration();configuration.addMapper(SupportAnalyticsMapper.class);
        for(var mode:ReadMode.values()) {
            var scope=new ReadScope(7L,mode,mode==ReadMode.PERSONAL?null:100L,7L);var args=java.util.Map.<String,Object>of("scope",scope,"factIds",List.of("DEPOSIT:11"));
            var tuple=configuration.getMappedStatement(SupportAnalyticsMapper.class.getName()+".attributions").getBoundSql(args);
            var proof=configuration.getMappedStatement(SupportAnalyticsMapper.class.getName()+".attributionProofs").getBoundSql(args);
            assertThat(proof.getSql().substring(proof.getSql().indexOf("FROM nx_support_payment_attribution"))).isEqualTo(tuple.getSql().substring(tuple.getSql().indexOf("FROM nx_support_payment_attribution")));
            assertThat(proof.getSql()).contains("e.fact_id IN","scope_customer.sandbox=0").doesNotContain("DEPOSIT:11","${");
            assertThat(proof.getParameterMappings()).extracting(p->p.getProperty()).containsExactlyElementsOf(tuple.getParameterMappings().stream().map(p->p.getProperty()).toList());
        }
    }
    private void paidOrigin(long originalAgent) throws Exception {paidOrigin(originalAgent,false);}
    private void paidOrigin(long originalAgent,boolean general) throws Exception {
        var at=java.time.LocalDateTime.of(2026,9,28,12,0);var changeAt=java.time.LocalDateTime.of(2026,10,9,12,0);
        var fact=new SupportPaymentFacts.Fact(general?"CONSUMPTION:GENESIS11":"DEPOSIT:11",general?SupportPaymentFacts.Kind.CONSUMPTION:SupportPaymentFacts.Kind.DEPOSIT,general?SupportPaymentFacts.Source.GENESIS_PRIMARY:SupportPaymentFacts.Source.CARD_TOPUP,List.of("source11"),1L,11L,"business11",general?"GENESIS11":null,general?"PRIMARY":null,null,"USDT",new BigDecimal("10"),at,general?"nx_genesis_order.paid_at":"nx_wallet_ledger.created_at",0,null,at,null,"source-v1",SupportPaymentFacts.Status.UNKNOWN);
        var attribution=new SupportAnalyticsMapper.AttributionRow(fact.factId(),1L,fact.kind().name(),fact.source().name(),11L,"business11",fact.orderNo(),fact.orderType(),null,"USDT",fact.amount(),at,"Asia/Shanghai",fact.successTimeField(),0,"NEW_SUCCESS","support-payment-attribution-v1",originalAgent,100L,8L,"KNOWN","KNOWN","KNOWN");
        when(mapper.attributions(any(),any())).thenReturn(List.of(attribution));
        var source=new java.util.LinkedHashMap<String,Object>();source.put("factId",fact.factId());source.put("customerId",1L);source.put("ledgerId",11L);source.put("kind",fact.kind().name());source.put("source",fact.source().name());source.put("currency","USDT");source.put("amount",fact.amount());source.put("succeededAt",at.toString());source.put("businessZone","Asia/Shanghai");source.put("fractionalSecondDigits",0);
        var capture=java.time.LocalDateTime.of(2026,9,28,4,0);var proof=new java.util.LinkedHashMap<String,Object>();proof.put("schemaVersion",attribution.captureSchemaVersion());proof.put("captureMode","NEW_SUCCESS");proof.put("captureZone","UTC");proof.put("captureDbUtc",capture.toString());proof.put("sourcePartition",null);
        proof.put("agent",java.util.Map.of("status","KNOWN","id",originalAgent));proof.put("group",java.util.Map.of("status","KNOWN","id",100L));proof.put("owner",java.util.Map.of("status","KNOWN","id",8L));
        var json=new com.fasterxml.jackson.databind.ObjectMapper();when(mapper.attributionProofs(any(),any())).thenReturn(List.of(new SupportAnalyticsMapper.AttributionProof(fact.factId(),null,capture,json.writeValueAsString(source),json.writeValueAsString(proof))));
        when(finance.readHistory(any())).thenReturn(new SupportPaymentFacts.Snapshot(List.of(fact),List.of(),List.of(),"Asia/Shanghai",Instant.parse("2026-10-09T05:00:00Z"),List.of(new SupportPaymentFacts.FirstHistory(1L,SupportPaymentFacts.Status.READY,List.of()))));
        when(funds.readNetContributions(any())).thenReturn(new NetSnapshot(List.of(new NetCustomer(1L,EvidenceState.READY,List.of(),new BigDecimal("7"),BigDecimal.ZERO)),List.of(new ContributionOrigin("orig11",1L,fact.factId(),at,"Asia/Shanghai",0,"raw11")),
            List.of(new ContributionChange("orig11",1L,"USDT",new BigDecimal("10"),at,"Asia/Shanghai",0,"raw11"),new ContributionChange("orig11",1L,"USDT",new BigDecimal("-3"),changeAt,"Asia/Shanghai",0,"withdrawal19"))));
    }
}
