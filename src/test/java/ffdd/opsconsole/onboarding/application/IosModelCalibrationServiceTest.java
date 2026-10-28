package ffdd.opsconsole.onboarding.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.onboarding.mapper.OnboardingCalibrationMapper;
import ffdd.opsconsole.onboarding.mapper.OnboardingCalibrationMapper.CalibrationRow;
import ffdd.opsconsole.onboarding.mapper.OnboardingCalibrationMapper.ComparisonRow;
import ffdd.opsconsole.onboarding.mapper.OnboardingCalibrationMapper.TierRow;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;

/** I01–I03, I06–I08, I10, I11 and I19 against the calibration transaction with an in-memory table. */
@SuppressWarnings("unchecked")
class IosModelCalibrationServiceTest {
    private final OnboardingCalibrationMapper mapper = mock(OnboardingCalibrationMapper.class);
    private final Environment environment = mock(Environment.class);
    private final ffdd.opsconsole.platform.facade.PlatformConfigFacade config =
            mock(ffdd.opsconsole.platform.facade.PlatformConfigFacade.class);
    private final OnboardingCalibrationService service = new OnboardingCalibrationService(mapper, null, environment,
            mock(ffdd.opsconsole.shared.audit.AuditLogService.class), mock(ffdd.opsconsole.shared.outbox.EventOutboxService.class),
            config, java.time.Clock.systemUTC(), mock(PhoneInstallationService.class), mock(PhoneReplacementService.class),
            mock(ffdd.opsconsole.common.boundary.PhoneActivationCapacityContract.class),
            mock(ffdd.opsconsole.common.boundary.UserTermsAcceptanceContract.class));
    /** nx_onboarding_calibration rows of user 9 by device id, in update order. */
    private final Map<String, CalibrationRow> table = new LinkedHashMap<>();

    @BeforeEach void setUp() {
        when(environment.getActiveProfiles()).thenReturn(new String[] {"prod"});
        when(mapper.userSandbox(9L)).thenReturn(0);
        when(mapper.lockUserSandbox(9L)).thenReturn(0);
        when(mapper.activeTiers()).thenReturn(List.of(tier(1, "0.04"), tier(2, "0.05"), tier(3, "0.06"), tier(4, "0.08"), tier(5, "0.095")));
        when(mapper.activeComparisons()).thenReturn(List.of(new ComparisonRow("phone", "手机", new BigDecimal("0.06"),
                new BigDecimal("10"), 1, 7L)));
        publish(1, "100", "120");
        when(mapper.find(eq(9L), anyString())).thenAnswer(call -> table.get(call.<String>getArgument(1)));
        when(mapper.findForUpdate(eq(9L), anyString())).thenAnswer(call -> table.get(call.<String>getArgument(1)));
        when(mapper.insert(any())).thenAnswer(call -> {
            OnboardingCalibrationMapper.CalibrationWrite write = call.getArgument(0);
            table.put(write.deviceId(), row(write.deviceId(), write.signalJson(), write.derivedJson(), 1L, write.idempotencyKey()));
            return 1;
        });
        when(mapper.update(eq(9L), anyString(), anyLong(), anyString(), anyString(), anyString(), anyLong(), anyString(), anyString()))
                .thenAnswer(call -> {
                    String device = call.getArgument(1);
                    table.remove(device);
                    table.put(device, row(device, call.getArgument(3), call.getArgument(4), call.<Long>getArgument(2) + 1,
                            call.getArgument(7)));
                    return 1;
                });
        when(mapper.iosRangeAllocations(9L, "PRODUCTION", "")).thenAnswer(call -> {
            var newestFirst = new java.util.ArrayList<>(table.values().stream()
                    .map(CalibrationRow::derivedJson)
                    .filter(json -> json.contains("IOS_MODEL_RANGE") || json.contains("iosAllocations")).toList());
            java.util.Collections.reverse(newestFirst);
            return newestFirst;
        });
    }

