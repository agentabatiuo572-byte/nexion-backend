package ffdd.opsconsole.onboarding.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class OnboardingPhoneActivationContractTest {
    @Test
    void readOnlyResultPathNeverUsesLockingUserLookup() throws Exception {
        String mapper = Files.readString(Path.of(
                "src/main/java/ffdd/opsconsole/onboarding/mapper/OnboardingCalibrationMapper.java"));
        String read = section(mapper, "@Select(\"SELECT COALESCE(sandbox,0)", "Integer userSandbox");
        assertThat(read).doesNotContain("FOR UPDATE");
        assertThat(mapper).contains("Integer lockUserSandbox", "FOR UPDATE");
    }

    @Test
    void activationStateBindsCanonicalPhoneAndTaskRewardsRequireThatActiveBinding() throws Exception {
        String schema = Files.readString(Path.of("scripts/schema.sql"));
        String onboardingMapper = Files.readString(Path.of(
                "src/main/java/ffdd/opsconsole/onboarding/mapper/OnboardingCalibrationMapper.java"));
        String mapper = Files.readString(Path.of(
                "src/main/java/ffdd/opsconsole/device/mapper/AppTaskAssignmentMapper.java"));
        assertThat(schema).contains("activation_status", "user_device_id", "CALIBRATED", "ACTIVE", "DEFERRED");
        assertThat(onboardingMapper).contains("upsertPhoneDevice", "deactivateScopedPhoneDevices",
                "source_environment,run_id", "user_device_id userDeviceId",
                "insertDeferred", "JSON_OBJECT()", "JSON_ARRAY()", "'DEFERRED'");
        assertThat(mapper).contains("UPPER(d.ownership_status) = 'OWNED'", "d.activated_at IS NOT NULL",
                "d.deactivated_at IS NULL", "d.pending_deactivate = 0",
                "oc.user_device_id=d.id", "oc.activation_status='ACTIVE'");
    }

    private String section(String source, String start, String end) {
        int begin = source.indexOf(start);
        int finish = source.indexOf(end, begin + start.length());
        assertThat(begin).isGreaterThanOrEqualTo(0);
        assertThat(finish).isGreaterThan(begin);
        return source.substring(begin, finish);
    }
}
