package ffdd.opsconsole.onboarding.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import ffdd.opsconsole.shared.exception.BizException;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class PhoneCalibrationPolicyTest {
    static PhoneCalibrationPolicy.Rule rule(String id, String model, String value) {
        return new PhoneCalibrationPolicy.Rule(id, "android", model, "test soc", "test gpu",
                new BigDecimal("3"), new BigDecimal("9"), new BigDecimal(value), "Test fixture only");
    }
    static PhoneCalibrationPolicy policy(List<PhoneCalibrationPolicy.Rule> rules) {
        return new PhoneCalibrationPolicy(1, 0, List.of(new BigDecimal("19"), new BigDecimal("25"),
                new BigDecimal("35"), new BigDecimal("47")), rules);
    }
    @Test void decimalsAndBoundariesNeverFallThroughToHighestTier() {
        var policy = policy(List.of());
        policy.validate();
        assertThat(List.of("18.4","19","24.3","25","34.2","35","46.9","47").stream()
                .map(v -> policy.tierFor(new BigDecimal(v)))).containsExactly(1,2,2,3,3,4,4,5);
    }
    @Test void exactModelTakesPrecedenceAndConflictDoesNotUseGeneric() {
        var policy = policy(List.of(rule("generic", "", "18.4"), rule("exact", "Test phone", "34.2")));
        assertThat(policy.match(new PhoneCalibrationPolicy.Hardware("android", " TEST PHONE ", "TEST SOC", "Test GPU",
                new BigDecimal("7.5"))).ruleId()).isEqualTo("exact");
        assertThat(policy.match(new PhoneCalibrationPolicy.Hardware("android", "Test phone", "other soc", "Test GPU",
                new BigDecimal("7.5"))).status()).isEqualTo("HARDWARE_CONFLICT");
        assertThat(policy.match(new PhoneCalibrationPolicy.Hardware("android", "another model", "test soc", "test gpu",
                new BigDecimal("4"))).tier()).isEqualTo(1);
    }
    @Test void unknownMissingAndOutOfMemoryRangeCannotReceiveAnAward() {
        var policy = policy(List.of(rule("exact", "Test phone", "34.2")));
        assertThat(policy.match(null).computeValue()).isNull();
        assertThat(policy.match(new PhoneCalibrationPolicy.Hardware("web", "Test phone", "test soc", "test gpu",
                new BigDecimal("4"))).tier()).isNull();
        assertThat(policy.match(new PhoneCalibrationPolicy.Hardware("android", "Test phone", "test soc", "test gpu",
                new BigDecimal("9"))).tier()).isNull();
    }
    @Test void overlappingRulesAndNonIncreasingThresholdsAreRejected() {
        assertThatThrownBy(() -> policy(List.of(rule("one", "Test phone", "20"), rule("two", " test phone ", "30"))).validate())
                .isInstanceOf(BizException.class).hasMessageContaining("PHONE_CALIBRATION_RULE_OVERLAP");
        assertThatThrownBy(() -> new PhoneCalibrationPolicy(1,0,List.of(BigDecimal.ONE,BigDecimal.ONE,
                BigDecimal.TEN,new BigDecimal("20")),List.of()).validate()).isInstanceOf(BizException.class);
    }
}
