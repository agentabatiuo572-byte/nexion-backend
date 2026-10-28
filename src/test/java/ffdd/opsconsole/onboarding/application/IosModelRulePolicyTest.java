package ffdd.opsconsole.onboarding.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.shared.exception.BizException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

class IosModelRulePolicyTest {
    static PhoneCalibrationPolicy.IosModelRule ios(String id, String model, String min, String max, String... aliases) {
        return new PhoneCalibrationPolicy.IosModelRule(id, model, List.of(aliases), new BigDecimal(min), new BigDecimal(max),
                true, "OPERATOR_PRESET", "APPLE_RELEASED_HISTORICAL", null);
    }
    static PhoneCalibrationPolicy policy(List<PhoneCalibrationPolicy.AndroidSocRule> rules, List<PhoneCalibrationPolicy.IosModelRule> ios) {
        return new PhoneCalibrationPolicy(3, 0, List.of(new BigDecimal("19"), new BigDecimal("25"),
                new BigDecimal("35"), new BigDecimal("110")), rules, ios);
    }
    static PhoneCalibrationPolicy.Hardware iphone(String model) {
        return new PhoneCalibrationPolicy.Hardware("ios", model, List.of());
    }
    private final PhoneCalibrationPolicy policy = policy(List.of(), List.of(
            ios("ios-iphone-16-pro", "iPhone 16 Pro", "100", "120", "iPhone17,1"),
            ios("ios-iphone-16-pro-max", "iPhone 16 Pro Max", "105", "125", "iPhone17,2"),
            ios("ios-iphone-air", "iPhone Air", "105", "125"),
            ios("ios-iphone-duo", "iPhone Duo", "130", "150")));

    @Test void modelOrVerifiedIdentifierMatchesWithoutSocGpuOrMemory() {
        var byName = policy.match(iphone(" iphone  16 PRO "));
        assertThat(byName.matched()).isTrue();
        assertThat(byName.range()).isTrue();
        assertThat(byName.ruleId()).isEqualTo("ios-iphone-16-pro");
        assertThat(byName.model()).isEqualTo("iPhone 16 Pro");
        assertThat(byName.computeValue()).isNull();
        assertThat(byName.tier()).isNull();
        assertThat(policy.match(iphone("iPhone17,1")).ruleId()).isEqualTo("ios-iphone-16-pro");
        assertThat(policy.possibleTiers(byName)).containsExactly(4, 5);
    }

    @Test void neighbouringModelsAreNeverMatchedByContainment() {
        assertThat(policy.match(iphone("iPhone 16 Pro Max")).ruleId()).isEqualTo("ios-iphone-16-pro-max");
        assertThat(policy.match(iphone("iPhone 16")).status()).isEqualTo("MODEL_UNKNOWN");
        assertThat(policy.match(iphone("iPhone 16 Pro 256GB")).status()).isEqualTo("MODEL_UNKNOWN");
        assertThat(policy.match(iphone("Air")).status()).isEqualTo("MODEL_UNKNOWN");
        assertThat(policy.match(iphone("iPhone Duo")).ruleId()).isEqualTo("ios-iphone-duo");
    }

    @Test void genericEmptyUnknownAndDisabledModelsGetNoAward() {
        for (String model : List.of("", "   ", "iPhone", "Apple iPhone", "iPhone99,1")) {
            var match = policy.match(iphone(model));
            assertThat(match.matched()).as(model).isFalse();
            assertThat(match.computeValue()).isNull();
            assertThat(policy.possibleTiers(match)).isEmpty();
        }
        var disabled = policy(List.of(), List.of(new PhoneCalibrationPolicy.IosModelRule("ios-iphone-6", "iPhone 6",
                List.of(), BigDecimal.TEN, new BigDecimal("20"), false, "OPERATOR_PRESET", null, null)));
        assertThat(disabled.match(iphone("iPhone 6")).status()).isEqualTo("MODEL_DISABLED");
    }

    @Test void androidUsesOnlyChipTiersEvenWhenItReportsAnIPhoneName() {
        var mixed = policy(List.of(PhoneCalibrationPolicyTest.tier("android-x", "30", "40", true,
                        PhoneCalibrationPolicyTest.chip("chip-x", "Chip X", "SM8550"))),
                List.of(ios("ios-iphone-16-pro", "iPhone 16 Pro", "100", "120")));
        var android = mixed.match(new PhoneCalibrationPolicy.Hardware("android", "iPhone 16 Pro", List.of("SM8550")));
        assertThat(android.ruleId()).isEqualTo("android-x");
        assertThat(android.method()).isEqualTo("ANDROID_SOC_RANGE");
        assertThat(mixed.match(new PhoneCalibrationPolicy.Hardware("android", "iPhone 16 Pro", List.of())).matched()).isFalse();
    }

