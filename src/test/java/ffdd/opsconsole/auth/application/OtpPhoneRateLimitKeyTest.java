package ffdd.opsconsole.auth.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class OtpPhoneRateLimitKeyTest {

    @Test
    void everyOtpPurposeCanUseOneEnvironmentAndDestinationQuota() {
        String shared = OtpPhoneRateLimitKey.from("PRODUCTION", "+84", "0901234567");

        assertThat(shared)
                .isEqualTo(OtpPhoneRateLimitKey.from("PRODUCTION", "84", "901234567"))
                .isNotEqualTo(OtpPhoneRateLimitKey.from("ACCEPTANCE", "+84", "901234567"));
    }
}
