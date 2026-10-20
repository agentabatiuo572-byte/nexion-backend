package ffdd.opsconsole.onboarding.application;

import ffdd.opsconsole.common.boundary.PhoneReplacementContract.*;
import ffdd.opsconsole.common.boundary.PhoneReplacementSupportContract;
import ffdd.opsconsole.growth.application.WheelSandboxProfile;
import ffdd.opsconsole.onboarding.mapper.OnboardingCalibrationMapper;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.security.CurrentAppSessionService;
import ffdd.opsconsole.shared.security.infrastructure.UserSessionEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.core.Authentication;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AppPhoneReplacementServiceTest {
    private final PhoneReplacementService replacements = mock(PhoneReplacementService.class);
    private final CurrentAppSessionService sessions = mock(CurrentAppSessionService.class);
    private final OnboardingCalibrationMapper calibration = mock(OnboardingCalibrationMapper.class);
    private final PhoneReplacementSupportContract support = mock(PhoneReplacementSupportContract.class);
    private final OnboardingCalibrationService onboarding = mock(OnboardingCalibrationService.class);
    private final Authentication authentication = mock(Authentication.class);
    private final MockEnvironment environment = new MockEnvironment();
    private final UserSessionEntity session = new UserSessionEntity();
    private AppPhoneReplacementService service;

    @BeforeEach void setup() {
        environment.setActiveProfiles("dev");
        service = new AppPhoneReplacementService(replacements, sessions, calibration,
                new WheelSandboxProfile(environment), environment, support, onboarding);
        session.setUserId(17L);
        session.setSessionGroup("APP");
        session.setInstallationId("persisted-installation-B");
        session.setExecutionSupported(true);
        when(sessions.requireSession(authentication)).thenReturn(session);
        when(calibration.userSandbox(17L)).thenReturn(0);
    }

    @Test void requestUsesPersistedInstallationAndLocksPolicyBeforeAuthentication() {
        service.createRequest(authentication, new AppPhoneReplacementService.RequestInput(3L, "请协助核实本次手机更换"), "original-key");
        var order = inOrder(replacements, sessions);
        order.verify(replacements).lockPolicy();
        order.verify(sessions).requireSession(authentication);
        order.verify(sessions).requireCurrent(authentication, "persisted-installation-B", false);
        verify(replacements).createRequest(isNull(), eq("persisted-installation-B"), eq(3L), eq("请协助核实本次手机更换"), isNull());
    }

    @Test void h5CanReadButCannotCreateAReplacementRequest() {
        session.setSessionGroup("H5");
        session.setInstallationId(null);
        session.setExecutionSupported(false);
        service.eligibility(authentication);
        verify(replacements).eligibility(eq(Account.production(17)), isNull(), isNull(), eq(false), anyList());
        verifyNoInteractions(onboarding);
        doThrow(new BizException(403, "APP_SESSION_REQUIRED")).when(sessions).requireCurrent(authentication, null, false);
        assertThatThrownBy(() -> service.createRequest(authentication,
                new AppPhoneReplacementService.RequestInput(3L, "请协助核实本次手机更换"), "key"))
                .isInstanceOf(BizException.class).hasMessage("APP_SESSION_REQUIRED");
        verify(replacements, never()).openOperation(any(), any(), any(), any(), any());
    }

    @Test void supportAssociationCannotLinkAnotherAccountsConversation() {
        doThrow(new BizException(403, "PERMISSION_DENIED")).when(support).requireConversationOwner(17L, "foreign-conversation");
        assertThatThrownBy(() -> service.linkSupport(authentication, "request-17",
                new AppPhoneReplacementService.SupportInput("foreign-conversation"), "link-key"))
                .hasMessage("PERMISSION_DENIED");
        verify(replacements, never()).linkSupport(any(), any(), any(), anyLong(), any());
    }

    @Test void operationReadUsesOnlyUserOwnedReceipts() {
        service.operation(authentication, "operation-id");
        verify(replacements).userOperation(Account.production(17), "operation-id");
        verify(replacements, never()).operation(any(), any());
    }

    @Test void eligibilityIncludesCurrentInstallationsCalibrationAndTermsBlocks() {
        when(onboarding.activationBlockReasons(17L, "persisted-installation-B"))
                .thenReturn(java.util.List.of("ONBOARDING_CALIBRATION_REQUIRED", "USER_TERMS_REQUIRED"));
        service.eligibility(authentication);
        verify(replacements).eligibility(Account.production(17), "persisted-installation-B", null, true,
                java.util.List.of("ONBOARDING_CALIBRATION_REQUIRED", "USER_TERMS_REQUIRED"));
    }

    @Test void accountEnvironmentMismatchRejectsBeforeReplacementQuery() {
        when(calibration.userSandbox(17L)).thenReturn(1);
        assertThatThrownBy(() -> service.currentRequest(authentication)).hasMessage("ONBOARDING_USER_ENVIRONMENT_MISMATCH");
        verify(replacements, never()).currentRequest(any(), any());
    }
    @Test void eligibilityUsesOnlyPersistedCurrentInstallationHardwareAndRetainsOperationalPause() {
        var row=mock(OnboardingCalibrationMapper.CalibrationRow.class);
        when(calibration.find(17L,"persisted-installation-B")).thenReturn(row);
        when(onboarding.hardwareKey(row)).thenReturn("server-hardware");
        when(calibration.phoneBinding(17L,"PRODUCTION",""))
                .thenReturn(new OnboardingCalibrationMapper.PhoneBindingRow("persisted-installation-B",9L,"server-hardware",1,2));
        when(calibration.phoneReplacementHardPause(17L,9L)).thenReturn("OPERATOR_PAUSE");
        service.eligibility(authentication);
        verify(replacements).eligibility(Account.production(17),"persisted-installation-B","server-hardware",true,
                java.util.List.of("PHONE_REPLACEMENT_RISK_PAUSED"));
    }
    @Test void missingCalibrationDoesNotSubstituteOldBindingHardwareForCurrentHardware() {
        when(calibration.phoneBinding(17L,"PRODUCTION",""))
                .thenReturn(new OnboardingCalibrationMapper.PhoneBindingRow("persisted-installation-B",9L,"old-hardware",1,2));
        when(onboarding.activationBlockReasons(17L,"persisted-installation-B"))
                .thenReturn(java.util.List.of("ONBOARDING_CALIBRATION_REQUIRED"));
        service.eligibility(authentication);
        verify(onboarding,never()).hardwareKey(any());
        verify(replacements).eligibility(Account.production(17),"persisted-installation-B",null,true,
                java.util.List.of("ONBOARDING_CALIBRATION_REQUIRED"));
    }

}