    @Test void firstCalibrationDrawsOnceAndEveryRetryOrReinstallReadsTheStoredValue() {
        var first = calibrate("install-a", 0, "key-first-01", "iPhone 16 Pro").getData();
        BigDecimal value = new BigDecimal(first.get("computeValue").toString());
        assertThat(value).isBetween(new BigDecimal("100"), new BigDecimal("120"));
        assertThat(first).containsEntry("calibrationStatus", "MATCHED").containsEntry("calibrationMethod", "IOS_MODEL_RANGE")
                .containsEntry("model", "iPhone 16 Pro").containsEntry("ruleId", "ios-iphone-16-pro")
                .containsEntry("ruleVersion", 1).containsEntry("tier", 5)
                .doesNotContainKeys("minComputeValue", "maxComputeValue", "valueSource");
        // The range stays on the server for activation review; only the App responses omit it.
        Map<String, Object> stored = json(table.get("install-a").derivedJson());
        assertThat(new BigDecimal(stored.get("minComputeValue").toString())).isEqualByComparingTo("100");
        assertThat(new BigDecimal(stored.get("maxComputeValue").toString())).isEqualByComparingTo("120");
        assertThat(stored).containsEntry("valueSource", "OPERATOR_PRESET");
        assertThat(service.result(9L, "install-a").getData()).doesNotContainKeys("minComputeValue", "maxComputeValue", "valueSource");
        assertThat(service.project(table.get("install-a"))).containsKeys("minComputeValue", "maxComputeValue", "valueSource");

        for (int i = 0; i < 20; i++) {
            var retry = calibrate("install-a", (Long) table.get("install-a").rowVersion(), "key-retry-" + 10 + i, "iPhone17,1");
            assertThat(new BigDecimal(retry.getData().get("computeValue").toString())).isEqualByComparingTo(value);
        }
        var reinstall = calibrate("install-b", 0, "key-reinstall", "iPhone 16 Pro").getData();
        assertThat(new BigDecimal(reinstall.get("computeValue").toString())).isEqualByComparingTo(value);
        assertThat(new BigDecimal(service.result(9L, "install-a").getData().get("computeValue").toString()))
                .isEqualByComparingTo(value);
    }

    @Test void aDetourThroughAnotherReportedModelOnTheSameInstallCannotDrawAgain() {
        BigDecimal value = new BigDecimal(calibrate("install-a", 0, "key-first-01", "iPhone 16 Pro").getData().get("computeValue").toString());
        for (int i = 0; i < 10; i++) {
            var detour = calibrate("install-a", table.get("install-a").rowVersion(), "key-detour-" + 10 + i, "iPhone99,9").getData();
            assertThat(detour).containsEntry("calibrationStatus", "PENDING_VERIFICATION").doesNotContainKey("iosAllocations");
            var back = calibrate("install-a", table.get("install-a").rowVersion(), "key-back-" + 10 + i, "iPhone 16 Pro").getData();
            assertThat(new BigDecimal(back.get("computeValue").toString())).isEqualByComparingTo(value);
            assertThat(back).doesNotContainKey("iosAllocations");
        }
    }

    @Test void capacityIsNotPartOfTheModelAndCannotChangeTheAllocation() {
        BigDecimal value = new BigDecimal(calibrate("install-a", 0, "key-first-01", "iPhone 16 Pro").getData().get("computeValue").toString());
        var other = calibrate("install-b", 0, "key-other-01", "iPhone 16 Pro", "512GB");
        assertThat(new BigDecimal(other.getData().get("computeValue").toString())).isEqualByComparingTo(value);
    }

    @Test void unknownGenericOrEmptyModelsArePendingWithoutAnyValue() {
        for (String model : List.of("iPhone", "", "iPhone99,9")) {
            var result = calibrate("install-" + model.length(), 0, "key-pending-" + model.length(), model).getData();
            assertThat(result).containsEntry("calibrationStatus", "PENDING_VERIFICATION")
                    .containsEntry("calibrationAvailable", false).containsEntry("computeValue", null).containsEntry("tier", null);
        }
    }

