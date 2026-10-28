package ffdd.opsconsole.onboarding.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class AndroidSocPresetCatalogTest {
    @Test void theShippedTiersAreValidSixRangesWithUnambiguousChipGroups() {
        var catalog = AndroidSocPresetCatalog.load();
        assertThat(catalog.tiers()).extracting(PhoneCalibrationPolicy.AndroidSocRule::name)
                .containsExactly("旗舰", "次旗舰", "高端", "中端", "中低端", "入门");
        assertThat(catalog.tiers()).extracting(rule -> rule.minComputeValue().intValue() + "-" + rule.maxComputeValue().intValue())
                .containsExactly("115-140", "95-115", "75-95", "55-75", "38-55", "20-38");
        var aliases = catalog.tiers().stream().flatMap(rule -> rule.chips().stream()).flatMap(chip -> chip.aliases().stream())
                .map(PhoneCalibrationPolicy::normalize).toList();
        assertThat(aliases).doesNotHaveDuplicates().doesNotContain("mt6833", "qcom");
        // A preset policy resolves a real identifier to its tier.
        var policy = new PhoneCalibrationPolicy(1, 0, List.of(new java.math.BigDecimal("30"), new java.math.BigDecimal("60"),
                new java.math.BigDecimal("90"), new java.math.BigDecimal("120")), catalog.tiers(), List.of());
        assertThat(policy.match(new PhoneCalibrationPolicy.Hardware("android", "x", List.of("SM8550"))).ruleId()).isEqualTo("soc-tier-upper");
        assertThat(policy.match(new PhoneCalibrationPolicy.Hardware("android", "x", List.of("MT6833"))).status()).isEqualTo("SOC_UNKNOWN");
    }
}
