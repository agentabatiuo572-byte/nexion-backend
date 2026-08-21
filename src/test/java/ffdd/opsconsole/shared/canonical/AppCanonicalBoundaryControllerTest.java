package ffdd.opsconsole.shared.canonical;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.growth.application.AppTrialLifecycleService;
import ffdd.opsconsole.commerce.application.CommerceSandboxTrialService;
import ffdd.opsconsole.finance.application.FundsSandboxProfileGuard;
import ffdd.opsconsole.shared.api.ApiResult;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

class AppCanonicalBoundaryControllerTest {
    private final AppCanonicalBoundaryService service = mock(AppCanonicalBoundaryService.class);
    private final AppTrialLifecycleService trialLifecycleService = mock(AppTrialLifecycleService.class);
    private final AppBundleOrderService bundleOrderService = mock(AppBundleOrderService.class);
    private final CommerceSandboxTrialService sandboxTrialService = mock(CommerceSandboxTrialService.class);
    private final FundsSandboxProfileGuard profileGuard = mock(FundsSandboxProfileGuard.class);
    private final AppCanonicalBoundaryController controller =
            new AppCanonicalBoundaryController(service, trialLifecycleService, bundleOrderService, sandboxTrialService, profileGuard);

    @BeforeEach
    void strictProductionByDefault() {
        when(profileGuard.isStrictProductionRuntime()).thenReturn(true);
    }

    @Test
    void productionDevFlagLiteralOneIsForwardedAsTamperAttempt() {
        UsernamePasswordAuthenticationToken user = auth("42", "USER");
        when(service.productPhase(42L, null, true)).thenReturn(ApiResult.fail(409, "PRODUCT_PHASE_OVERRIDE_REJECTED"));

        ApiResult<Map<String, Object>> result = controller.productPhase(null, "1", user);

        assertThat(result.getCode()).isEqualTo(409);
        verify(service).productPhase(42L, null, true);
    }

    @Test
    void adminTokenCannotCallUserCanonicalBoundaries() {
        UsernamePasswordAuthenticationToken admin = auth("7", "ADMIN");

        ApiResult<Map<String, Object>> result = controller.trialEligibility("CLAIMED", admin);

        assertThat(result.getCode()).isEqualTo(403);
        assertThat(result.getMessage()).isEqualTo("USER_SUBJECT_REQUIRED");
        verify(trialLifecycleService, never()).state(42L);
    }

    @Test
    void orderHistoryUsesAuthenticatedUserSubjectOnly() {
        UsernamePasswordAuthenticationToken user = auth("42", "USER");
        when(service.orders(42L)).thenReturn(ApiResult.ok(Map.of("orders", java.util.List.of())));

        ApiResult<Map<String, Object>> result = controller.orders(user);

        assertThat(result.getCode()).isZero();
        verify(service).orders(42L);
    }

    @Test
    void purchaseEligibilityUsesAuthenticatedUserAndRequestedProduct() {
        UsernamePasswordAuthenticationToken user = auth("42", "USER");
        when(service.purchaseEligibility(42L, "stellarbox-pro-v2"))
                .thenReturn(ApiResult.ok(Map.of("productNo", "stellarbox-pro-v2", "eligible", false)));

        ApiResult<Map<String, Object>> result = controller.purchaseEligibility("stellarbox-pro-v2", user);

        assertThat(result.getCode()).isZero();
        assertThat(result.getData()).containsEntry("eligible", false);
        verify(service).purchaseEligibility(42L, "stellarbox-pro-v2");
    }

    @Test
    void userDeviceDeactivateUsesSubjectPathVersionAndIdempotencyKey() {
        UsernamePasswordAuthenticationToken user = auth("42", "USER");
        when(service.deactivateDevice(42L, 9L, 7L, "device-deactivate-key"))
                .thenReturn(ApiResult.ok(Map.of("deviceId", 9L, "status", "DEACTIVATED")));

        ApiResult<Map<String, Object>> result = controller.deactivateDevice(
                9L, new AppCanonicalBoundaryController.DeviceDeactivateRequest(7L),
                "device-deactivate-key", user);

        assertThat(result.getCode()).isZero();
        verify(service).deactivateDevice(42L, 9L, 7L, "device-deactivate-key");
    }

    @Test
    void userDeviceActivateUsesSubjectCasVersionAndIdempotencyKey() {
        UsernamePasswordAuthenticationToken user = auth("42", "USER");
        when(service.activateDevice(42L, 9L, 7L, 3, "device-activate-key"))
                .thenReturn(ApiResult.ok(Map.of("deviceId", 9L, "status", "ACTIVE")));

        ApiResult<Map<String, Object>> result = controller.activateDevice(
                new AppCanonicalBoundaryController.DeviceActivateRequest(9L, 7L, 3),
                "device-activate-key", user);

        assertThat(result.getCode()).isZero();
        verify(service).activateDevice(42L, 9L, 7L, 3, "device-activate-key");
    }

