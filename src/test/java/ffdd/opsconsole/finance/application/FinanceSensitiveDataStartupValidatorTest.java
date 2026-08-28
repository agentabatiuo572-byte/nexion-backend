package ffdd.opsconsole.finance.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.finance.mapper.VietnamPaymentMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

class FinanceSensitiveDataStartupValidatorTest {

    private final VietnamPaymentMapper mapper = mock(VietnamPaymentMapper.class);
    private final FinanceSensitiveDataCipher cipher = mock(FinanceSensitiveDataCipher.class);
    private final FinanceSensitiveDataStartupValidator validator =
            new FinanceSensitiveDataStartupValidator(mapper, cipher);

    @Test
    void validatesDuringSingletonInitializationBeforeTheWebContextFinishesStarting() {
        when(mapper.listActiveVietQrAccountsForKeyValidation()).thenReturn(List.of());

        assertThat(validator).isInstanceOf(SmartInitializingSingleton.class);
        assertThatCode(validator::afterSingletonsInstantiated).doesNotThrowAnyException();

        verify(mapper).listActiveVietQrAccountsForKeyValidation();
    }

    @Test
    void decryptsEveryActiveVietQrAccountBeforeAdvertisingStartupSuccess() {
        when(mapper.listActiveVietQrAccountsForKeyValidation()).thenReturn(List.of(
                Map.of(
                        "id", 94L,
                        "accountNumberEncrypted", "opaque-ciphertext",
                        "accountNumberHash", "account-hash")));
        when(cipher.decrypt("opaque-ciphertext", "account-hash")).thenReturn("97043612074");

        assertThatCode(validator::validate).doesNotThrowAnyException();

        verify(cipher).validateConfiguration();
        verify(cipher).decrypt("opaque-ciphertext", "account-hash");
    }

    @Test
    void failsStartupClosedWhenTheConfiguredKeyCannotDecryptAnActiveAccount() {
        when(mapper.listActiveVietQrAccountsForKeyValidation()).thenReturn(List.of(
                Map.of(
                        "id", 94L,
                        "accountNumberEncrypted", "opaque-ciphertext",
                        "accountNumberHash", "account-hash")));
        when(cipher.decrypt("opaque-ciphertext", "account-hash"))
                .thenThrow(new IllegalStateException("FINANCE_DATA_DECRYPTION_FAILED"));

        assertThatThrownBy(validator::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("ACTIVE_VIETQR_ACCOUNT_DECRYPTION_FAILED:id=94")
                .hasNoCause();
    }

    @Test
    void acceptsAnEmptyActiveAccountSetWithoutInventingPaymentAvailability() {
        when(mapper.listActiveVietQrAccountsForKeyValidation()).thenReturn(List.of());

        assertThatCode(validator::validate).doesNotThrowAnyException();

        verify(cipher).validateConfiguration();
    }

    @Test
    void failsClosedOnMissingKeyEvenWhenThereAreNoActiveAccounts() {
        FinanceSensitiveDataStartupValidator missingKeyValidator =
                new FinanceSensitiveDataStartupValidator(mapper, new FinanceSensitiveDataCipher(""));
        when(mapper.listActiveVietQrAccountsForKeyValidation()).thenReturn(List.of());

        assertThatThrownBy(missingKeyValidator::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("FINANCE_DATA_ENCRYPTION_KEY_REQUIRED")
                .hasNoCause();
    }

    @Test
    void failsClosedWhenActiveAccountValidationQueryReturnsNoResultCollection() {
        when(mapper.listActiveVietQrAccountsForKeyValidation()).thenReturn(null);

        assertThatThrownBy(validator::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("ACTIVE_VIETQR_ACCOUNT_VALIDATION_UNAVAILABLE")
                .hasNoCause();
    }

    @Test
    void springContextRefreshFailsBeforeCompletionWhenValidationFails() {
        when(mapper.listActiveVietQrAccountsForKeyValidation()).thenReturn(null);
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.registerBean(VietnamPaymentMapper.class, () -> mapper);
        context.registerBean(FinanceSensitiveDataCipher.class, () -> cipher);
        context.registerBean(FinanceSensitiveDataStartupValidator.class);
        try {
            assertThatThrownBy(context::refresh)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("ACTIVE_VIETQR_ACCOUNT_VALIDATION_UNAVAILABLE")
                    .hasNoCause();
            assertThat(context.isActive()).isFalse();
        } finally {
            context.close();
        }
    }
}
