package ffdd.opsconsole.shared.capacity;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class E3DeviceCapacityPolicyTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 26, 12, 0);

    @Test
    void computesTheTrueServerSideSubsidyCountdown() {
        var projection = E3DeviceCapacityPolicy.project(
                "stellarbox-pro", "SERVER", NOW.minusDays(1).minusHours(1), null, NOW, config());

        assertThat(projection.subsidized()).isTrue();
        assertThat(projection.subsidyRemainingDays()).isEqualTo(29);
        assertThat(projection.subsidyEndsAt()).isEqualTo(NOW.plusDays(29).minusHours(1));
    }

    @Test
    void roundsTheLastPartialDayUpAndExpiresExactlyAtTheServerDeadline() {
        var lastDay = E3DeviceCapacityPolicy.project(
                "stellarbox-pro", "SERVER", NOW.minusDays(30).plusSeconds(1), null, NOW, config());
        var expired = E3DeviceCapacityPolicy.project(
                "stellarbox-pro", "SERVER", NOW.minusDays(30), null, NOW, config());

        assertThat(lastDay.subsidized()).isTrue();
        assertThat(lastDay.subsidyRemainingDays()).isOne();
        assertThat(expired.subsidized()).isFalse();
        assertThat(expired.subsidyRemainingDays()).isZero();
    }

    @Test
    void usesTheSameCapacityProjectionForDisplayAndSettlement() {
        var projection = E3DeviceCapacityPolicy.project(
                "stellarbox-pro", "SERVER", NOW.minusMonths(4), null, NOW, config());

        assertThat(projection.capacityPct()).isEqualByComparingTo("85.791262");
        assertThat(E3DeviceCapacityPolicy.applyCapacity(new BigDecimal("1.000000"), projection))
                .isEqualByComparingTo("0.857913");
    }

    @Test
    void treatsTheLastDayOfFebruaryAsOneCalendarMonthForAMonthEndPurchase() {
        var leapYear = E3DeviceCapacityPolicy.project(
                "stellarbox-pro", "SERVER",
                LocalDateTime.of(2024, 1, 31, 12, 0),
                null,
                LocalDateTime.of(2024, 2, 29, 12, 0), config());
        var beforeSecondAnniversary = E3DeviceCapacityPolicy.project(
                "stellarbox-pro", "SERVER",
                LocalDateTime.of(2024, 1, 31, 12, 0),
                null,
                LocalDateTime.of(2024, 3, 30, 12, 0), config());
        var secondAnniversary = E3DeviceCapacityPolicy.project(
                "stellarbox-pro", "SERVER",
                LocalDateTime.of(2024, 1, 31, 12, 0),
                null,
                LocalDateTime.of(2024, 3, 31, 12, 0), config());

        assertThat(leapYear.ageMonths()).isOne();
        assertThat(beforeSecondAnniversary.ageMonths()).isOne();
        assertThat(secondAnniversary.ageMonths()).isEqualTo(2);
    }

    @Test
    void leavesExcludedCloudShareAtOneHundredPercent() {
        var projection = E3DeviceCapacityPolicy.project(
                "cloud-share", "SHARE", NOW.minusMonths(18), null, NOW, config());

        assertThat(projection.configKey()).isEqualTo("capacityApplyToCloudShare");
        assertThat(projection.capacityPct()).isEqualByComparingTo("100.000000");
    }

    @Test
    void rejectsAnIncompleteOrUnclassifiedSettlementContract() {
        Map<String, String> incomplete = config();
        incomplete.remove("capacityBand2DeltaPct");

        assertThat(E3DeviceCapacityPolicy.validConfig(incomplete)).isFalse();
        assertThat(E3DeviceCapacityPolicy.classify("mystery", "unknown")).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"taskLockS1", "taskLockPro", "taskLockRack"})
    void usdtHintThresholdHasNoMinuteCeilingAndDoesNotChangeCapacityOrReward(String key) {
        var original = config();
        var expected = E3DeviceCapacityPolicy.project(
                "stellarbox-pro", "SERVER", NOW.minusMonths(4), null, NOW, original);
        for (String amount : new String[]{"0", "10081", "999999999999999999999999999999"}) {
            var changed = config();
            changed.put(key, amount);
            assertThat(E3DeviceCapacityPolicy.validConfig(changed)).isTrue();
            var actual = E3DeviceCapacityPolicy.project(
                    "stellarbox-pro", "SERVER", NOW.minusMonths(4), null, NOW, changed);
            assertThat(actual).isEqualTo(expected);
            assertThat(E3DeviceCapacityPolicy.applyCapacity(BigDecimal.ONE, actual))
                    .isEqualByComparingTo("0.857913");
        }
    }

    @ParameterizedTest
    @CsvSource({"taskLockS1,-1", "taskLockPro,0.01", "taskLockRack,invalid",
            "stageEarlyEnd,0", "stageMidEnd,3", "cycleMonths,8",
            "capacityFloorPct,101", "capacityBand1DeltaPct,-101", "capacitySubsidyDays,-1"})
    void stillRejectsInvalidAmountsAndOtherCapacityInvariants(String key, String value) {
        var candidate = config();
        candidate.put(key, value);
        assertThat(E3DeviceCapacityPolicy.validConfig(candidate)).isFalse();
    }

    @Test
    void fallsBackToTheVerifiedActivationTimeWhenPurchaseTimeIsMissing() {
        var projection = E3DeviceCapacityPolicy.project(
                "stellarbox-pro", "SERVER", null, NOW.minusMonths(4), NOW, config());

        assertThat(projection.ageMonths()).isEqualTo(4);
        assertThat(projection.capacityPct()).isEqualByComparingTo("85.791262");
        assertThat(projection.subsidized()).isFalse();
    }

    @Test
    void rejectsADeviceWithNoPurchaseOrActivationTime() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> E3DeviceCapacityPolicy.project(
                        "stellarbox-pro", "SERVER", null, null, NOW, config()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("E3_DEVICE_LIFECYCLE_START_UNAVAILABLE");
    }

    @Test
    void rejectsALifecycleStartInTheFuture() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> E3DeviceCapacityPolicy.project(
                        "stellarbox-pro", "SERVER", NOW.plusSeconds(1), null, NOW, config()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("E3_DEVICE_LIFECYCLE_START_INVALID");
    }

    private Map<String, String> config() {
        return new LinkedHashMap<>(Map.ofEntries(
                Map.entry("capacityBand1DeltaPct", "-3"),
                Map.entry("capacityBand2DeltaPct", "-6"),
                Map.entry("capacityBand3DeltaPct", "-23.7"),
                Map.entry("stageEarlyEnd", "3"),
                Map.entry("stageMidEnd", "8"),
                Map.entry("cycleMonths", "13"),
                Map.entry("capacityFloorPct", "22"),
                Map.entry("capacitySubsidyDays", "30"),
                Map.entry("taskLockS1", "30"),
                Map.entry("taskLockPro", "150"),
                Map.entry("taskLockRack", "480"),
                Map.entry("capacityApplyToPhone", "false"),
                Map.entry("capacityApplyToCloudShare", "false"),
                Map.entry("capacityApplyToPcGpu", "false"),
                Map.entry("capacityApplyToS1", "true"),
                Map.entry("capacityApplyToPro", "true"),
                Map.entry("capacityApplyToProV2", "true"),
                Map.entry("capacityApplyToRackP1", "true"),
                Map.entry("capacityApplyToRackP2", "true")));
    }
}