    @Test void activationReviewsTheStoredValueAndARangeChangeInvalidatesItOnlyWhenOutside() {
        BigDecimal value = new BigDecimal(calibrate("install-a", 0, "key-first-01", "iPhone 16 Pro").getData().get("computeValue").toString());
        assertThat(service.activationBlockReasons(9L, "install-a")).isEmpty();
        assertThat(service.activationBlockReasons(9L, "install-a")).isEmpty(); // a review never re-draws

        publish(2, value.toPlainString(), value.toPlainString());
        assertThat(service.activationBlockReasons(9L, "install-a")).containsExactly("PHONE_CALIBRATION_RULES_CHANGED");
        var sameValue = calibrate("install-a", table.get("install-a").rowVersion(), "key-new-version", "iPhone 16 Pro").getData();
        assertThat(new BigDecimal(sameValue.get("computeValue").toString())).isEqualByComparingTo(value);
        assertThat(sameValue).containsEntry("ruleVersion", 2);
        assertThat(service.activationBlockReasons(9L, "install-a")).isEmpty();

        String outside = value.compareTo(new BigDecimal("500")) < 0 ? "600" : "10";
        publish(3, outside, outside);
        var redrawn = calibrate("install-a", table.get("install-a").rowVersion(), "key-outside-01", "iPhone 16 Pro").getData();
        assertThat(new BigDecimal(redrawn.get("computeValue").toString())).isEqualByComparingTo(outside);
    }

    @Test void aTamperedStoredValueOutsideTheRuleFailsActivationReview() {
        calibrate("install-a", 0, "key-first-01", "iPhone 16 Pro");
        var row = table.get("install-a");
        String tampered = row.derivedJson().replaceAll("\"computeValue\":[0-9.]+", "\"computeValue\":150");
        table.put("install-a", row("install-a", row.signalJson(), tampered, row.rowVersion(), row.idempotencyKey()));
        assertThat(service.activationBlockReasons(9L, "install-a")).containsExactly("PHONE_CALIBRATION_RULES_CHANGED");
    }

    @Test void theBindingHardwareKeyFollowsTheCanonicalModelNotTheReportedName() {
        calibrate("install-a", 0, "key-first-01", "iPhone 16 Pro");
        String byName = service.hardwareKey(table.get("install-a"));
        calibrate("install-a", table.get("install-a").rowVersion(), "key-ident-01", "iPhone17,1");
        assertThat(service.hardwareKey(table.get("install-a"))).isEqualTo(byName);
    }

    @Test void anInstallationReportingAnotherModelIsHeldWithoutADrawAndTheAppIsNotToldWhy() {
        calibrate("install-a", 0, "key-first-01", "iPhone 16 Pro");
        for (String key : List.of("key-swap-001", "key-swap-002")) {
            var held = calibrate("install-a", table.get("install-a").rowVersion(), key, "iPhone 17 Pro").getData();
            assertThat(held).containsEntry("calibrationStatus", "PENDING_VERIFICATION").containsEntry("pendingReason", "MODEL_UNVERIFIED")
                    .containsEntry("computeValue", null).doesNotContainKeys("riskFlags", "installModelLock");
            Map<String, Object> stored = json(table.get("install-a").derivedJson());
            assertThat(stored).containsEntry("pendingReason", "MODEL_CHANGED");
            assertThat((Map<String, Object>) stored.get("installModelLock")).containsEntry("model", "iPhone 16 Pro");
        }
        // Its own identifier is the same phone.
        assertThat(calibrate("install-a", table.get("install-a").rowVersion(), "key-back-0001", "iPhone17,1").getData())
                .containsEntry("calibrationStatus", "MATCHED");
    }

