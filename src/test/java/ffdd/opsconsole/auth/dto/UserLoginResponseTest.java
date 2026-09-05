package ffdd.opsconsole.auth.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class UserLoginResponseTest {
    @Test
    void registrationReceiptIsOptionalAndContainsOnlySafeFacts() {
        var response = new UserLoginResponse("access", "Bearer",
                new UserLoginResponse.UserSession(1L, "+84", "912345678", "NexGrid"));
        var receipt = new UserLoginResponse.RegistrationReceipt(
                "NXAB12CD34EF", "A•••", "PRODUCTION", "PENDING_REVIEW",
                new BigDecimal("1.25"), new BigDecimal("20"));

        assertThat(response.registrationReceipt()).isNull();
        assertThat(response.withRegistrationReceipt(receipt).registrationReceipt()).isEqualTo(receipt);
    }
}
