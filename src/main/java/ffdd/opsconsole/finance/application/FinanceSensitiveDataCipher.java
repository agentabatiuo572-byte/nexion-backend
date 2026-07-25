package ffdd.opsconsole.finance.application;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
@RequiredArgsConstructor
public class FinanceSensitiveDataCipher {
    private static final int IV_BYTES = 12;
    private final SecureRandom random = new SecureRandom();
    @Value("${nexion.finance.data-encryption-key:${NEXION_FINANCE_DATA_KEY:}}")
    private final String keyMaterial;

    public String encrypt(String plaintext) {
        if (!StringUtils.hasText(keyMaterial)) {
            throw new IllegalStateException("FINANCE_DATA_ENCRYPTION_KEY_REQUIRED");
        }
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key(), new GCMParameterSpec(128, iv));
            byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(
                    ByteBuffer.allocate(iv.length + encrypted.length).put(iv).put(encrypted).array());
        } catch (Exception ex) {
            throw new IllegalStateException("FINANCE_DATA_ENCRYPTION_FAILED", ex);
        }
    }

    private SecretKey key() throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(keyMaterial.trim().getBytes(StandardCharsets.UTF_8));
        return new SecretKeySpec(digest, "AES");
    }
}
