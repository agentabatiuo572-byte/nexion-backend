package ffdd.opsconsole.auth.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public final class ItnioSmsSigner {
    public String sign(String apiKey, String apiSecret, long epochSeconds) {
        if (!StringUtils.hasText(apiKey) || !StringUtils.hasText(apiSecret) || epochSeconds <= 0) {
            throw new IllegalArgumentException("ITNIO_SMS_SIGNATURE_INPUT_INVALID");
        }
        try {
            byte[] digest = MessageDigest.getInstance("MD5").digest(
                    (apiKey + apiSecret + epochSeconds).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("ITNIO_SMS_SIGNATURE_ALGORITHM_UNAVAILABLE", impossible);
        }
    }
}
