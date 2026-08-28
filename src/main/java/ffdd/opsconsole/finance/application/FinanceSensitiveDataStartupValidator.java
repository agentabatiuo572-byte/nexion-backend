package ffdd.opsconsole.finance.application;

import ffdd.opsconsole.finance.mapper.VietnamPaymentMapper;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Fails startup before the payment rail is advertised as healthy when the
 * configured finance key cannot open an active VietQR receiving account.
 */
@Component
@RequiredArgsConstructor
public class FinanceSensitiveDataStartupValidator implements SmartInitializingSingleton {
    private final VietnamPaymentMapper mapper;
    private final FinanceSensitiveDataCipher cipher;

    @Override
    public void afterSingletonsInstantiated() {
        validate();
    }

    void validate() {
        cipher.validateConfiguration();
        List<Map<String, Object>> accounts = mapper.listActiveVietQrAccountsForKeyValidation();
        if (accounts == null) {
            throw new IllegalStateException("ACTIVE_VIETQR_ACCOUNT_VALIDATION_UNAVAILABLE");
        }
        for (Map<String, Object> account : accounts) {
            long id = longValue(account == null ? null : account.get("id"));
            String encrypted = text(account == null ? null : account.get("accountNumberEncrypted"));
            String accountHash = text(account == null ? null : account.get("accountNumberHash"));
            if (id <= 0 || !StringUtils.hasText(encrypted) || !StringUtils.hasText(accountHash)) {
                throw failure(id);
            }
            try {
                String accountNumber = cipher.decrypt(encrypted, accountHash);
                if (!StringUtils.hasText(accountNumber)) throw failure(id);
            } catch (RuntimeException ex) {
                throw failure(id);
            }
        }
    }

    private IllegalStateException failure(long id) {
        return new IllegalStateException("ACTIVE_VIETQR_ACCOUNT_DECRYPTION_FAILED:id=" + id);
    }

    private long longValue(Object value) {
        if (value instanceof Number number) return number.longValue();
        try {
            return Long.parseLong(text(value));
        } catch (NumberFormatException ex) {
            return 0L;
        }
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }
}
