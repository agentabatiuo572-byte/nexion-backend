package ffdd.opsconsole.onboarding.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.growth.application.WheelSandboxProfile;
import ffdd.opsconsole.onboarding.mapper.OnboardingCalibrationMapper;
import ffdd.opsconsole.onboarding.mapper.OnboardingCalibrationMapper.CalibrationRow;
import ffdd.opsconsole.onboarding.mapper.OnboardingCalibrationMapper.ComparisonRow;
import ffdd.opsconsole.onboarding.mapper.OnboardingCalibrationMapper.TierRow;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.exception.BizException;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;

class OnboardingCalibrationSandboxServiceTest {
    private final OnboardingCalibrationMapper mapper = mock(OnboardingCalibrationMapper.class);
    private final WheelSandboxProfile profile = mock(WheelSandboxProfile.class);
    private final Environment environment = mock(Environment.class);
    private final OnboardingCalibrationService service = new OnboardingCalibrationService(mapper, profile, environment);

    @BeforeEach
    void sandboxScope() {
        when(environment.getActiveProfiles()).thenReturn(new String[] {"test"});
        when(profile.mode()).thenReturn(WheelSandboxProfile.Mode.SANDBOX);
        when(profile.requireRunId()).thenReturn("run-20260816");
        when(mapper.userSandbox(9L)).thenReturn(1);
        when(mapper.lockUserSandbox(9L)).thenReturn(1);
        when(mapper.activeTiers()).thenReturn(List.of(
                new TierRow(1, "T1", 1, 10, new BigDecimal("0.04"), new BigDecimal("6"), 7L),
                new TierRow(2, "T2", 11, 20, new BigDecimal("0.05"), new BigDecimal("8"), 7L),
                new TierRow(3, "T3", 21, 35, new BigDecimal("0.06"), new BigDecimal("10"), 7L),
                new TierRow(4, "T4", 36, 48, new BigDecimal("0.08"), new BigDecimal("13"), 7L),
                new TierRow(5, "T5", 49, 58, new BigDecimal("0.095"), new BigDecimal("16"), 7L)));
        when(mapper.activeComparisons()).thenReturn(List.of(
                new ComparisonRow("phone", "Phone", new BigDecimal("0.06"), new BigDecimal("10"), 1, 7L)));
    }

    @Test
    void sandboxUsesRunScopedPersistenceAndReturnsProvenance() {
        var request = new OnboardingCalibrationService.Request("device-sandbox", 0L, "sandbox-key-001",
                new OnboardingCalibrationService.Signals(8D, 8, "Pixel 8", "Google", "Mali-G715", 900D,
                        42D, 48, true, true));
        when(mapper.findForUpdateScoped(9L, "device-sandbox", "SANDBOX", "run-20260816")).thenReturn(null);
        when(mapper.insertScoped(any())).thenReturn(1);
        when(mapper.findScoped(9L, "device-sandbox", "SANDBOX", "run-20260816"))
                .thenReturn(new CalibrationRow(9L, "device-sandbox", "{}",
                        "{\"score\":87,\"tier\":3,\"tops\":28.3,\"baseRateUsdt\":0.06,\"baseRateNex\":10}",
                        "[{\"key\":\"phone\"}]", "server", true, 7L, 0L, "sandbox-key-001", "hash",
                        "SANDBOX", "run-20260816"));

        ApiResult<Map<String, Object>> result = service.calibrate(9L, request);

        assertThat(result.getCode()).isZero();
        assertThat(result.getData()).containsEntry("sourceEnvironment", "SANDBOX")
                .containsEntry("runId", "run-20260816");
        verify(mapper).insertScoped(any());
        verify(mapper, never()).insert(any());
    }

    @Test
    void sandboxWithoutServerRunIdFailsClosedBeforeAnyReadOrWrite() {
        when(profile.requireRunId()).thenThrow(new BizException(503, "WHEEL_SANDBOX_RUN_ID_REQUIRED"));
        var request = new OnboardingCalibrationService.Request("device-sandbox", 0L, "sandbox-key-001",
                new OnboardingCalibrationService.Signals(8D, 8, "Pixel 8", "Google", "Mali-G715", 900D,
                        42D, 48, true, true));

        assertThrows(BizException.class, () -> service.calibrate(9L, request));
        verify(mapper, never()).findForUpdateScoped(any(), any(), any(), any());
        verify(mapper, never()).insertScoped(any());
    }

    @Test
    void sandboxDeferBeforeCalibrationPersistsOnlyInsideCurrentRun() {
        CalibrationRow deferred = new CalibrationRow(9L, "device-sandbox", null, "{}", "{}", "[]",
                "server", true, 0L, 0L, "deferred:placeholder", "placeholder-hash",
                "DEFERRED", "sandbox-defer-001", "action-hash", "SANDBOX", "run-20260816");
        when(mapper.findForUpdateScoped(9L, "device-sandbox", "SANDBOX", "run-20260816")).thenReturn(null);
        when(mapper.insertDeferred(any())).thenReturn(1);
        when(mapper.findScoped(9L, "device-sandbox", "SANDBOX", "run-20260816")).thenReturn(deferred);

        ApiResult<Map<String, Object>> result = service.defer(9L,
                new OnboardingCalibrationService.ActionRequest("device-sandbox", 0L, "sandbox-defer-001"));

        assertThat(result.getCode()).isZero();
        assertThat(result.getData()).containsEntry("sourceEnvironment", "SANDBOX")
                .containsEntry("runId", "run-20260816")
                .containsEntry("activationStatus", "DEFERRED")
                .containsEntry("calibrationAvailable", false);
        verify(mapper).insertDeferred(argThat(row -> row.sourceEnvironment().equals("SANDBOX")
                && row.runId().equals("run-20260816")));
        verify(mapper, never()).insert(any());
    }

    @Test
    void sandboxRuntimeRejectsProductionAccountBeforeScopedCalibrationRead() {
        when(mapper.userSandbox(9L)).thenReturn(0);
        var request = new OnboardingCalibrationService.Request("device-prod", 0L, "sandbox-key-002",
                new OnboardingCalibrationService.Signals(8D, 8, "Pixel 8", "Google", "Mali-G715", 900D,
                        42D, 48, true, true));

        assertThrows(BizException.class, () -> service.calibrate(9L, request));
        verify(mapper, never()).findForUpdateScoped(any(), any(), any(), any());
        verify(mapper, never()).insertScoped(any());
    }
}
