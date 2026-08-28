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
    private static final String AAD_ENVELOPE_PREFIX = "v2:";
    private static final int MIN_KEY_MATERIAL_LENGTH = 32;
    private final SecureRandom random = new SecureRandom();
    @Value("${nexion.finance.data-encryption-key:${NEXION_FINANCE_DATA_KEY:}}")
    private final String keyMaterial;

    public String encrypt(String plaintext) {
        return encryptInternal(plaintext, null, false);
    }

    public String encrypt(String plaintext, String associatedData) {
        if (!StringUtils.hasText(associatedData)) {
            throw new IllegalArgumentException("FINANCE_DATA_ASSOCIATED_DATA_REQUIRED");
        }
        return encryptInternal(plaintext, associatedData, true);
    }

    private String encryptInternal(
            String plaintext, String associatedData, boolean versioned) {
        requireKeyMaterial();
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key(), new GCMParameterSpec(128, iv));
            if (associatedData != null) {
                cipher.updateAAD(associatedData.getBytes(StandardCharsets.UTF_8));
            }
            byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            String encoded = Base64.getEncoder().encodeToString(
                    ByteBuffer.allocate(iv.length + encrypted.length).put(iv).put(encrypted).array());
            return versioned ? AAD_ENVELOPE_PREFIX + encoded : encoded;
        } catch (Exception ex) {
            throw new IllegalStateException("FINANCE_DATA_ENCRYPTION_FAILED", ex);
        }
    }

    public String decrypt(String ciphertext) {
        if (ciphertext != null && ciphertext.startsWith(AAD_ENVELOPE_PREFIX)) {
            throw new IllegalStateException("FINANCE_DATA_ASSOCIATED_DATA_REQUIRED");
        }
        return decryptInternal(ciphertext, null);
    }

    public String decrypt(String ciphertext, String associatedData) {
        boolean versioned = ciphertext != null && ciphertext.startsWith(AAD_ENVELOPE_PREFIX);
        if (versioned && !StringUtils.hasText(associatedData)) {
            throw new IllegalStateException("FINANCE_DATA_ASSOCIATED_DATA_REQUIRED");
        }
        return decryptInternal(
                versioned ? ciphertext.substring(AAD_ENVELOPE_PREFIX.length()) : ciphertext,
                versioned ? associatedData : null);
    }

    /** Verifies that the service has usable key material before exposing finance APIs. */
    public void validateConfiguration() {
        requireKeyMaterial();
    }

    private String decryptInternal(String ciphertext, String associatedData) {
        requireKeyMaterial();
        try {
            byte[] packed = Base64.getDecoder().decode(ciphertext);
            if (packed.length <= IV_BYTES) {
                throw new IllegalArgumentException("ciphertext is too short");
            }
            ByteBuffer buffer = ByteBuffer.wrap(packed);
            byte[] iv = new byte[IV_BYTES];
            buffer.get(iv);
            byte[] encrypted = new byte[buffer.remaining()];
            buffer.get(encrypted);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, iv));
            if (associatedData != null) {
                cipher.updateAAD(associatedData.getBytes(StandardCharsets.UTF_8));
            }
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (Exception ex) {
            throw new IllegalStateException("FINANCE_DATA_DECRYPTION_FAILED", ex);
        }
    }

    private void requireKeyMaterial() {
        if (!StringUtils.hasText(keyMaterial)
                || keyMaterial.trim().length() < MIN_KEY_MATERIAL_LENGTH) {
            throw new IllegalStateException("FINANCE_DATA_ENCRYPTION_KEY_REQUIRED");
        }
    }

    private SecretKey key() throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(keyMaterial.trim().getBytes(StandardCharsets.UTF_8));
        return new SecretKeySpec(digest, "AES");
    }
}