    @Test void observingTheLockFlagsTheChangeButCalibratesAsBefore() {
        risk("{\"revision\":1,\"installModelLock\":\"OBSERVE\"}");
        calibrate("install-a", 0, "key-first-01", "iPhone 15");
        var result = calibrate("install-a", table.get("install-a").rowVersion(), "key-swap-001", "iPhone 16 Pro").getData();
        assertThat(result).containsEntry("calibrationStatus", "MATCHED").doesNotContainKey("riskFlags");
        assertThat((List<Object>) json(table.get("install-a").derivedJson()).get("riskFlags")).containsExactly("MODEL_CHANGED", "SIGNALS_MISSING");
        // Observed, the lock follows the phone, so enforcing later does not hold this legitimate change.
        assertThat((Map<String, Object>) json(table.get("install-a").derivedJson()).get("installModelLock")).containsEntry("model", "iPhone 16 Pro");
    }

    @Test void anAndroidInstallationLocksItsModelToo() {
        calibrateWith("install-x", 0, "key-pixel-01", android("Pixel 8"));
        var held = calibrateWith("install-x", table.get("install-x").rowVersion(), "key-pixel-02", android("Pixel 9")).getData();
        assertThat(held).containsEntry("pendingReason", "MODEL_UNVERIFIED");
        assertThat(json(table.get("install-x").derivedJson())).containsEntry("pendingReason", "MODEL_CHANGED");
    }

    @Test void aReleasedLockTakesTheNextReportedModel() {
        calibrate("install-a", 0, "key-first-01", "iPhone 16 Pro");
        calibrate("install-a", table.get("install-a").rowVersion(), "key-swap-001", "iPhone 15");
        release("install-a");
        calibrate("install-a", table.get("install-a").rowVersion(), "key-after-001", "iPhone 15");
        Map<String, Object> stored = json(table.get("install-a").derivedJson());
        assertThat(stored).containsEntry("pendingReason", "MODEL_UNKNOWN");
        assertThat((Map<String, Object>) stored.get("installModelLock")).containsEntry("model", "iPhone 15");
    }

    @Test void aLockReleasedBeforeTheNewPhoneReportsDoesNotFallBackToTheOldReport() {
        // The customer calls support first: the installation's latest report is still the old phone.
        calibrate("install-a", 0, "key-first-01", "iPhone 15");
        release("install-a");
        var next = calibrate("install-a", table.get("install-a").rowVersion(), "key-new-0001", "iPhone 16 Pro").getData();
        assertThat(next).containsEntry("calibrationStatus", "MATCHED");
        assertThat((Map<String, Object>) json(table.get("install-a").derivedJson()).get("installModelLock")).containsEntry("model", "iPhone 16 Pro");
    }

    @Test void withTheLockOffItFollowsTheReportSoEnablingItStartsFromTheCurrentPhone() {
        risk("{\"revision\":1,\"installModelLock\":\"OFF\"}");
        calibrate("install-a", 0, "key-first-01", "iPhone 15");
        calibrate("install-a", table.get("install-a").rowVersion(), "key-swap-001", "iPhone 16 Pro");
        assertThat((Map<String, Object>) json(table.get("install-a").derivedJson()).get("installModelLock")).containsEntry("model", "iPhone 16 Pro");
    }

    /** What OnboardingCalibrationMapper.releaseModelLock writes for support. */
    private void release(String device) {
        var row = table.get(device);
        Map<String, Object> derived = json(row.derivedJson());
        derived.put("installModelLock", Map.of("released", true));
        table.put(device, row(row.deviceId(), row.signalJson(), write(derived), row.rowVersion(), row.idempotencyKey()));
    }

