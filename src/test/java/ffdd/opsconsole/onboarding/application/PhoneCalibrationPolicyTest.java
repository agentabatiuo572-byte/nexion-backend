package ffdd.opsconsole.onboarding.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import ffdd.opsconsole.shared.exception.BizException;
import java.math.BigDecimal;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/** Android chip tiers: one range per tier, chips as alias groups, every reported identifier resolved. */
class PhoneCalibrationPolicyTest {
    static PhoneCalibrationPolicy.AndroidSocChip chip(String key, String name, String... aliases) {
        return new PhoneCalibrationPolicy.AndroidSocChip(key, name, "Qualcomm", List.of(aliases));
    }
    static PhoneCalibrationPolicy.AndroidSocRule tier(String id, String min, String max, boolean enabled,
                                                      PhoneCalibrationPolicy.AndroidSocChip... chips) {
        return new PhoneCalibrationPolicy.AndroidSocRule(id, id, new BigDecimal(min), new BigDecimal(max), enabled,
                "OPERATOR_PRESET", null, List.of(chips));
    }
    static PhoneCalibrationPolicy policy(List<PhoneCalibrationPolicy.AndroidSocRule> tiers) {
        return new PhoneCalibrationPolicy(1, 0, List.of(new BigDecimal("19"), new BigDecimal("25"),
                new BigDecimal("35"), new BigDecimal("47")), tiers, List.of());
    }
    static PhoneCalibrationPolicy.Hardware android(String... ids) {
        return new PhoneCalibrationPolicy.Hardware("android", "Test phone", List.of(ids));
    }
    static final PhoneCalibrationPolicy TIERS = policy(List.of(
            tier("tier-high", "95", "115", true, chip("sd-8g2", "Snapdragon 8 Gen 2", "SM8550", "kalama"),
                    chip("sd-8g3", "Snapdragon 8 Gen 3", "SM8650")),
            tier("tier-mid", "55", "75", false, chip("sd-778g", "Snapdragon 778G", "SM7325"))));

    @Test void decimalsAndBoundariesNeverFallThroughToHighestTier() {
        var policy = policy(List.of());
        policy.validate();
        assertThat(List.of("18.4","19","24.3","25","34.2","35","46.9","47").stream()
                .map(v -> policy.tierFor(new BigDecimal(v)))).containsExactly(1,2,2,3,3,4,4,5);
    }

    @Test void anyVerifiedIdentifierOfAChipMatchesItsTierAsARange() {
        for (var hardware : List.of(android(" sm8550 "), android("kalama"), android("SM8550", "kalama"), android("qcom", "KALAMA"))) {
            var match = TIERS.match(hardware);
            assertThat(match.status()).isEqualTo("MATCHED");
            assertThat(match.method()).isEqualTo("ANDROID_SOC_RANGE");
            assertThat(match.ruleId()).isEqualTo("tier-high");
            assertThat(match.socKey()).isEqualTo("sd-8g2");
            assertThat(match.model()).isEqualTo("Snapdragon 8 Gen 2");
            assertThat(match.computeValue()).isNull();
        }
        // An unknown identifier beside a verified one does not block it.
        assertThat(TIERS.match(android("mt9999", "SM8550")).socKey()).isEqualTo("sd-8g2");
    }

    @Test void differentChipsConflictWhateverTheOrderEvenWithinOneTier() {
        assertThat(TIERS.match(android("SM8550", "SM8650")).status()).isEqualTo("SOC_CONFLICT");
        assertThat(TIERS.match(android("SM8650", "SM8550")).status()).isEqualTo("SOC_CONFLICT");
        // A disabled tier cannot be reached by putting its chip first or last.
        assertThat(TIERS.match(android("SM7325", "SM8550")).status()).isEqualTo("SOC_CONFLICT");
        assertThat(TIERS.match(android("SM8550", "SM7325")).status()).isEqualTo("SOC_CONFLICT");
    }

    @Test void genericSharedUnknownAndDisabledIdentifiersWaitForVerification() {
        assertThat(TIERS.match(android()).status()).isEqualTo("SOC_MISSING");
        assertThat(TIERS.match(android("qcom", " ", "unknown")).status()).isEqualTo("SOC_MISSING");
        assertThat(TIERS.match(android("MT6833")).status()).isEqualTo("SOC_UNKNOWN");
        assertThat(TIERS.match(android("mt9999")).status()).isEqualTo("SOC_UNKNOWN");
        assertThat(TIERS.match(android("SM7325")).status()).isEqualTo("SOC_DISABLED");
        assertThat(TIERS.match(new PhoneCalibrationPolicy.Hardware("web", "x", List.of("SM8550"))).status())
                .isEqualTo("PLATFORM_UNSUPPORTED");
        assertThat(TIERS.match(null).matched()).isFalse();
    }

    @Test void aliasesStayUniqueVerifiedAndWithinTheTierLimit() {
        assertThatThrownBy(() -> policy(List.of(tier("a", "1", "2", true, chip("x", "X", "SM8550")),
                tier("b", "1", "2", true, chip("y", "Y", " sm8550 ")))).validate())
                .hasMessageContaining("PHONE_CALIBRATION_SOC_ALIAS_DUPLICATE");
        for (String forbidden : List.of("qcom", "MT6833", "unknown")) {
            assertThatThrownBy(() -> policy(List.of(tier("a", "1", "2", true, chip("x", "X", forbidden)))).validate())
                    .isInstanceOf(BizException.class);
        }
        assertThatThrownBy(() -> policy(List.of(tier("a", "1", "2", true, chip("x", "X", "A1"), chip("x", "Y", "B1")))).validate())
                .isInstanceOf(BizException.class);
        String[] many = IntStream.range(0, 101).mapToObj(i -> "id" + i).toArray(String[]::new);
        assertThatThrownBy(() -> policy(List.of(tier("a", "1", "2", true, chip("x", "X", many)))).validate())
                .isInstanceOf(BizException.class);
        assertThatThrownBy(() -> policy(List.of(tier("a", "5", "2", true, chip("x", "X", "A1")))).validate())
                .isInstanceOf(BizException.class);
        assertThatThrownBy(() -> new PhoneCalibrationPolicy(1,0,List.of(BigDecimal.ONE,BigDecimal.ONE,
                BigDecimal.TEN,new BigDecimal("20")),List.of(),List.of()).validate()).isInstanceOf(BizException.class);
    }

    @Test void aStoredPolicyWithRetiredHardwareRulesReadsWithoutThem() throws Exception {
        var json = "{\"version\":2,\"effectiveAt\":0,\"thresholds\":[19,25,35,47],\"rules\":[{\"id\":\"old\"}],"
                + "\"iosModelRules\":[]}";
        var policy = new com.fasterxml.jackson.databind.ObjectMapper().readValue(json, PhoneCalibrationPolicy.class);
        assertThat(policy.androidSocRules()).isEmpty();
        assertThat(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(policy)).doesNotContain("\"rules\"");
    }
}
