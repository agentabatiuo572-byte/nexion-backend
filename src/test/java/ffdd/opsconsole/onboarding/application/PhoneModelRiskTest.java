package ffdd.opsconsole.onboarding.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ffdd.opsconsole.onboarding.application.PhoneModelRisk.Mode;
import ffdd.opsconsole.onboarding.application.PhoneModelRisk.Settings;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class PhoneModelRiskTest {
    private static final PhoneCalibrationPolicy.IosModelRule RULE = new PhoneCalibrationPolicy.IosModelRule("ios-8", "iPhone 8",
            List.of("iPhone10,1"), BigDecimal.ONE, BigDecimal.TEN, true, "OPERATOR_PRESET", null, null, "11.0", 16, List.of(2),
            List.of("667x375"));

    @Test void versionsAndScreensParseStrictly() {
        assertThat(PhoneModelRisk.osVersion("16.7.10")).containsExactly(16, 7);
        assertThat(PhoneModelRisk.osVersion("17")).containsExactly(17, 0);
        for (String bad : new String[] {null, "", "iOS 17", "17.", "0.1", "17.5.1.2"}) assertThat(PhoneModelRisk.osVersion(bad)).isNull();
        assertThat(PhoneModelRisk.screen("874x402")).isEqualTo("402x874");
        assertThat(PhoneModelRisk.screen("402 x 874")).isNull();
    }

    @Test void anIPhoneOutsideItsOsRangeOrScaleIsFlaggedByMode() {
        var observed = PhoneModelRisk.evaluate(Settings.DEFAULTS, facts("17.0", 2.0, 375, 667), RULE, false, 0);
        assertThat(observed.flags()).containsExactly("OS_MISMATCH");
        assertThat(observed.pendingReason()).isNull();
        var enforced = PhoneModelRisk.evaluate(settings(Mode.ENFORCE, Mode.ENFORCE), facts("10.3", 3.0, 375, 667), RULE, false, 0);
        assertThat(enforced.flags()).containsExactly("OS_MISMATCH", "SCALE_MISMATCH");
        assertThat(enforced.pendingReason()).isEqualTo("OS_MISMATCH");
        assertThat(PhoneModelRisk.evaluate(Settings.DEFAULTS, facts("16.7.10", 2.0, 667, 375), RULE, false, 0).findings()).isEmpty();
    }

    @Test void theLockIsEnforcedByDefaultAndChurnOnlyObserved() {
        var outcome = PhoneModelRisk.evaluate(Settings.DEFAULTS, new PhoneModelRisk.Facts("android", null, null, null, null), null, true, 3);
        assertThat(outcome.flags()).containsExactly("MODEL_CHANGED", "ACCOUNT_MODEL_CHURN");
        assertThat(outcome.pendingReason()).isEqualTo("MODEL_CHANGED");
        var off = new Settings(1, Mode.OFF, Mode.OFF, Mode.OFF, Mode.OFF, Mode.OFF, Mode.OFF, 30, 3, null, null, null);
        assertThat(PhoneModelRisk.evaluate(off, facts(null, null, null, null), RULE, true, 9).findings()).isEmpty();
    }

    @Test void sizeAndChurnCannotBeEnforcedAndBoundsHold() {
        for (Settings bad : List.of(
                new Settings(1, null, null, null, Mode.ENFORCE, null, null, null, null, null, null, null),
                new Settings(1, null, null, null, null, null, Mode.ENFORCE, null, null, null, null, null),
                new Settings(1, null, null, null, null, null, null, 0, null, null, null, null),
                new Settings(1, null, null, null, null, null, null, null, 1, null, null, null))) {
            assertThatThrownBy(bad::validate).hasMessage("PHONE_RISK_SETTINGS_INVALID");
        }
    }

    @Test void ruleFactsAreValidatedWithThePolicy() {
        var bad = new PhoneCalibrationPolicy.IosModelRule("ios-8", "iPhone 8", List.of(), BigDecimal.ONE, BigDecimal.TEN, true,
                "OPERATOR_PRESET", null, null, "17.0", 16, List.of(4), List.of("big"));
        assertThatThrownBy(() -> new PhoneCalibrationPolicy(1, 0, List.of(BigDecimal.ONE, BigDecimal.valueOf(2),
                BigDecimal.valueOf(3), BigDecimal.TEN), List.of(), List.of(bad)).validate()).hasMessage("PHONE_CALIBRATION_POLICY_INVALID");
    }

    private static Settings settings(Mode os, Mode scale) {
        return new Settings(1, Mode.ENFORCE, os, scale, Mode.OBSERVE, Mode.OBSERVE, Mode.OBSERVE, 30, 3, null, null, null);
    }

    private static PhoneModelRisk.Facts facts(String os, Double scale, Integer width, Integer height) {
        return new PhoneModelRisk.Facts("ios", os, scale, width, height);
    }
}