    @Test void iPhoneFactsAreComparedWithTheRuleBeforeAnyDraw() {
        publishWithFacts();
        risk("{\"revision\":1,\"osVersion\":\"ENFORCE\",\"screenScale\":\"ENFORCE\"}");
        var oldOs = calibrateWith("install-a", 0, "key-facts-01", ios("iPhone 16 Pro", "16.7", 3.0, 402, 874)).getData();
        assertThat(oldOs).containsEntry("pendingReason", "MODEL_UNVERIFIED").containsEntry("computeValue", null);
        assertThat(json(table.get("install-a").derivedJson())).containsEntry("pendingReason", "OS_MISMATCH")
                .doesNotContainKey("iosAllocations");
        var scale = calibrateWith("install-b", 0, "key-facts-02", ios("iPhone 16 Pro", "18.1", 2.0, 402, 874)).getData();
        assertThat(scale).containsEntry("pendingReason", "MODEL_UNVERIFIED");
        assertThat(json(table.get("install-b").derivedJson())).containsEntry("pendingReason", "SCALE_MISMATCH");
        // Display Zoom and older builds are only observed.
        var zoomed = calibrateWith("install-c", 0, "key-facts-03", ios("iPhone 16 Pro", "18.1", 3.0, 375, 812)).getData();
        assertThat(zoomed).containsEntry("calibrationStatus", "MATCHED").doesNotContainKey("riskFlags");
        assertThat((List<Object>) json(table.get("install-c").derivedJson()).get("riskFlags")).containsExactly("SCREEN_MISMATCH");
        calibrate("install-d", 0, "key-facts-04", "iPhone 16 Pro");
        assertThat((List<Object>) json(table.get("install-d").derivedJson()).get("riskFlags")).containsExactly("SIGNALS_MISSING");
    }

    @Test void aReportWithoutPlatformIsTheSamePhoneAndAnImplausibleScaleIsOnlySkipped() {
        calibrate("install-a", 0, "key-first-01", "iPhone 16 Pro");
        var noPlatform = new OnboardingCalibrationService.Signals(null, null, "iPhone 16 Pro", "Apple", "", null, null, 80, false, true,
                "", "", null, null, null, 0.75);
        var result = calibrateWith("install-a", table.get("install-a").rowVersion(), "key-plain-01", noPlatform).getData();
        assertThat(json(table.get("install-a").derivedJson()).get("pendingReason")).isNotEqualTo("MODEL_CHANGED");
        assertThat(result.get("pendingReason")).isNotEqualTo("MODEL_UNVERIFIED");
    }

    @Test void manyModelsOnOneAccountAreObservedNotHeld() {
        when(mapper.accountModelReports(9L, "PRODUCTION", "", 30)).thenReturn(java.util.Arrays.asList(
                new OnboardingCalibrationMapper.ModelReport("android", "Pixel 8", "android", "Pixel 8"),
                null, // a deferred installation without any report reads back as null
                new OnboardingCalibrationMapper.ModelReport("ios", "iPhone 15", "ios", "iPhone 15")));
        var result = calibrate("install-a", 0, "key-churn-01", "iPhone 16 Pro").getData();
        assertThat(result).containsEntry("calibrationStatus", "MATCHED");
        assertThat((List<Object>) json(table.get("install-a").derivedJson()).get("riskFlags")).contains("ACCOUNT_MODEL_CHURN");
    }

    @Test void androidDrawsInsideItsTierOnceAndShowsNoChipOrRule() {
        publishAndroid();
        var first = calibrateWith("install-x", 0, "key-soc-0001", androidSoc("Pixel 8", "SM8550")).getData();
        BigDecimal value = new BigDecimal(first.get("computeValue").toString());
        assertThat(value).isBetween(new BigDecimal("95"), new BigDecimal("115"));
        assertThat(first).containsEntry("calibrationMethod", "ANDROID_SOC_RANGE")
                .doesNotContainKeys("ruleId", "ruleVersion", "soc", "socKey", "minComputeValue", "maxComputeValue", "valueSource");
        assertThat((Map<String, Object>) first.get("signals")).doesNotContainKeys("socIds", "soc", "gpu");
        assertThat(json(table.get("install-x").derivedJson())).containsEntry("socKey", "sd-8g2").containsEntry("soc", "Snapdragon 8 Gen 2");
        // Another phone with another chip of the same tier keeps the account's value.
        var sameTier = calibrateWith("install-y", 0, "key-soc-0002", androidSoc("Galaxy S24", "SM8650")).getData();
        assertThat(new BigDecimal(sameTier.get("computeValue").toString())).isEqualByComparingTo(value);
    }

