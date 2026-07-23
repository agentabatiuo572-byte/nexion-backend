package ffdd.opsconsole.finance.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

final class TopupEventHashing {
    private TopupEventHashing() {
    }

    static String sha256(String canonical) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA256_UNAVAILABLE", ex);
        }
    }
}
