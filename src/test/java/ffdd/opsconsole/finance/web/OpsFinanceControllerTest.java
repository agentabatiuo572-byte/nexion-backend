package ffdd.opsconsole.finance.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.api.PageResult;
import ffdd.opsconsole.finance.application.OpsFinanceService;
import ffdd.opsconsole.finance.domain.DepositFlowView;
import ffdd.opsconsole.finance.domain.WithdrawalOrderView;
import ffdd.opsconsole.finance.dto.TopupCommandRequest;
import ffdd.opsconsole.finance.dto.WithdrawalParamUpdateRequest;
import ffdd.opsconsole.finance.dto.WithdrawalQueryRequest;
import ffdd.opsconsole.finance.dto.WithdrawalReviewRequest;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;

class OpsFinanceControllerTest {
    private final OpsFinanceService financeService = mock(OpsFinanceService.class);
    private final OpsFinanceController controller = new OpsFinanceController(financeService);

    @Test
    void withdrawalParamsDelegatesToFinanceService() {
        when(financeService.withdrawalParams()).thenReturn(ApiResult.ok(Map.of("dailyLimitCount", 1)));

        assertThat(controller.withdrawalParams().getData()).containsEntry("dailyLimitCount", 1);

        verify(financeService).withdrawalParams();
    }

    @Test
    void topupOverviewDelegatesToFinanceService() {
        when(financeService.topupOverview()).thenReturn(ApiResult.ok(Map.of("ledgerCount", 2L)));

        assertThat(controller.topupOverview().getData()).containsEntry("ledgerCount", 2L);

        verify(financeService).topupOverview();
    }

    @Test
    void topupFlowsDelegatesPagingQueryToFinanceService() {
        PageResult<DepositFlowView> page = new PageResult<>(0, 1, 20, List.of());
        when(financeService.topupFlows("confirmed", 1001L, "TP", 1, 20)).thenReturn(ApiResult.ok(page));

        ApiResult<PageResult<DepositFlowView>> result = controller.topupFlows("confirmed", 1001L, "TP", 1, 20);

        assertThat(result.getData()).isSameAs(page);
        verify(financeService).topupFlows("confirmed", 1001L, "TP", 1, 20);
    }

    @Test
    void topupChannelStatusDelegatesWithIdempotencyHeader() {
        TopupCommandRequest request = new TopupCommandRequest(null, false, "pause", "superadmin");
        when(financeService.updateTopupChannelEnabled("trc20", "idem-topup", request))
                .thenReturn(ApiResult.ok(Map.of("ok", true)));

        assertThat(controller.updateTopupChannelEnabled("trc20", "idem-topup", request).getData()).containsEntry("ok", true);

        verify(financeService).updateTopupChannelEnabled("trc20", "idem-topup", request);
    }

    @Test
    void binLockEndpointAuthorizesLockAndUnlockDirectionsSeparately() throws Exception {
        PreAuthorize guard = OpsFinanceController.class
                .getMethod("setTopupBinLock", String.class, String.class, TopupCommandRequest.class)
                .getAnnotation(PreAuthorize.class);

        assertThat(guard.value())
                .contains("#request.enabled == true")
                .contains("finance_d1_bin_lock")
                .contains("#request.enabled == false")
                .contains("finance_d1_bin_unlock");
    }

    @Test
    void legacyWithdrawalParamPatchIsGoneAndCannotReachTheOldWriteService() {
        WithdrawalParamUpdateRequest request = new WithdrawalParamUpdateRequest("networkFee", "3", "tighten", "superadmin");

        var response = controller.updateWithdrawalParam("idem-param", request);

        assertThat(response.getStatusCode().value()).isEqualTo(410);
        assertThat(response.getBody())
                .containsEntry("code", "LEGACY_D5_WRITE_DISABLED")
                .containsEntry("redirect", "/api/admin/withdraw/limits");
        verifyNoInteractions(financeService);
    }

    @Test
    void withdrawalsDelegatesPagingQueryToFinanceService() {
        PageResult<WithdrawalOrderView> page = new PageResult<>(0, 2, 50, List.of());
        when(financeService.withdrawals(new WithdrawalQueryRequest("REVIEWING", 1001L, "WD", 2, 50, new BigDecimal("100"), new BigDecimal("5000"), null))).thenReturn(ApiResult.ok(page));

        ApiResult<PageResult<WithdrawalOrderView>> result = controller.withdrawals("REVIEWING", 1001L, "WD", 2, 50, new BigDecimal("100"), new BigDecimal("5000"), null);

        assertThat(result.getData()).isSameAs(page);
        verify(financeService).withdrawals(new WithdrawalQueryRequest("REVIEWING", 1001L, "WD", 2, 50, new BigDecimal("100"), new BigDecimal("5000"), null));
    }

    @Test
    void reviewDelegatesWithIdempotencyHeader() {
        WithdrawalReviewRequest request = new WithdrawalReviewRequest("APPROVE", "superadmin", "manual review");
        when(financeService.reviewWithdrawal("WD-1", "idem-review", request)).thenReturn(ApiResult.ok(mock(WithdrawalOrderView.class)));

        assertThat(controller.reviewWithdrawal("WD-1", "idem-review", request).getCode()).isZero();

        verify(financeService).reviewWithdrawal("WD-1", "idem-review", request);
    }
}
