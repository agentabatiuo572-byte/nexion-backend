package ffdd.opsconsole.content.application;

import ffdd.opsconsole.content.domain.CustomerProfileRepository;
import ffdd.opsconsole.content.domain.SupportGroupFacts.*;
import ffdd.opsconsole.content.mapper.*;
import ffdd.opsconsole.device.application.OpsDeviceService;
import ffdd.opsconsole.finance.application.FinanceSupportReadService;
import ffdd.opsconsole.finance.facade.SupportFundsReadFacade;
import ffdd.opsconsole.finance.facade.SupportFundsReadFacade.*;
import ffdd.opsconsole.risk.application.OpsRiskService;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.storage.ObjectStorageService;
import ffdd.opsconsole.user.application.OpsUserService;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;

class SupportCustomerProfileNetTest {
    private final SupportOwnershipService ownership=mock(SupportOwnershipService.class);
    private final FinanceSupportReadService finance=mock(FinanceSupportReadService.class);
    private final SupportFundsReadFacade funds=mock(SupportFundsReadFacade.class);
    private SupportCustomerProfileService service() throws Exception {
        return service(null);
    }
    private SupportCustomerProfileService service(SupportAnalyticsService analytics) throws Exception {
        Object[] legacy={ownership,mock(SupportBindingMapper.class),mock(SupportCustomerProfileMapper.class),mock(OpsUserService.class),finance,
            mock(OpsDeviceService.class),mock(OpsRiskService.class),mock(CustomerProfileRepository.class),mock(SupportMaintenanceService.class),mock(SupportWorkbenchMapper.class),mock(ObjectStorageService.class)};
        when(ownership.customerQueryScope(1L)).thenReturn(new ReadScope(7L,ReadMode.PERSONAL,null,null));
        when(finance.totals(1L)).thenReturn(Map.of("byCurrency",List.of(Map.of("currency","USDT","balance","77","creditedDepositTotal","11","fieldStatuses",Map.of("balance","READY","creditedDepositTotal","READY")))));
        for(var ctor:SupportCustomerProfileService.class.getConstructors())if(ctor.getParameterCount()==14){var args=Arrays.copyOf(legacy,14);args[11]=funds;args[12]=mock(ffdd.opsconsole.device.facade.SupportDeviceReadFacade.class);args[13]=analytics;return (SupportCustomerProfileService)ctor.newInstance(args);}
        return (SupportCustomerProfileService)SupportCustomerProfileService.class.getConstructors()[0].newInstance(legacy);
    }
    @Test void profileUsesAuthorizedScopeForStatisticsAndPreservesSectionTimestamp() throws Exception {
        var analytics=mock(SupportAnalyticsService.class);var service=service(analytics);
        var scope=new ReadScope(7L,ReadMode.PERSONAL,null,null);var payload=Map.<String,Object>of("lifetimeStatus","UNKNOWN");
        when(analytics.statisticsForCustomer(1L,scope)).thenReturn(payload);
        var group=(Map<?,?>)service.profile(1L).get("statistics");assertThat(group.get("status")).isEqualTo("READY");
        assertThat(group.get("data")).isEqualTo(payload);assertThat(group.get("evaluatedAt")).isNotNull();
        verify(analytics).statisticsForCustomer(1L,scope);
        when(ownership.customerQueryScope(1L)).thenThrow(new BizException(403,"SUPPORT_SCOPE_FORBIDDEN"));clearInvocations(analytics);
        assertThatThrownBy(()->service.profile(1L)).isInstanceOf(BizException.class);verifyNoInteractions(analytics);
    }
    @SuppressWarnings("unchecked") private static List<Map<String,Object>> currencies(Map<String,Object> result){return (List<Map<String,Object>>)((Map<String,Object>)((Map<String,Object>)result.get("finance")).get("data")).get("byCurrency");}
    @Test void signedNetAndBothCurrenciesPreserveOldBalanceAndRechargeFields() throws Exception {
        var service=service();when(funds.readNetContributions(any())).thenReturn(new NetSnapshot(List.of(new NetCustomer(1L,EvidenceState.READY,List.of(),new BigDecimal("-12.000001"),BigDecimal.ZERO)),List.of(),List.of()));
        var rows=currencies(service.profile(1L));var usdt=rows.stream().filter(r->"USDT".equals(r.get("currency"))).findFirst().orElseThrow();
        assertThat(usdt).containsEntry("balance","77").containsEntry("creditedDepositTotal","11").containsEntry("netContributionTotal","-12.000001");
        assertThat(((Map<?,?>)usdt.get("fieldStatuses")).get("netContributionTotal")).isEqualTo("READY");
        assertThat(rows.stream().filter(r->"NEX".equals(r.get("currency"))).findFirst().orElseThrow()).containsEntry("netContributionTotal","0");
        verify(funds).readNetContributions(List.of(1L));
    }
    @Test void unknownNetHasIndependentNullStatusWhileOldBalancesStayAvailable() throws Exception {
        var service=service();when(funds.readNetContributions(any())).thenReturn(new NetSnapshot(List.of(new NetCustomer(1L,EvidenceState.UNKNOWN,List.of("SOURCE"),null,null)),List.of(),List.of()));
        var usdt=currencies(service.profile(1L)).stream().filter(r->"USDT".equals(r.get("currency"))).findFirst().orElseThrow();
        assertThat(usdt).containsEntry("balance","77").containsEntry("netContributionTotal",null);
        assertThat(((Map<?,?>)usdt.get("fieldStatuses")).get("netContributionTotal")).isEqualTo("UNKNOWN");
    }
    @Test void failedLegacyTotalsDoNotHideReadyNetOrInventOldAmounts() throws Exception {
        var service=service();when(finance.totals(1L)).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("legacy totals failed"));
        when(funds.readNetContributions(any())).thenReturn(new NetSnapshot(List.of(new NetCustomer(1L,EvidenceState.READY,List.of(),new BigDecimal("-12.000001"),BigDecimal.ZERO)),List.of(),List.of()));
        var rows=currencies(service.profile(1L));assertThat(rows).hasSize(2);
        for(var row:rows) {
            assertThat(row).containsEntry("balance",null).containsEntry("creditedDepositTotal",null);
            assertThat(((Map<?,?>)row.get("fieldStatuses")).get("balance")).isEqualTo("ERROR");
            assertThat(((Map<?,?>)row.get("fieldStatuses")).get("netContributionTotal")).isEqualTo("READY");
        }
        assertThat(rows.get(0)).containsEntry("netContributionTotal","-12.000001");verify(funds).readNetContributions(List.of(1L));
    }
    @Test void failedLegacyTotalsAndUnknownNetKeepAllAmountsUnavailable() throws Exception {
        var service=service();when(finance.totals(1L)).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("legacy totals failed"));
        when(funds.readNetContributions(any())).thenReturn(new NetSnapshot(List.of(new NetCustomer(1L,EvidenceState.UNKNOWN,List.of("SOURCE"),null,null)),List.of(),List.of()));
        for(var row:currencies(service.profile(1L))) {
            assertThat(row).containsEntry("balance",null).containsEntry("netContributionTotal",null);
            assertThat(((Map<?,?>)row.get("fieldStatuses")).get("balance")).isEqualTo("ERROR");
            assertThat(((Map<?,?>)row.get("fieldStatuses")).get("netContributionTotal")).isEqualTo("UNKNOWN");
        }
        verify(funds).readNetContributions(List.of(1L));
    }
    @Test void deniedPrivateCustomerCannotReadAnyFundSource() throws Exception {
        var service=service();when(ownership.customerQueryScope(1L)).thenThrow(new BizException(403,"SUPPORT_SCOPE_FORBIDDEN"));
        assertThatThrownBy(()->service.profile(1L)).isInstanceOf(BizException.class);verifyNoInteractions(funds,finance);
    }
}