    @Test
    void trialStateMismatchPublishesTheJ3CanonicalRejection() {
        UsernamePasswordAuthenticationToken user = auth("42", "USER");
        when(trialLifecycleService.state(42L))
                .thenReturn(ApiResult.ok(Map.of("state", "CLAIMED")));
        when(service.rejectTrialStateTamper(42L))
                .thenReturn(ApiResult.fail(409, "TRIAL_STATE_CONFLICT"));

        ApiResult<Map<String, Object>> result = controller.trialEligibility("ELIGIBLE", user);

        assertThat(result.getCode()).isEqualTo(409);
        assertThat(result.getMessage()).isEqualTo("TRIAL_STATE_CONFLICT");
        verify(service).rejectTrialStateTamper(42L);
    }

    @Test
    void localSandboxEligibilityUsesTheRunScopedTrialAuthority() {
        UsernamePasswordAuthenticationToken user = auth("42", "USER");
        when(sandboxTrialService.enabled()).thenReturn(true);
        when(sandboxTrialService.state(42L))
                .thenReturn(ApiResult.ok(Map.of("state", "ELIGIBLE", "sourceEnvironment", "SANDBOX")));

        ApiResult<Map<String, Object>> result = controller.trialEligibility(null, user);

        assertThat(result.getCode()).isZero();
        assertThat(result.getData()).containsEntry("sourceEnvironment", "SANDBOX");
        verify(sandboxTrialService).state(42L);
        verify(trialLifecycleService, never()).state(42L);
    }

    @Test
    void mixedRuntimeRejectsTrialReadAndChargeBeforeCanonicalLifecycleCalls() {
        UsernamePasswordAuthenticationToken user = auth("42", "USER");
        when(profileGuard.isStrictProductionRuntime()).thenReturn(false);

        ApiResult<Map<String, Object>> eligibility = controller.trialEligibility(null, user);
        ApiResult<Map<String, Object>> charge = controller.chargeTrial(null, "mixed-trial-key", user);

        assertThat(eligibility.getCode()).isEqualTo(503);
        assertThat(eligibility.getMessage()).isEqualTo("TRIAL_RUNTIME_UNAVAILABLE");
        assertThat(charge.getCode()).isEqualTo(503);
        assertThat(charge.getMessage()).isEqualTo("TRIAL_RUNTIME_UNAVAILABLE");
        verify(trialLifecycleService, never()).state(42L);
        verify(trialLifecycleService, never()).charge(42L, "mixed-trial-key");
        verify(service, never()).chargeTrial(42L, null, null, "mixed-trial-key");
    }

    @Test
    void sandboxOldChargeEntryHoldsWithoutCallingCanonicalTrialLifecycle() {
        UsernamePasswordAuthenticationToken user = auth("42", "USER");
        when(sandboxTrialService.enabled()).thenReturn(true);

        ApiResult<Map<String, Object>> result = controller.chargeTrial(null, "sandbox-old-charge", user);

        assertThat(result.getCode()).isEqualTo(503);
        assertThat(result.getMessage()).isEqualTo("TRIAL_CHARGE_SANDBOX_UNAVAILABLE");
        verify(trialLifecycleService, never()).charge(42L, "sandbox-old-charge");
        verify(service, never()).chargeTrial(42L, null, null, "sandbox-old-charge");
    }

    @Test
    void clientOwnedTrialChargeOutcomePublishesTheJ3CanonicalRejection() {
        UsernamePasswordAuthenticationToken user = auth("42", "USER");
        when(service.chargeTrial(42L, true, java.math.BigDecimal.ONE, "j3-charge-probe"))
                .thenReturn(ApiResult.fail(409, "CLIENT_CHARGE_OUTCOME_REJECTED"));

        ApiResult<Map<String, Object>> result = controller.chargeTrial(
                new AppCanonicalBoundaryController.TrialChargeRequest(true, java.math.BigDecimal.ONE),
                "j3-charge-probe", user);

        assertThat(result.getCode()).isEqualTo(409);
        assertThat(result.getMessage()).isEqualTo("CLIENT_CHARGE_OUTCOME_REJECTED");
        verify(service).chargeTrial(42L, true, java.math.BigDecimal.ONE, "j3-charge-probe");
        verify(trialLifecycleService, never()).charge(42L, "j3-charge-probe");
    }

    private UsernamePasswordAuthenticationToken auth(String id, String subjectType) {
        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(id, null, java.util.List.of());
        authentication.setDetails(Map.of("subjectType", subjectType));
        return authentication;
    }
}