    @Test void anInstallationReportingAnotherChipIsHeldButAnotherAliasOfItsChipIsNot() {
        publishAndroid();
        calibrateWith("install-x", 0, "key-soc-0001", androidSoc("Pixel 8", "SM8550"));
        String key = service.hardwareKey(table.get("install-x"));
        var alias = calibrateWith("install-x", table.get("install-x").rowVersion(), "key-soc-0002", androidSoc("Pixel 8", "kalama")).getData();
        assertThat(alias).containsEntry("calibrationStatus", "MATCHED");
        assertThat(service.hardwareKey(table.get("install-x"))).isEqualTo(key);
        var swapped = calibrateWith("install-x", table.get("install-x").rowVersion(), "key-soc-0003", androidSoc("Pixel 8", "SM8650")).getData();
        assertThat(swapped).containsEntry("calibrationStatus", "PENDING_VERIFICATION").containsEntry("pendingReason", "MODEL_UNVERIFIED");
        assertThat(json(table.get("install-x").derivedJson())).containsEntry("pendingReason", "SOC_CHANGED");
        var unknown = calibrateWith("install-z", 0, "key-soc-0004", androidSoc("Phone Z", "MT6833")).getData();
        assertThat(unknown).containsEntry("pendingReason", "MODEL_UNVERIFIED");
        assertThat(json(table.get("install-z").derivedJson())).containsEntry("pendingReason", "SOC_UNKNOWN");
    }

    private void publishAndroid() {
        when(config.activeValue(PhoneCalibrationConfigService.KEY)).thenReturn(Optional.of(
                "{\"revision\":1,\"current\":{\"version\":1,\"effectiveAt\":0,\"thresholds\":[19,25,35,47],\"androidSocRules\":["
                + "{\"id\":\"tier-high\",\"name\":\"次旗舰\",\"minComputeValue\":95,\"maxComputeValue\":115,\"enabled\":true,"
                + "\"valueSource\":\"OPERATOR_PRESET\",\"chips\":[{\"socKey\":\"sd-8g2\",\"name\":\"Snapdragon 8 Gen 2\","
                + "\"manufacturer\":\"Qualcomm\",\"aliases\":[\"SM8550\",\"kalama\"]},{\"socKey\":\"sd-8g3\","
                + "\"name\":\"Snapdragon 8 Gen 3\",\"manufacturer\":\"Qualcomm\",\"aliases\":[\"SM8650\"]}]}],"
                + "\"iosModelRules\":[]},\"scheduled\":null}"));
    }

    private static OnboardingCalibrationService.Signals androidSoc(String model, String... socIds) {
        return new OnboardingCalibrationService.Signals(8.0, 8, model, "Google", "Adreno 740", null, null, 80, false, true,
                "android", "", null, null, null, null, List.of(socIds));
    }

    private void risk(String settings) {
        when(config.activeValue(PhoneModelRiskConfig.KEY)).thenReturn(Optional.of(settings));
    }

    private void publishWithFacts() {
        when(config.activeValue(PhoneCalibrationConfigService.KEY)).thenReturn(Optional.of(
                "{\"revision\":1,\"current\":{\"version\":1,\"effectiveAt\":0,"
                + "\"thresholds\":[19,25,35,47],\"rules\":[],\"iosModelRules\":[{\"id\":\"ios-iphone-16-pro\","
                + "\"model\":\"iPhone 16 Pro\",\"aliases\":[\"iPhone17,1\"],\"minComputeValue\":100,"
                + "\"maxComputeValue\":120,\"enabled\":true,\"valueSource\":\"OPERATOR_PRESET\",\"minOsVersion\":\"18.0\","
                + "\"scales\":[3],\"screens\":[\"402x874\"]}]},\"scheduled\":null}"));
    }

    private static OnboardingCalibrationService.Signals ios(String model, String os, Double scale, Integer width, Integer height) {
        return new OnboardingCalibrationService.Signals(null, null, model, "Apple", "", null, null, 80, false, true, "ios", "",
                os, width, height, scale);
    }

