package ffdd.opsconsole.developer.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** Decrypts server-held secrets; the encryption key is supplied by deployment configuration, never returned by an API. */
@Component
public class AesGcmWebhookSecretCodec implements WebhookSecretCodec {
    private final SecretKeySpec key;

    public AesGcmWebhookSecretCodec(Environment environment) {
        String configured = environment.getProperty("nexion.developer.webhooks.secret-key", "");
        try { this.key = configured.isBlank() ? null : new SecretKeySpec(MessageDigest.getInstance("SHA-256").digest(configured.getBytes(StandardCharsets.UTF_8)), "AES"); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }

    @Override
    public String decode(String ciphertext) throws Exception {
        if (key == null || ciphertext == null || ciphertext.isBlank()) throw new IllegalArgumentException("SECRET_UNAVAILABLE");
        byte[] all = Base64.getUrlDecoder().decode(ciphertext);
        if (all.length < 13) throw new IllegalArgumentException("SECRET_UNAVAILABLE");
        byte[] iv = java.util.Arrays.copyOfRange(all, 0, 12), encrypted = java.util.Arrays.copyOfRange(all, 12, all.length);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, iv));
        return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
    }
}
