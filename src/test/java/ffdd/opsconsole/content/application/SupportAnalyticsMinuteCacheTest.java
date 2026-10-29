package ffdd.opsconsole.content.application;

import ffdd.opsconsole.content.domain.SupportAnalyticsStats.*;
import ffdd.opsconsole.content.domain.SupportGroupFacts.*;
import ffdd.opsconsole.content.domain.SupportRules;
import ffdd.opsconsole.content.mapper.SupportAnalyticsFactMapper;
import ffdd.opsconsole.content.mapper.SupportAnalyticsMapper;
import ffdd.opsconsole.content.mapper.SupportAnalyticsMapper.*;
import ffdd.opsconsole.device.facade.SupportDeviceReadFacade;
import ffdd.opsconsole.finance.facade.FinanceSupportPaymentFactsFacade;
import ffdd.opsconsole.finance.facade.SupportFundsReadFacade;
import ffdd.opsconsole.finance.facade.SupportPaymentFacts;
import ffdd.opsconsole.team.facade.SupportInvitationReadFacade;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class SupportAnalyticsMinuteCacheTest {
    static final Instant AT=Instant.parse("2026-10-29T00:00:00Z");
    static final LocalDateTime T=LocalDateTime.of(2026,10,29,0,0);
    static final class Fixture {
        final SupportAnalyticsMapper scoped=mock(SupportAnalyticsMapper.class);
        final SupportAnalyticsFactMapper system=mock(SupportAnalyticsFactMapper.class);
        final FinanceSupportPaymentFactsFacade finance=mock(FinanceSupportPaymentFactsFacade.class);
        final SupportFundsReadFacade funds=mock(SupportFundsReadFacade.class);
        final SupportDeviceReadFacade devices=mock(SupportDeviceReadFacade.class);
        final SupportInvitationReadFacade invitations=mock(SupportInvitationReadFacade.class);
        final SupportAnalyticsFactSampler cache=mock(SupportAnalyticsFactSampler.class);
        final SupportAnalyticsService service=new SupportAnalyticsService(mock(SupportOwnershipService.class),finance,scoped,invitations,devices,funds,cache,system);
        final SupportAnalyticsFactSnapshot raw;
        Fixture(boolean brokenPeer){
            var ids=brokenPeer?List.of(1L,2L):List.of(1L);
            var histories=ids.stream().map(id->new SupportPaymentFacts.FirstHistory(id,SupportPaymentFacts.Status.READY,List.of())).toList();
            var coverage=Arrays.stream(SupportPaymentFacts.Source.values()).map(s->new SupportPaymentFacts.Coverage(s,SupportPaymentFacts.Status.READY,SupportPaymentFacts.Status.READY,SupportPaymentFacts.Status.READY,SupportPaymentFacts.Status.READY,T.minusDays(30),List.of(),0,"v1")).toList();
            var customerCoverage=ids.stream().flatMap(id->Arrays.stream(SupportPaymentFacts.Source.values()).map(s->new SupportPaymentFacts.CustomerCoverage(id,s,SupportPaymentFacts.Status.READY,SupportPaymentFacts.Status.READY,SupportPaymentFacts.Status.READY,T.minusDays(30),List.of()))).toList();
            var payment=new SupportPaymentFacts.Snapshot(List.of(),List.of(),coverage,"Asia/Shanghai",AT,histories,customerCoverage);
            var netRows=ids.stream().map(id->new SupportFundsReadFacade.NetCustomer(id,SupportFundsReadFacade.EvidenceState.READY,List.of(),BigDecimal.ZERO,BigDecimal.ZERO)).toList();
            var origins=brokenPeer?List.of(new SupportFundsReadFacade.ContributionOrigin("broken",2L,"missing",T,"Asia/Shanghai",0,"")):List.<SupportFundsReadFacade.ContributionOrigin>of();
            raw=new SupportAnalyticsFactSnapshot(AT,ids,payment,new SupportFundsReadFacade.NetSnapshot(netRows,origins,List.of()),List.of(),List.of());
            when(system.currentCustomers()).thenReturn(List.of(new CurrentCustomer(1L,"BOUND","UNGROUPED",0,7L,null)));
            when(system.activityCoverage()).thenReturn(new ActivityCoverage(T.minusDays(30),T,T));
            when(system.activityRules()).thenReturn(new ActivityRules(1L,7));
            when(system.activityEvents(anyCollection(),eq(T))).thenReturn(List.of(new ActivityRow(1L,T)));
            when(system.activityIdentityRows(anyCollection(),eq(T))).thenReturn(List.of(new ActivityIdentityRow(1L,11L,1L,"LOGIN:11",T)));
            when(system.queryRules()).thenReturn(new SupportRules(1L,7,7,7,"INFINITE",null));
            when(system.rootDisplayRows(anyCollection())).thenReturn(List.of(new RootDisplayRow(1L,"customer","U00000001","ACTIVE",T,T,T,null,null)));
            when(system.customerTagRows(anyCollection())).thenReturn(List.of());when(system.taskRows(anyMap())).thenReturn(List.of());when(system.pendingReplyRows(anyCollection())).thenReturn(List.of());
            when(invitations.readInvitations(List.of(1L))).thenReturn(List.of(new SupportInvitationReadFacade.Invitation(1L,0,List.of(),List.of(),SupportInvitationReadFacade.Completeness.COMPLETE,Set.of())));
            when(devices.readCurrent(List.of(1L))).thenReturn(new SupportDeviceReadFacade.Snapshot(List.of(),List.of(),T));
            var wallet=new SupportFundsReadFacade.WalletRead(List.of(new SupportFundsReadFacade.WalletEvidence(1L,9L,0L,BigDecimal.ZERO,BigDecimal.ZERO,T,SupportFundsReadFacade.EvidenceState.READY,List.of())),SupportFundsReadFacade.ReadState.READY,List.of(),T);
            var withdrawals=new SupportFundsReadFacade.WithdrawalRead(List.of(),SupportFundsReadFacade.ReadState.READY,List.of(),T,SupportFundsReadFacade.CoverageState.UNKNOWN,SupportFundsReadFacade.CoverageState.UNKNOWN);
            when(funds.readCurrent(anyCollection())).thenReturn(new SupportFundsReadFacade.Snapshot(List.of(1L),wallet,withdrawals,"Asia/Shanghai"));
        }
        SupportAnalyticsFactSnapshot compile(){return service.compileForCache(raw);}
    }
    @Test void realCompilerPrecomputesBothCurrenciesAndAssetViewsWithoutActorSql(){
        var f=new Fixture(false);var compiled=f.compile();
        assertThat(compiled.compiled().customers()).hasSize(6);
        assertThat(compiled.compiled().customers().get("HISTORY:USDT").get(0).metrics().lifetime().get(0).net().confirmedAmount()).isEqualByComparingTo("0");
        assertThat(compiled.compiled().customers().get("ASSET:UVT").get(0).metrics().lifetimeStatus()).isEqualTo(Status.UNAVAILABLE);
        verifyNoInteractions(f.scoped);verify(f.funds,never()).readNetContributions(anyCollection());
    }
    @Test void foreignBrokenNetStructureDoesNotPoisonAnAuthorizedCustomer(){
        var f=new Fixture(true);var compiled=f.compile();
        assertThat(compiled.compiled().verifiedNet().get(1L).complete(Set.of(1L))).isTrue();
        assertThat(compiled.compiled().verifiedNet().get(2L).complete(Set.of(2L))).isFalse();
        assertThat(compiled.compiled().customers().get("HISTORY:USDT").get(0).metrics().lifetime().get(0).net().confirmedAmount()).isEqualByComparingTo("0");
    }
    @Test void requestReadsCompiledCustomerMetricsAndCurrentOwnershipWithoutHeavyReaders(){
        var f=new Fixture(false);var compiled=f.compile();when(f.cache.current()).thenReturn(compiled);
        var scope=new ReadScope(7L,ReadMode.PERSONAL,null,null);
        when(f.scoped.currentCustomers(scope)).thenReturn(List.of(new CurrentCustomer(1L,"BOUND","UNGROUPED",0,7L,null)));
        when(f.scoped.currentGrantedGroupIds(scope)).thenReturn(List.of());
        clearInvocations(f.finance,f.funds,f.devices,f.invitations,f.system);
        var query=new Query(ReadMode.PERSONAL,null,null,Basis.CURRENT_CUSTOMER_HISTORY,null,null,"Asia/Shanghai","USDT");
        var result=f.service.evaluateForQuery(query,scope,false);
        assertThat(result.result().currentCustomers()).extracting(Customer::customerId).containsExactly(1L);
        assertThat(result.result().asOf()).isEqualTo(AT);
        when(f.scoped.currentCustomers(scope)).thenReturn(List.of());
        assertThat(f.service.evaluateForQuery(query,scope,false).result().currentCustomers()).isEmpty();
        verifyNoInteractions(f.finance,f.funds,f.devices,f.invitations,f.system);
    }
    @Test void newUnobservedCustomerDevicesRemainUnknownInTheActualQueryPath(){
        var f=new Fixture(false);var compiled=f.compile();when(f.cache.current()).thenReturn(compiled);
        var scope=new ReadScope(7L,ReadMode.PERSONAL,null,null);
        when(f.scoped.currentCustomers(scope)).thenReturn(List.of(new CurrentCustomer(99L,"BOUND","UNGROUPED",0,7L,null)));
        when(f.scoped.currentGrantedGroupIds(scope)).thenReturn(List.of());
        var result=f.service.evaluateForQuery(new Query(ReadMode.PERSONAL,null,null,Basis.CURRENT_ASSET,null,null,"Asia/Shanghai","USDT"),scope,false);
        assertThat(result.result().currentCustomers().get(0).metrics().devices().held().confirmedValue()).isNull();
        assertThat(result.result().currentMetrics().devices().held().confirmedValue()).isNull();
        assertThat(result.evidence().unobservedSubjects).isTrue();
    }
    @Test void newCustomerAfterAnEmptyGenerationDoesNotRequireInventedDeviceObservationTime(){
        var f=new Fixture(false);when(f.cache.current()).thenReturn(SupportAnalyticsCacheFixtures.empty(AT));
        var scope=new ReadScope(7L,ReadMode.PERSONAL,null,null);
        when(f.scoped.currentCustomers(scope)).thenReturn(List.of(new CurrentCustomer(99L,"BOUND","UNGROUPED",0,7L,null)));
        when(f.scoped.currentGrantedGroupIds(scope)).thenReturn(List.of());
        var result=f.service.evaluateForQuery(new Query(ReadMode.PERSONAL,null,null,Basis.CURRENT_ASSET,null,null,"Asia/Shanghai","USDT"),scope,false);
        assertThat(result.result().currentCustomers().get(0).metrics().devices().held().confirmedValue()).isNull();
        assertThat(result.result().currentMetrics().devices().held().confirmedValue()).isNull();
        assertThat(result.evidence().stock.evaluatedDbAt()).isNull();
        verifyNoInteractions(f.finance,f.funds,f.devices,f.invitations,f.system);
    }
    @Test void cachedFundsOnlyCarrySelectedCustomerQualityAndNewSubjectsRemainUnknown(){
        var all=new Fixture(false).compile().compiled().funds();
        var bad=new SupportFundsReadFacade.WalletEvidence(2L,19L,0L,BigDecimal.ZERO,BigDecimal.ZERO,T,SupportFundsReadFacade.EvidenceState.UNKNOWN,List.of(SupportFundsReadFacade.Reason.SOURCE_OBSERVATION_UNVERIFIED));
        var combined=new SupportFundsReadFacade.Snapshot(List.of(1L,2L),new SupportFundsReadFacade.WalletRead(List.of(all.wallets().rows().get(0),bad),SupportFundsReadFacade.ReadState.PARTIAL,bad.reasons(),T),all.withdrawals(),all.businessZone());
        var own=SupportAnalyticsPrivateQueryService.cachedFunds(combined,Set.of(1L));
        assertThat(own.wallets().state()).isEqualTo(SupportFundsReadFacade.ReadState.READY);
        assertThat(own.wallets().reasons()).isEmpty();
        assertThat(SupportAnalyticsQueryVersion.fundsObservation(own,Set.of(1L))).isEqualTo(SupportAnalyticsQueryVersion.ReadState.COMPLETE);
        var unseen=SupportAnalyticsPrivateQueryService.cachedFunds(combined,Set.of(99L));
        assertThat(unseen.wallets().state()).isEqualTo(SupportFundsReadFacade.ReadState.UNKNOWN);
        assertThat(unseen.wallets().rows().get(0).usdtAvailable()).isNull();
        assertThat(SupportAnalyticsQueryVersion.fundsObservation(unseen,Set.of(99L))).isEqualTo(SupportAnalyticsQueryVersion.ReadState.UNKNOWN);
    }
    @Test void liveTaskReadsRecoverEvenWhenTheCompletedStatisticsHadNoActivityCoverage(){
        var f=new SupportAnalyticsPrivateQueryServiceTest.Fixture(true);
        SupportAnalyticsPrivateQueryServiceTest.authenticate("service_m1_read");
        try {
            f.capture.sampledFacts=SupportAnalyticsCacheFixtures.empty(AT);f.capture.activityCoverage=null;
            f.capture.activities=Map.of(100L,new CustomerActivity(null,WindowState.UNKNOWN,Status.UNKNOWN,List.of()));
            when(f.mapper.activityCoverage(f.scope)).thenReturn(new ActivityCoverage(T.minusDays(30),SupportAnalyticsPrivateQueryServiceTest.T,SupportAnalyticsPrivateQueryServiceTest.T));
            assertThat(f.service.query(Map.of("filter",List.of("TODO"))).get("records")).asList().hasSize(1);
            verify(f.mapper).pendingReplyRows(f.scope,Set.of(100L));
            when(f.mapper.activityCoverage(f.scope)).thenReturn(null);
            assertThatThrownBy(()->f.service.query(Map.of("filter",List.of("TODO")))).hasMessageContaining("SUPPORT_ANALYTICS_SOURCE_UNAVAILABLE");
        } finally {org.springframework.security.core.context.SecurityContextHolder.clearContext();}
    }
    @Test void fixedRedisCodecPreservesCompiledStatisticsAndRejectsMalformedValues() throws Exception {
        var codec=new SupportAnalyticsCacheCodec();var snapshot=new Fixture(false).compile();var encoded=codec.encode(snapshot);
        assertThat(codec.decode(encoded)).isEqualTo(snapshot);
        assertThat(encoded).doesNotContain("\"actorId\"","\"@class\"");
        assertThatThrownBy(()->codec.decode("{\"schemaVersion\":1,"+encoded.substring(1))).hasMessageContaining("CACHE_INVALID");
        var json=new com.fasterxml.jackson.databind.ObjectMapper();
        for(String mutation:List.of("float","null","enum","nullStatus","nullDevices","missing","future")){
            var tree=(com.fasterxml.jackson.databind.node.ObjectNode)json.readTree(encoded);
            switch(mutation){
                case "float"->tree.put("schemaVersion",1.5);
                case "null"->tree.putNull("schemaVersion");
                case "enum"->((com.fasterxml.jackson.databind.node.ObjectNode)tree.path("statistics").path("compiled").path("funds").path("wallets")).put("state",0);
                case "nullStatus"->((com.fasterxml.jackson.databind.node.ObjectNode)tree.path("statistics").path("compiled").path("funds").path("wallets")).putNull("state");
                case "nullDevices"->((com.fasterxml.jackson.databind.node.ObjectNode)tree.path("statistics").path("compiled")).putNull("devices");
                case "missing"->((com.fasterxml.jackson.databind.node.ObjectNode)tree.path("statistics").path("compiled")).remove("customers");
                case "future"->((com.fasterxml.jackson.databind.node.ObjectNode)tree.path("statistics")).put("asOf",Instant.now().plusSeconds(60).toString());
            }
            assertThatThrownBy(()->codec.decode(tree.toString())).as(mutation).hasMessageContaining("CACHE_INVALID");
        }
    }
}
