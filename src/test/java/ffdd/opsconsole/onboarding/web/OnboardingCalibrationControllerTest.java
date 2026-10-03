package ffdd.opsconsole.onboarding.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import ffdd.opsconsole.onboarding.application.OnboardingCalibrationService;
import ffdd.opsconsole.onboarding.application.PhoneInstallationService;
import ffdd.opsconsole.onboarding.mapper.PhoneInstallationMapper;
import ffdd.opsconsole.shared.api.ApiResult;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

class OnboardingCalibrationControllerTest {
    private final OnboardingCalibrationService service = mock(OnboardingCalibrationService.class);
    private final PhoneInstallationMapper mapper = mock(PhoneInstallationMapper.class);
    private final OnboardingCalibrationController controller = new OnboardingCalibrationController(
            service, new PhoneInstallationService(mapper));

    @Test void ordinaryUserCanCalibrateActivateAndDeferWithoutAProofSession() {
        var user = auth("USER");
        var action = new OnboardingCalibrationService.ActionRequest("phone", 0, "action-key");
        var calibration = new OnboardingCalibrationService.Request("phone", 0, "calibrate-key", null);
        when(service.calibrate(42L, calibration)).thenReturn(ApiResult.ok(Map.of("status", "CALIBRATED")));
        when(service.activate(42L, action)).thenReturn(ApiResult.ok(Map.of("activationStatus", "ACTIVE")));
        when(service.defer(42L, action)).thenReturn(ApiResult.ok(Map.of("activationStatus", "DEFERRED")));
        assertThat(controller.calibrate(calibration, null, user).getCode()).isZero();
        assertThat(controller.activate(action, null, user).getCode()).isZero();
        assertThat(controller.defer(action, null, user).getCode()).isZero();
        verify(service).calibrate(42L, calibration);
        verify(service).activate(42L, action);
        verify(service).defer(42L, action);
        verifyNoInteractions(mapper);
    }

    @Test void anonymousAndAdminSubjectsCannotReachOnboardingWrites() {
        var request = new OnboardingCalibrationService.ActionRequest("phone", 0, "defer-key");
        for (var auth : new UsernamePasswordAuthenticationToken[] {null, auth("ADMIN")}) {
            assertThat(controller.calibrate(null, null, auth).getCode()).isEqualTo(403);
            assertThat(controller.activate(request, null, auth).getCode()).isEqualTo(403);
            assertThat(controller.defer(request, null, auth).getCode()).isEqualTo(403);
        }
        verifyNoInteractions(service, mapper);
    }

    @Test void invalidInstallationDoesNotReachCalibrationOrTransitions() {
        var request = new OnboardingCalibrationService.ActionRequest("bad/id", 0, "defer-key");
        var calibration = new OnboardingCalibrationService.Request("bad/id", 0, "calibrate-key", null);
        assertThatThrownBy(() -> controller.calibrate(calibration, null, auth("USER"))).hasMessage("ONBOARDING_DEVICE_INVALID");
        assertThatThrownBy(() -> controller.activate(request, null, auth("USER"))).hasMessage("ONBOARDING_DEVICE_INVALID");
        assertThatThrownBy(() -> controller.defer(request, null, auth("USER"))).hasMessage("ONBOARDING_DEVICE_INVALID");
        verifyNoInteractions(service, mapper);
    }

    @Test void deferPreservesTheNormalIdempotencyHeader() {
        var request = new OnboardingCalibrationService.ActionRequest("phone", 3, null);
        controller.defer(request, "defer-key", auth("USER"));
        verify(service).defer(42L, new OnboardingCalibrationService.ActionRequest("phone", 3, "defer-key"));
    }

    private UsernamePasswordAuthenticationToken auth(String subject) {
        var user = new UsernamePasswordAuthenticationToken("42", "", List.of());
        user.setDetails(Map.of("subjectType", subject));
        return user;
    }
}
