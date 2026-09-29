package ffdd.opsconsole.onboarding.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import ffdd.opsconsole.onboarding.application.OnboardingCalibrationService;
import ffdd.opsconsole.onboarding.application.PhoneNativeSessionService;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.exception.BizException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

class OnboardingCalibrationControllerTest {
    private final OnboardingCalibrationService service = mock(OnboardingCalibrationService.class);
    private final PhoneNativeSessionService nativeSessions = mock(PhoneNativeSessionService.class);
    private final OnboardingCalibrationController controller = new OnboardingCalibrationController(service, nativeSessions);

    @Test void deferNeedsAccountButNotNativeProofWhileActivationStillRequiresProof() {
        var user = new UsernamePasswordAuthenticationToken("42", "", List.of());
        user.setDetails(Map.of("subjectType", "USER"));
        var request = new OnboardingCalibrationService.ActionRequest("phone", 0, "defer-key");

        assertThat(controller.defer(request, null, null).getCode()).isEqualTo(403);
        verifyNoInteractions(service, nativeSessions);

        when(service.defer(42L, request)).thenReturn(ApiResult.ok(Map.of("activationStatus", "DEFERRED")));
        assertThat(controller.defer(request, null, user).getData().get("activationStatus")).isEqualTo("DEFERRED");
        verify(service).defer(42L, request);
        verifyNoInteractions(nativeSessions);

        doThrow(new BizException(403, "PHONE_NATIVE_SESSION_REQUIRED")).when(nativeSessions).require(user, "phone");
        assertThatThrownBy(() -> controller.activate(request, null, user))
                .isInstanceOf(BizException.class).hasMessageContaining("PHONE_NATIVE_SESSION_REQUIRED");
        verify(nativeSessions).require(user, "phone");
        verify(service, never()).activate(anyLong(), any());
    }
}
