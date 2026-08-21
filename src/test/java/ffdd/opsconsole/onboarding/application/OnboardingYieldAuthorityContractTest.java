package ffdd.opsconsole.onboarding.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * P1 contract: the E2 admin projection must edit the exact rows consumed by
 * onboarding calibration. This is intentionally a source contract until the
 * existing mapper integration fixture is promoted to a database-backed test.
 */
class OnboardingYieldAuthorityContractTest {
    @Test
    void e2PhoneTierProjectionDoesNotWriteTheLegacyDuplicateTable() throws Exception {
        String mapper = Files.readString(Path.of("src/main/java/ffdd/opsconsole/device/mapper/DeviceCatalogMapper.java"));
        String service = Files.readString(Path.of("src/main/java/ffdd/opsconsole/device/application/OpsDeviceService.java"));

        assertThat(mapper).contains("nx_onboarding_phone_tier_config");
        assertThat(mapper).contains("nx_onboarding_yield_comparison_config");
        assertThat(service).contains("comparisons");
        assertThat(service).doesNotContain("nx_admin_phone_tier_reward");
    }

    @Test
    void appProjectionIncludesTheSameComparisonRowsAsCalibration() throws Exception {
        String service = Files.readString(Path.of("src/main/java/ffdd/opsconsole/device/application/OpsDeviceService.java"));
        String calibration = Files.readString(Path.of("src/main/java/ffdd/opsconsole/onboarding/application/OnboardingCalibrationService.java"));

        assertThat(service).contains("nx_onboarding_yield_comparison_config");
        assertThat(calibration).contains("activeComparisons");
        assertThat(service).contains("dailyUsdt").contains("dailyNex");
    }

    @Test
    void adminWritesUseActiveRowsAndRevisionCompareAndSwap() throws Exception {
        String mapper = Files.readString(Path.of("src/main/java/ffdd/opsconsole/device/mapper/DeviceCatalogMapper.java"));
        String service = Files.readString(Path.of("src/main/java/ffdd/opsconsole/device/application/OpsDeviceService.java"));

        assertThat(mapper).contains("active=1 AND is_deleted=0")
                .contains("revision=#{expectedRevision}")
                .contains("revision=revision+1");
        assertThat(service).contains("PHONE_TIER_VERSION_CONFLICT")
                .contains("YIELD_COMPARISON_VERSION_CONFLICT");
    }
}
