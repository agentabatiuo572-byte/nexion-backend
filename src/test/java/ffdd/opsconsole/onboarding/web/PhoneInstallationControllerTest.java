package ffdd.opsconsole.onboarding.web;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import ffdd.opsconsole.onboarding.application.OnboardingCalibrationService;
import ffdd.opsconsole.onboarding.application.PhoneInstallationService;
import ffdd.opsconsole.onboarding.mapper.PhoneInstallationMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class PhoneInstallationControllerTest {
    private final PhoneInstallationMapper mapper = mock(PhoneInstallationMapper.class);
    private final OnboardingCalibrationService calibration = mock(OnboardingCalibrationService.class);
    private final PhoneInstallationController controller = new PhoneInstallationController(
            new PhoneInstallationService(mapper), calibration);

    @Test void loginUsesTheAuthenticatedUserAndInstallationWithoutAProofSession() {
        var user = new UsernamePasswordAuthenticationToken("42", "", List.of());
        user.setDetails(Map.of("subjectType", "USER"));
        when(calibration.phoneLogin(42L, "phone-a")).thenReturn(Map.of("status", "NEEDS_CALIBRATION"));
        assertThat(controller.login(new PhoneInstallationController.InstallationRequest("phone-a"), user).getData())
                .containsEntry("status", "NEEDS_CALIBRATION");
        verify(calibration).phoneLogin(42L, "phone-a");
        verifyNoInteractions(mapper);
    }

    @Test void invalidLoginCannotChangeExecutionOwnership() {
        var user = new UsernamePasswordAuthenticationToken("42", "", List.of());
        user.setDetails(Map.of("subjectType", "USER"));
        assertThatThrownBy(() -> controller.login(null, user)).hasMessage("ONBOARDING_DEVICE_INVALID");
        assertThatThrownBy(() -> controller.login(new PhoneInstallationController.InstallationRequest("bad/id"), user))
                .hasMessage("ONBOARDING_DEVICE_INVALID");
        user.setDetails(Map.of("subjectType", "ADMIN"));
        assertThatThrownBy(() -> controller.login(new PhoneInstallationController.InstallationRequest("phone-a"), user))
                .hasMessage("USER_AUTH_REQUIRED");
        verifyNoInteractions(calibration, mapper);
    }

    @Test void retiredProofEndpointsReturnHttp410AndNeverReadOrWriteBusinessState() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(controller).build();
        for (String path : List.of("challenge", "verify")) {
            mvc.perform(post("/api/onboarding/phone-installation/" + path)
                            .contentType("application/json").content("{\"deviceId\":\"phone-a\",\"signature\":\"ignored\"}"))
                    .andExpect(status().isGone()).andExpect(jsonPath("$.code").value(410))
                    .andExpect(jsonPath("$.message").value("PHONE_NATIVE_PROOF_RETIRED"));
            mvc.perform(post("/api/onboarding/phone-installation/" + path))
                    .andExpect(status().isGone()).andExpect(jsonPath("$.code").value(410));
        }
        verifyNoInteractions(calibration, mapper);
    }
}