    @Test void drawsStayInsideTheClosedRangeWithTwoDecimals() {
        var match = policy.match(iphone("iPhone 16 Pro"));
        var random = new SplittableRandom(7);
        var seen = new HashSet<BigDecimal>();
        for (int i = 0; i < 5000; i++) {
            BigDecimal value = PhoneCalibrationPolicy.draw(match, random);
            assertThat(value.scale()).isEqualTo(2);
            assertThat(PhoneCalibrationPolicy.within(match, value)).isTrue();
            seen.add(value);
        }
        assertThat(seen).contains(new BigDecimal("100.00"), new BigDecimal("120.00"));
        var fixed = policy(List.of(), List.of(ios("fixed", "iPhone 6", "12.5", "12.5"))).match(iphone("iPhone 6"));
        assertThat(PhoneCalibrationPolicy.draw(fixed, random)).isEqualByComparingTo("12.5");
        assertThat(PhoneCalibrationPolicy.within(match, new BigDecimal("99.99"))).isFalse();
        assertThat(PhoneCalibrationPolicy.within(match, new BigDecimal("110.005"))).isFalse();
    }

    @Test void invalidRangesNamesAndDuplicatesAreRejected() {
        for (var bad : List.of(ios("a", "iPhone 6", "20", "10"), ios("a", "iPhone 6", "0", "10"),
                ios("a", "iPhone 6", "1.234", "10"), ios("a", "iPhone", "1", "10"), ios("a", " ", "1", "10"),
                ios("a b", "iPhone 6", "1", "10"), ios("a", "iPhone 6", "1", "10", "iphone"))) {
            assertThatThrownBy(() -> policy(List.of(), List.of(bad)).validate()).isInstanceOf(BizException.class);
        }
        assertThatThrownBy(() -> policy(List.of(), List.of(ios("a", "iPhone 6", "1", "2"), ios("b", " IPHONE 6", "1", "2"))).validate())
                .hasMessageContaining("PHONE_CALIBRATION_IOS_MODEL_DUPLICATE");
        assertThatThrownBy(() -> policy(List.of(), List.of(ios("a", "iPhone 6", "1", "2", "iPhone7,2"),
                ios("b", "iPhone 6 Plus", "1", "2", "iphone7,2"))).validate())
                .hasMessageContaining("PHONE_CALIBRATION_IOS_MODEL_DUPLICATE");
        assertThatThrownBy(() -> policy(List.of(PhoneCalibrationPolicyTest.tier("same", "1", "3", true,
                PhoneCalibrationPolicyTest.chip("chip-x", "Chip X", "SM8550"))),
                List.of(ios("same", "iPhone 6", "1", "2"))).validate()).isInstanceOf(BizException.class);
        assertThatThrownBy(() -> policy(List.of(), List.of(new PhoneCalibrationPolicy.IosModelRule("a", "iPhone 6", List.of(),
                BigDecimal.ONE, BigDecimal.TEN, true, "MEASURED", null, null))).validate()).isInstanceOf(BizException.class);
    }

    @Test void storedPoliciesWithoutIosRulesStillReadAndRoundTrip() throws Exception {
        var json = new ObjectMapper();
        var old = json.readValue("{\"version\":1,\"effectiveAt\":0,\"thresholds\":[19,25,35,47],\"rules\":[]}",
                PhoneCalibrationPolicy.class);
        assertThat(old.iosModelRules()).isEmpty();
        var copy = json.readValue(json.writeValueAsString(policy), PhoneCalibrationPolicy.class);
        assertThat(copy).isEqualTo(policy);
        assertThat(json.writeValueAsString(policy.match(iphone("iPhone 16 Pro")))).doesNotContain("\"matched\"", "\"range\"");
    }

    @Test void presetCatalogCoversEverySoldIphoneFromIphone6Once() {
        var catalog = IosModelPresetCatalog.load();
        var names = catalog.models().stream().map(PhoneCalibrationPolicy.IosModelRule::model).toList();
        assertThat(names).hasSize(47).doesNotHaveDuplicates()
                .contains("iPhone 6", "iPhone 6 Plus", "iPhone SE (1st generation)", "iPhone SE (2nd generation)",
                        "iPhone SE (3rd generation)", "iPhone 12 mini", "iPhone 13 mini", "iPhone 16 Pro", "iPhone Air",
                        "iPhone 17e", "iPhone 18 Pro Max", "iPhone Duo")
                .doesNotContain("iPhone 5s", "iPhone");
        var sixteenPro = catalog.models().stream().filter(m -> m.model().equals("iPhone 16 Pro")).findFirst().orElseThrow();
        assertThat(sixteenPro.minComputeValue()).isEqualByComparingTo("100");
        assertThat(sixteenPro.maxComputeValue()).isEqualByComparingTo("120");
        assertThat(catalog.models()).allSatisfy(m -> {
            assertThat(m.valueSource()).isEqualTo("OPERATOR_PRESET");
            assertThat(m.enabled()).isTrue();
        });
        assertThat(catalog.models().stream().filter(m -> "APPLE_CURRENT_SNAPSHOT".equals(m.catalogClass())).count()).isEqualTo(7);
        var withCatalog = policy(List.of(), new ArrayList<>(catalog.models()));
        assertThat(withCatalog.match(iphone("iPhone17,1")).model()).isEqualTo("iPhone 16 Pro");
        assertThat(withCatalog.match(iphone("iPhone12,8")).model()).isEqualTo("iPhone SE (2nd generation)");
    }
}
