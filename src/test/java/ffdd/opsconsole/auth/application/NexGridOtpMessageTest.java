package ffdd.opsconsole.auth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class NexGridOtpMessageTest {

    @Test
    void rendersTheApprovedVietnameseTemplateForVietnam() {
        String content = NexGridOtpMessage.render("+84", "LOGIN-abc", "654321", 5);

        assertThat(content).isEqualTo(
                "Mã xác minh của bạn là 654321, có hiệu lực trong 5 phút. [NexGrid]");
        assertThat(content).hasSizeLessThanOrEqualTo(70);
        assertThat(content).doesNotContain("NEXION", "Nexion");
    }

    @Test
    void vietnameseTemplateNeverExposesTheChallengeNumber() {
        assertThat(NexGridOtpMessage.render("84", "REG-secret-challenge", "123456", 5))
                .isEqualTo("Mã xác minh của bạn là 123456, có hiệu lực trong 5 phút. [NexGrid]")
                .doesNotContain("REG-secret-challenge");
        assertThat(NexGridOtpMessage.render("+84", "RESET-secret-challenge", "123456", 1))
                .isEqualTo("Mã xác minh của bạn là 123456, có hiệu lực trong 1 phút. [NexGrid]")
                .doesNotContain("RESET-secret-challenge");
        assertThat(NexGridOtpMessage.render("+84", "PAYOUT-secret-challenge", "123456", 5))
                .startsWith("Mã xác minh của bạn là 123456")
                .doesNotContain("PAYOUT-secret-challenge");
    }

    @Test
    void keepsTheExistingEnglishTemplateForTheChinaDevelopmentRoute() {
        assertThat(NexGridOtpMessage.render("+86", "LOGIN-hidden", "654321", 5))
                .isEqualTo("NexGrid verification code: 654321. Expires in 5 min. "
                        + "Never share this code with anyone, including NexGrid Support.");
    }

    @Test
    void rejectsInvalidCodeAndTtl() {
        assertThatThrownBy(() -> NexGridOtpMessage.render("+84", "LOGIN-a", "12345", 5))
                .hasMessage("USER_OTP_MESSAGE_INPUT_INVALID");
        assertThatThrownBy(() -> NexGridOtpMessage.render("+84", "LOGIN-a", "123456", 0))
                .hasMessage("USER_OTP_MESSAGE_INPUT_INVALID");
        assertThatThrownBy(() -> NexGridOtpMessage.render("+84", "LOGIN-a", "123456", 16))
                .hasMessage("USER_OTP_MESSAGE_INPUT_INVALID");
    }
}
