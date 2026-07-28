package ffdd.opsconsole.finance.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class FinanceSensitiveDataCipherTest {

    @Test
    void encryptDecryptRoundTripUsesAuthenticatedCiphertext() {
        FinanceSensitiveDataCipher cipher =
                new FinanceSensitiveDataCipher("test-only-key-material-with-more-than-32-bytes");

        String encrypted = cipher.encrypt("9704361234567890");

        assertThat(encrypted).isNotEqualTo("9704361234567890");
        assertThat(cipher.decrypt(encrypted)).isEqualTo("9704361234567890");
    }

    @Test
    void decryptRejectsTamperedCiphertext() {
        FinanceSensitiveDataCipher cipher =
                new FinanceSensitiveDataCipher("test-only-key-material-with-more-than-32-bytes");
        String encrypted = cipher.encrypt("9704361234567890");
        String tampered = encrypted.substring(0, encrypted.length() - 2) + "AA";

        assertThatThrownBy(() -> cipher.decrypt(tampered))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("FINANCE_DATA_DECRYPTION_FAILED");
    }

    @Test
    void aadEnvelopeRejectsCiphertextMovedToAnotherAccount() {
        FinanceSensitiveDataCipher cipher =
                new FinanceSensitiveDataCipher("test-only-key-material-with-more-than-32-bytes");
        String encrypted = cipher.encrypt("9704361234567890", "account-hash-1");

        assertThat(cipher.decrypt(encrypted, "account-hash-1"))
                .isEqualTo("9704361234567890");
        assertThatThrownBy(() -> cipher.decrypt(encrypted, "account-hash-2"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("FINANCE_DATA_DECRYPTION_FAILED");
        assertThatThrownBy(() -> cipher.decrypt(encrypted))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("FINANCE_DATA_ASSOCIATED_DATA_REQUIRED");
    }

    @Test
    void weakKeyMaterialIsRejectedBeforeEncryption() {
        FinanceSensitiveDataCipher cipher = new FinanceSensitiveDataCipher("too-short");

        assertThatThrownBy(() -> cipher.encrypt("9704361234567890"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("FINANCE_DATA_ENCRYPTION_KEY_REQUIRED");
    }
}
