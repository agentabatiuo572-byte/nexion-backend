package ffdd.opsconsole.onboarding.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.onboarding.mapper.OnboardingCalibrationMapper;
import ffdd.opsconsole.onboarding.mapper.OnboardingCalibrationMapper.CalibrationRow;
import ffdd.opsconsole.onboarding.mapper.OnboardingCalibrationMapper.ComparisonRow;
import ffdd.opsconsole.onboarding.mapper.OnboardingCalibrationMapper.TierRow;
import ffdd.opsconsole.shared.api.ApiResult;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;

class OnboardingCalibrationServiceTest {
    private final OnboardingCalibrationMapper mapper = mock(OnboardingCalibrationMapper.class);
    private final Environment environment = mock(Environment.class);
    private final OnboardingCalibrationService service = new OnboardingCalibrationService(mapper, null, environment);

    @BeforeEach
    void config() {
        when(environment.getActiveProfiles()).thenReturn(new String[] {"prod"});
        when(mapper.userSandbox(9L)).thenReturn(0);
        when(mapper.userSandbox(10L)).thenReturn(0);
        when(mapper.lockUserSandbox(9L)).thenReturn(0);
        when(mapper.lockUserSandbox(10L)).thenReturn(0);
        when(mapper.activeTiers()).thenReturn(List.of(
                tier(1, 1, 10, "0.040000", "6.000000"),
                tier(2, 11, 20, "0.050000", "8.000000"),
                tier(3, 21, 35, "0.060000", "10.000000"),
                tier(4, 36, 48, "0.080000", "13.000000"),
                tier(5, 49, 58, "0.095000", "16.000000")));
        when(mapper.activeComparisons()).thenReturn(List.of(
                new ComparisonRow("phone", "手机", new BigDecimal("0.060000"), new BigDecimal("10.000000"), 1, 7L),
                new ComparisonRow("s1", "S1", new BigDecimal("1.200000"), new BigDecimal("65.000000"), 2, 7L)));
    }

    @Test
    void developmentAcceptsTheAuthenticatedSandboxAccountButReturnsProductionShapedFacts() {
        when(environment.getActiveProfiles()).thenReturn(new String[] {"dev"});
        when(mapper.userSandbox(9L)).thenReturn(1);
        var request = request("dev-sandbox-account", 0L, "key-dev-001", 12, 8,
                "Pixel 8", "Google", "Mali-G715", 900, 48, true);
        when(mapper.findForUpdate(9L, "dev-sandbox-account")).thenReturn(null);
        when(mapper.insert(any())).thenReturn(1);
        when(mapper.find(9L, "dev-sandbox-account"))
                .thenReturn(row(9L, "dev-sandbox-account", 1L, "key-dev-001", "hash", 7L));

        ApiResult<Map<String, Object>> result = service.calibrate(9L, request);

        assertThat(result.getCode()).isZero();
        assertThat(result.getData()).containsEntry("sourceEnvironment", "PRODUCTION")
                .containsEntry("runId", "");
    }

    @Test
    void serverDerivesCapabilityAndReturnsCanonicalProvenance() {
        OnboardingCalibrationService.Request request = request("dev-a", 0L, "key-a-001", 12, 8, "Pixel 8", "Google", "Mali-G715", 900, 48, true);
        when(mapper.findForUpdate(9L, "dev-a")).thenReturn(null);
        when(mapper.insert(any())).thenReturn(1);
        when(mapper.find(9L, "dev-a")).thenReturn(row(9L, "dev-a", 1L, "key-a-001", "hash", 7L));

        ApiResult<Map<String, Object>> result = service.calibrate(9L, request);

        assertThat(result.getCode()).isZero();
        assertThat(result.getData()).containsEntry("userId", 9L)
                .containsEntry("deviceId", "dev-a")
                .containsEntry("serverCanonical", true)
                .containsEntry("source", "server")
                .containsEntry("sourceEnvironment", "PRODUCTION")
                .containsEntry("runId", "")
                .containsEntry("revision", 1L);
        assertThat(result.getData().get("score")).isInstanceOf(Integer.class);
        assertThat(result.getData().get("tops")).isInstanceOf(Number.class);
        assertThat(result.getData().get("tier")).isInstanceOf(Integer.class);
        assertThat(((Number) result.getData().get("baseRateUsdt")).doubleValue()).isEqualTo(0.06);
        verify(mapper).insert(any());
    }

