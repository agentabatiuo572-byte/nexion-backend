package ffdd.opsconsole.auth.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class OtpMaxVerifyAttemptsContractTest {
    private static final String KEY = "auth.risk.otp_max_verify_attempts";

    private static String source(String relativePath) throws Exception {
        return Files.readString(Path.of("src/main/java", relativePath));
    }

    @Test
    void everyFormalOtpVerifierUsesTheK2MaximumAttemptsAuthority() throws Exception {
        String userOtp = source("ffdd/opsconsole/user/mapper/UserOpsMapper.java");
        String payoutOtp = source("ffdd/opsconsole/finance/mapper/AppPayoutAddressMapper.java");

        assertThat(source("ffdd/opsconsole/auth/application/AppOtpPolicy.java")).contains(KEY);
        assertThat(userOtp).doesNotContain("attempts<5");
        assertThat(payoutOtp).doesNotContain("attempts<5");
        assertThat(userOtp).contains(KEY).contains("BETWEEN 1 AND 10");
        assertThat(payoutOtp).contains(KEY).contains("BETWEEN 1 AND 10");
    }
}
