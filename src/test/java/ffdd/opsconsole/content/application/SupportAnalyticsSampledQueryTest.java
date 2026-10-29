package ffdd.opsconsole.content.application;

import ffdd.opsconsole.content.domain.SupportGroupFacts.*;
import ffdd.opsconsole.content.domain.SupportAnalyticsStats.*;
import ffdd.opsconsole.content.mapper.SupportAnalyticsMapper;
import ffdd.opsconsole.finance.facade.FinanceSupportPaymentFactsFacade;
import ffdd.opsconsole.finance.facade.SupportFundsReadFacade;
import ffdd.opsconsole.finance.facade.SupportFundsReadFacade.*;
import ffdd.opsconsole.device.facade.SupportDeviceReadFacade;
import ffdd.opsconsole.team.facade.SupportInvitationReadFacade;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class SupportAnalyticsSampledQueryTest {
    @Test void sampledFinanceStillProjectsCurrentBindingAndNeverInvokesRequestHistoryReplay() {
        var ownership=mock(SupportOwnershipService.class);var finance=mock(FinanceSupportPaymentFactsFacade.class);
        var mapper=mock(SupportAnalyticsMapper.class);var invitations=mock(SupportInvitationReadFacade.class);
        var devices=mock(SupportDeviceReadFacade.class);var funds=mock(SupportFundsReadFacade.class);var sampler=mock(SupportAnalyticsFactSampler.class);
        var base=SupportAnalyticsFactSnapshotTest.sample();
        var snapshot=new SupportAnalyticsFactSnapshot(base.asOf(),base.subjects(),base.payments(),new NetSnapshot(
            base.subjects().stream().map(id->new NetCustomer(id,EvidenceState.READY,List.of(),BigDecimal.ZERO,BigDecimal.ZERO)).toList(),List.of(),List.of()),base.attributions(),base.proofs());
        when(sampler.current()).thenReturn(snapshot);
        var scope=new ReadScope(7L,ReadMode.PERSONAL,null,null);
        when(mapper.currentGrantedGroupIds(scope)).thenReturn(List.of());
        when(mapper.currentCustomers(scope)).thenReturn(List.of(new SupportAnalyticsMapper.CurrentCustomer(1L,"BOUND","UNGROUPED",0,7L,null)));
        when(invitations.readInvitations(List.of(1L))).thenReturn(List.of(new SupportInvitationReadFacade.Invitation(1L,0,List.of(),List.of(),SupportInvitationReadFacade.Completeness.COMPLETE,Set.of())));
        when(devices.readCurrent(List.of(1L))).thenReturn(new SupportDeviceReadFacade.Snapshot(List.of(),List.of(),LocalDateTime.of(2026,10,29,0,0)));
        var service=new SupportAnalyticsService(ownership,finance,mapper,invitations,devices,funds,sampler);
        var query=new Query(ReadMode.PERSONAL,null,null,Basis.CURRENT_CUSTOMER_HISTORY,null,null,"Asia/Shanghai","USDT");
        var first=service.evaluateForQuery(query,scope,false);
        assertThat(first.result().currentCustomers()).extracting(Customer::customerId).containsExactly(1L);
        assertThat(first.result().currentCustomers().get(0).first().state()).isEqualTo(FirstState.NONE);
        assertThat(first.result().asOf()).isEqualTo(snapshot.asOf());
        when(mapper.currentCustomers(scope)).thenReturn(List.of());
        var afterTransfer=service.evaluateForQuery(query,scope,false);
        assertThat(afterTransfer.result().currentCustomers()).isEmpty();
        assertThat(afterTransfer.result().currentScope().total()).isZero();
        assertThat(afterTransfer.result().asOf()).isEqualTo(snapshot.asOf());
        verifyNoInteractions(finance,funds);
        verify(mapper,times(2)).currentCustomers(scope);
        verify(mapper,never()).attributions(any(),anyCollection());
        verify(mapper,never()).attributionProofs(any(),anyCollection());
    }
    @Test void internalFactMapperUsesProductionSubjectsAndHasNoOperatorOrScopeParameters() {
        var config=new org.apache.ibatis.session.Configuration();
        config.addMapper(ffdd.opsconsole.content.mapper.SupportAnalyticsFactMapper.class);
        String name=ffdd.opsconsole.content.mapper.SupportAnalyticsFactMapper.class.getName();
        assertThat(config.getMappedStatement(name+".productionCustomers").getBoundSql(null).getSql()).contains("sandbox=0").doesNotContain("is_deleted=0");
        for(String method:List.of("attributions","attributionProofs")) {
            var sql=config.getMappedStatement(name+"."+method).getBoundSql(null);
            assertThat(sql.getSql()).contains("JOIN nx_user","u.sandbox=0");
            assertThat(sql.getParameterMappings()).isEmpty();
        }
    }
}