    @Test
    void rejectsClientSuppliedFinalFactsAndOutOfRangeSignals() {
        var request = request("dev-a", 0L, "key-a-001", 999, 8, "Pixel", "Google", "GPU", 900, 48, true);

        ApiResult<Map<String, Object>> result = service.calibrate(9L, request);

        assertThat(result.getCode()).isEqualTo(422);
        assertThat(result.getMessage()).isEqualTo("ONBOARDING_SIGNAL_INVALID");
        verify(mapper, never()).insert(any());
    }

    @Test
    void preservesUnavailableRawObservationsAsNullAndStillDerivesConservatively() {
        var signals = new OnboardingCalibrationService.Signals(
                null, null, "Web", "", "", null, null, null, null, null);
        var request = new OnboardingCalibrationService.Request("dev-unknown", 0L, "key-unknown-001", signals);
        when(mapper.findForUpdate(9L, "dev-unknown")).thenReturn(null);
        when(mapper.insert(any())).thenReturn(1);
        String signalJson = "{\"memGB\":null,\"cores\":null,\"model\":\"Web\",\"brand\":\"\",\"gpu\":\"\",\"pxDensity\":null,\"pingMs\":null,\"batteryLevel\":null,\"charging\":null,\"networkReachable\":null}";
        when(mapper.find(9L, "dev-unknown")).thenReturn(new CalibrationRow(
                9L, "dev-unknown", signalJson,
                "{\"score\":70,\"tier\":1,\"tierName\":\"T1\",\"tops\":11,\"baseRateUsdt\":0.040000,\"baseRateNex\":6}",
                "[{\"key\":\"phone\"}]", "server", true, 7L, 1L, "key-unknown-001", "hash"));

        ApiResult<Map<String, Object>> result = service.calibrate(9L, request);

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().get("signals")).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> rawSignals = (Map<String, Object>) result.getData().get("signals");
        assertThat(rawSignals)
                .containsEntry("memGB", null)
                .containsEntry("pingMs", null)
                .containsEntry("batteryLevel", null);
    }

    @Test
    void sameIdempotencyKeyReplaysOnlyMatchingRequestAndCannotCrossAccount() {
        var request = request("dev-a", 3L, "key-a-001", 8, 8, "Pixel", "Google", "GPU", 900, 48, true);
        when(mapper.findForUpdate(9L, "dev-a")).thenReturn(row(9L, "dev-a", 3L, "key-a-001", service.requestHash(9L, request), 7L));

        ApiResult<Map<String, Object>> replay = service.calibrate(9L, request);

        assertThat(replay.getCode()).isZero();
        verify(mapper, never()).update(any(), any(), any(), any(), any(), any(), any(), any(), any());
        assertThat(service.calibrate(10L, request).getCode()).isEqualTo(409);
    }

    @Test
    void staleRevisionFailsCasBeforeWrite() {
        var request = request("dev-a", 1L, "key-b-001", 8, 8, "Pixel", "Google", "GPU", 900, 48, true);
        when(mapper.findForUpdate(9L, "dev-a")).thenReturn(row(9L, "dev-a", 2L, "old", "old-hash", 7L));

        ApiResult<Map<String, Object>> result = service.calibrate(9L, request);

        assertThat(result.getCode()).isEqualTo(409);
        assertThat(result.getMessage()).isEqualTo("ONBOARDING_CALIBRATION_REVISION_CONFLICT");
        verify(mapper, never()).update(any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void simultaneousFirstCalibrationConvergesToTheCommittedIdempotentResult() {
        var request = request("dev-race", 0L, "key-race-001", 8, 8, "Pixel", "Google", "GPU", 900, 48, true);
        String hash = service.requestHash(9L, request);
        when(mapper.findForUpdate(9L, "dev-race")).thenReturn(null);
        // The first transaction wins the unique (user, device) insert. MySQL's
        // no-op duplicate branch returns 0 after waiting for that row to commit.
        when(mapper.insert(any())).thenReturn(0);
        when(mapper.find(9L, "dev-race")).thenReturn(row(9L, "dev-race", 0L, "key-race-001", hash, 7L));

        ApiResult<Map<String, Object>> result = service.calibrate(9L, request);

        assertThat(result.getCode()).isZero();
        assertThat(result.getData()).containsEntry("deviceId", "dev-race").containsEntry("revision", 0L);
    }

    @Test
    void productionRuntimeRejectsSandboxAccountBeforeCanonicalCalibrationRead() {
        when(mapper.userSandbox(9L)).thenReturn(1);
        var request = request("dev-prod", 0L, "key-prod-001", 8, 8, "Pixel", "Google", "GPU", 900, 48, true);

        assertThatThrownBy(() -> service.calibrate(9L, request))
                .hasMessageContaining("ONBOARDING_USER_ENVIRONMENT_MISMATCH");
        verify(mapper, never()).findForUpdate(any(), any());
        verify(mapper, never()).insert(any());
    }

    @Test
    void activationBindsCanonicalPhoneBeforePublishingActiveState() {
        CalibrationRow calibrated = actionRow(9L, "dev-phone", null, 3L, "CALIBRATED", null, null);
        CalibrationRow active = actionRow(9L, "dev-phone", 44L, 4L, "ACTIVE", "phone-active-001", "hash");
        when(mapper.findForUpdate(9L, "dev-phone")).thenReturn(calibrated);
        when(mapper.upsertPhoneDevice(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(1);
        when(mapper.phoneDeviceId(eq(9L), any(), eq("PRODUCTION"), eq(""))).thenReturn(44L);
        when(mapper.updateActivation(eq(9L), eq("dev-phone"), eq(3L), eq(44L), eq("ACTIVE"),
                eq("phone-active-001"), any())).thenReturn(1);
        when(mapper.find(9L, "dev-phone")).thenReturn(active);

        ApiResult<Map<String, Object>> result = service.activate(9L,
                new OnboardingCalibrationService.ActionRequest("dev-phone", 3L, "phone-active-001"));

        assertThat(result.getCode()).isZero();
        assertThat(result.getData()).containsEntry("activationStatus", "ACTIVE");
        verify(mapper).upsertPhoneDevice(eq(9L), any(), eq("TIER-3"), any(), eq(8),
                eq(new BigDecimal("28.3")),
                argThat(value -> value.compareTo(new BigDecimal("0.060000")) == 0),
                eq(new BigDecimal("10")), eq("PRODUCTION"), eq(""));
        verify(mapper).deactivateOtherPhoneDevices(9L, 44L, "PRODUCTION", "");
        verify(mapper).deferOtherPhoneCalibrations(9L, 44L, "PRODUCTION", "");
    }

    @Test
    void deferDeactivatesBoundPhoneBeforePublishingDeferredState() {
        CalibrationRow active = actionRow(9L, "dev-phone", 44L, 4L, "ACTIVE", null, null);
        CalibrationRow deferred = actionRow(9L, "dev-phone", 44L, 5L, "DEFERRED", "phone-defer-001", "hash");
        when(mapper.findForUpdate(9L, "dev-phone")).thenReturn(active);
        when(mapper.updateActivation(eq(9L), eq("dev-phone"), eq(4L), eq(44L), eq("DEFERRED"),
                eq("phone-defer-001"), any())).thenReturn(1);
        when(mapper.find(9L, "dev-phone")).thenReturn(deferred);

        ApiResult<Map<String, Object>> result = service.defer(9L,
                new OnboardingCalibrationService.ActionRequest("dev-phone", 4L, "phone-defer-001"));

        assertThat(result.getCode()).isZero();
        assertThat(result.getData()).containsEntry("activationStatus", "DEFERRED");
        verify(mapper).deactivateScopedPhoneDevices(9L, "PRODUCTION", "");
        verify(mapper, never()).upsertPhoneDevice(any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void deferBeforeFirstCalibrationPersistsCanonicalTombstoneWithoutInventingCapability() {
        CalibrationRow deferred = new CalibrationRow(9L, "dev-new", null, "{}", "{}", "[]",
                "server", true, 0L, 0L, "deferred:placeholder", "placeholder-hash",
                "DEFERRED", "phone-defer-new", "action-hash", "PRODUCTION", "");
        when(mapper.findForUpdate(9L, "dev-new")).thenReturn(null);
        when(mapper.insertDeferred(any())).thenReturn(1);
        when(mapper.find(9L, "dev-new")).thenReturn(deferred);

        ApiResult<Map<String, Object>> result = service.defer(9L,
                new OnboardingCalibrationService.ActionRequest("dev-new", 0L, "phone-defer-new"));

        assertThat(result.getCode()).isZero();
        assertThat(result.getData())
                .containsEntry("activationStatus", "DEFERRED")
                .containsEntry("calibrationAvailable", false)
                .containsEntry("configRevision", 0L)
                .containsEntry("score", null)
                .containsEntry("signals", null)
                .containsEntry("comparisonConfig", List.of());
        verify(mapper).deactivateScopedPhoneDevices(9L, "PRODUCTION", "");
        verify(mapper).insertDeferred(argThat(row -> row.userId().equals(9L)
                && row.deviceId().equals("dev-new")
                && row.activationIdempotencyKey().equals("phone-defer-new")
                && row.sourceEnvironment().equals("PRODUCTION")
                && row.runId().isEmpty()));
        verify(mapper, never()).upsertPhoneDevice(any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void deferDeactivatesScopedPhoneEvenWhenLegacyCalibrationLostItsDeviceLink() {
        CalibrationRow activeWithoutLink = actionRow(9L, "dev-phone", null, 4L, "ACTIVE", null, null);
        CalibrationRow deferred = actionRow(9L, "dev-phone", null, 5L, "DEFERRED", "phone-defer-legacy", "hash");
        when(mapper.findForUpdate(9L, "dev-phone")).thenReturn(activeWithoutLink);
        when(mapper.updateActivation(eq(9L), eq("dev-phone"), eq(4L), eq(null), eq("DEFERRED"),
                eq("phone-defer-legacy"), any())).thenReturn(1);
        when(mapper.find(9L, "dev-phone")).thenReturn(deferred);

        ApiResult<Map<String, Object>> result = service.defer(9L,
                new OnboardingCalibrationService.ActionRequest("dev-phone", 4L, "phone-defer-legacy"));

        assertThat(result.getCode()).isZero();
        verify(mapper).deactivateScopedPhoneDevices(9L, "PRODUCTION", "");
    }

    private OnboardingCalibrationMapper.TierRow tier(int tier, int min, int max, String usdt, String nex) {
        return new TierRow(tier, "T" + tier, min, max, new BigDecimal(usdt), new BigDecimal(nex), 7L);
    }

    private OnboardingCalibrationService.Request request(String deviceId, long expected, String key, double mem,
                                                          int cores, String model, String brand, String gpu,
                                                          double px, int battery, boolean charging) {
        return new OnboardingCalibrationService.Request(deviceId, expected, key,
                new OnboardingCalibrationService.Signals(mem, cores, model, brand, gpu, px, 42.0, battery, charging, true));
    }

    private CalibrationRow row(long userId, String deviceId, long version, String key, String hash, long revision) {
        return new CalibrationRow(userId, deviceId, "{}", "{\"score\":87,\"tier\":3,\"tops\":28.3,\"baseRateUsdt\":0.060000,\"baseRateNex\":10}",
                "[{\"key\":\"phone\"}]", "server", true, revision, version, key, hash);
    }

    private CalibrationRow actionRow(long userId, String deviceId, Long userDeviceId, long version,
                                     String status, String actionKey, String actionHash) {
        return new CalibrationRow(userId, deviceId, userDeviceId, "{\"memGB\":8}",
                "{\"score\":87,\"tier\":3,\"tops\":28.3,\"baseRateUsdt\":0.060000,\"baseRateNex\":10}",
                "[{\"key\":\"phone\"}]", "server", true, 7L, version, "cal-key", "cal-hash",
                status, actionKey, actionHash, "PRODUCTION", "");
    }
}
