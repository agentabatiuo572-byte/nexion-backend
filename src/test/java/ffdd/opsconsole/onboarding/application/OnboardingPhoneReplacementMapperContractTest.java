package ffdd.opsconsole.onboarding.application;

import static org.assertj.core.api.Assertions.assertThat;

import ffdd.opsconsole.onboarding.mapper.OnboardingCalibrationMapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.Test;

class OnboardingPhoneReplacementMapperContractTest {
    @Test
    void pausedOldPhoneTasksAreSelectedAndCancelledBeforeTheyCanSettle() throws Exception {
        String selected = String.join(" ", OnboardingCalibrationMapper.class
                .getMethod("lockOtherPhoneTasks", Long.class, Long.class)
                .getAnnotation(Select.class).value());
        String cancelled = String.join(" ", OnboardingCalibrationMapper.class
                .getMethod("cancelReplacedPhoneTask", Long.class, Long.class, String.class)
                .getAnnotation(Update.class).value());
        String runtime = String.join(" ", OnboardingCalibrationMapper.class
                .getMethod("clearReplacedPhoneRuntime", Long.class, Long.class)
                .getAnnotation(Update.class).value());

        assertThat(selected).contains("t.user_device_id<>#{keepUserDeviceId}",
                "UPPER(t.status) IN ('CLAIMED','RUNNING')", "d.source_channel='ONBOARDING'",
                "t.source_environment='PRODUCTION'", "FOR UPDATE")
                .doesNotContain("paused_at IS NULL");
        assertThat(cancelled).contains("status='CANCELLED'", "last_error='PHONE_REPLACED'",
                "proof_consumed_at IS NULL", "UPPER(status) IN ('CLAIMED','RUNNING')");
        assertThat(runtime).contains("r.active_task_no=NULL", "r.online_status='OFFLINE'",
                "r.paused_reason='PHONE_REPLACED'");
    }
}
