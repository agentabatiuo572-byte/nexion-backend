package ffdd.opsconsole.finance.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.finance.application.OpsFinanceService;
import ffdd.opsconsole.finance.domain.WithdrawalOrderView;
import ffdd.opsconsole.finance.dto.WithdrawalParamUpdateRequest;
import ffdd.opsconsole.finance.dto.WithdrawalReviewRequest;
import java.util.Map;
import org.junit.jupiter.api.Test;

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
    void updateWithdrawalParamDelegatesWithIdempotencyHeader() {
        WithdrawalParamUpdateRequest request = new WithdrawalParamUpdateRequest("networkFee", "3", "tighten", "superadmin");
        when(financeService.updateWithdrawalParam("idem-param", request)).thenReturn(ApiResult.ok(Map.of("ok", true)));

        assertThat(controller.updateWithdrawalParam("idem-param", request).getData()).containsEntry("ok", true);

        verify(financeService).updateWithdrawalParam("idem-param", request);
    }

    @Test
    void reviewDelegatesWithIdempotencyHeader() {
        WithdrawalReviewRequest request = new WithdrawalReviewRequest("APPROVE", "superadmin", "manual review");
        when(financeService.reviewWithdrawal("WD-1", "idem-review", request)).thenReturn(ApiResult.ok(mock(WithdrawalOrderView.class)));

        assertThat(controller.reviewWithdrawal("WD-1", "idem-review", request).getCode()).isZero();

        verify(financeService).reviewWithdrawal("WD-1", "idem-review", request);
    }
}
