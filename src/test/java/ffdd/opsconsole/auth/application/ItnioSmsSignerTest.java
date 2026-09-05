package ffdd.opsconsole.auth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ItnioSmsSignerTest {
    private final ItnioSmsSigner signer = new ItnioSmsSigner();

    @Test
    void signsApiKeySecretAndEpochSecondsWithLowercaseMd5() {
        assertThat(signer.sign("api-key", "api-secret", 1_630_468_800L))
                .isEqualTo("916cca171614e17d6ccea6b02e53d807");
    }

    @Test
    void rejectsBlankCredentialsAndInvalidTimestamp() {
        assertThatThrownBy(() -> signer.sign("", "secret", 1L))
                .hasMessage("ITNIO_SMS_SIGNATURE_INPUT_INVALID");
        assertThatThrownBy(() -> signer.sign("key", "", 1L))
                .hasMessage("ITNIO_SMS_SIGNATURE_INPUT_INVALID");
        assertThatThrownBy(() -> signer.sign("key", "secret", 0L))
                .hasMessage("ITNIO_SMS_SIGNATURE_INPUT_INVALID");
    }
}
