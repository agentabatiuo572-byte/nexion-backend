package ffdd.opsconsole.developer.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.core.env.Environment;

final class WebhookSecretCrypto {
    private static final SecureRandom RANDOM = new SecureRandom();
    private WebhookSecretCrypto() { }
    static boolean configured(Environment environment) {
        return environment != null
                && !environment.getProperty("nexion.developer.webhooks.secret-key", "").isBlank();
    }
    static String encrypt(String plaintext, Environment environment) {
        String configured = environment.getProperty("nexion.developer.webhooks.secret-key", "");
        if (configured.isBlank()) return null;
        try {
            byte[] iv = new byte[12]; RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key(configured), new GCMParameterSpec(128, iv));
            byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] all = new byte[iv.length + encrypted.length];
            System.arraycopy(iv, 0, all, 0, iv.length); System.arraycopy(encrypted, 0, all, iv.length, encrypted.length);
            return Base64.getUrlEncoder().withoutPadding().encodeToString(all);
        } catch (Exception ex) { throw new IllegalStateException("DEVELOPER_WEBHOOK_SECRET_ENCRYPTION_FAILED", ex); }
    }
    private static SecretKeySpec key(String configured) throws Exception {
        return new SecretKeySpec(MessageDigest.getInstance("SHA-256").digest(configured.getBytes(StandardCharsets.UTF_8)), "AES");
    }
}