    private static OnboardingCalibrationService.Signals android(String model) {
        return new OnboardingCalibrationService.Signals(8.0, 8, model, "Google", "Mali", null, null, 80, false, true, "android", "Tensor");
    }

    private ffdd.opsconsole.shared.api.ApiResult<Map<String, Object>> calibrateWith(String device, long expected, String key,
                                                                                    OnboardingCalibrationService.Signals signals) {
        var result = service.calibrate(9L, new OnboardingCalibrationService.Request(device, expected, key, signals));
        assertThat(result.getCode()).as(result.getMessage()).isZero();
        return result;
    }

    private static String write(Map<String, Object> value) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(value);
        } catch (com.fasterxml.jackson.core.JsonProcessingException invalid) {
            throw new IllegalStateException(invalid);
        }
    }

    private ffdd.opsconsole.shared.api.ApiResult<Map<String, Object>> calibrate(String device, long expected, String key,
                                                                               String model, String... extra) {
        String brand = extra.length > 0 ? "Apple " + extra[0] : "Apple";
        var signals = new OnboardingCalibrationService.Signals(null, null, model, brand, "", null, null, 80, false, true, "ios", "");
        var result = service.calibrate(9L, new OnboardingCalibrationService.Request(device, expected, key, signals));
        assertThat(result.getCode()).as(result.getMessage()).isZero();
        return result;
    }

    @SuppressWarnings("unchecked")
    @Test void anActivationReceiptStoredWithTheRangeReachesTheAppWithoutIt() {
        Map<String, Object> result = new LinkedHashMap<>(Map.of("computeValue", 113.27, "model", "iPhone 16 Pro",
                "minComputeValue", 100, "maxComputeValue", 120, "valueSource", "OPERATOR_PRESET"));
        Map<String, Object> receipt = new LinkedHashMap<>(result);
        receipt.put("originalResult", result);
        Map<String, Object> view = PhoneCalibrationPolicy.appView(receipt);
        assertThat(view).containsEntry("computeValue", 113.27).doesNotContainKeys("minComputeValue", "maxComputeValue", "valueSource");
        assertThat((Map<String, Object>) view.get("originalResult")).containsEntry("model", "iPhone 16 Pro")
                .doesNotContainKeys("minComputeValue", "maxComputeValue", "valueSource");
        assertThat(result).containsKey("minComputeValue");
    }

    private static Map<String, Object> json(String value) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readValue(value, new com.fasterxml.jackson.core.type.TypeReference<>() { });
        } catch (com.fasterxml.jackson.core.JsonProcessingException invalid) {
            throw new IllegalStateException(invalid);
        }
    }

    private void publish(long version, String min, String max) {
        when(config.activeValue(PhoneCalibrationConfigService.KEY)).thenReturn(Optional.of(
                "{\"revision\":" + version + ",\"current\":{\"version\":" + version + ",\"effectiveAt\":0,"
                + "\"thresholds\":[19,25,35,47],\"rules\":[],\"iosModelRules\":[{\"id\":\"ios-iphone-16-pro\","
                + "\"model\":\"iPhone 16 Pro\",\"aliases\":[\"iPhone17,1\"],\"minComputeValue\":" + min
                + ",\"maxComputeValue\":" + max + ",\"enabled\":true,\"valueSource\":\"OPERATOR_PRESET\"}]},\"scheduled\":null}"));
    }

    private static CalibrationRow row(String device, String signals, String derived, long version, String key) {
        return new CalibrationRow(9L, device, null, signals, derived, "[]", "server", true, 7L, version, key, "hash",
                "CALIBRATED", null, null, "PRODUCTION", "");
    }

    private static TierRow tier(int tier, String usdt) {
        return new TierRow(tier, "T" + tier, tier * 10, tier * 10 + 9, new BigDecimal(usdt), new BigDecimal("6"), 7L);
    }
}
