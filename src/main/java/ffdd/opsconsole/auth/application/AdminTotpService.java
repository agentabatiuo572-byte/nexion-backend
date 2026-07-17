package ffdd.opsconsole.auth.application;

import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.time.Instant;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class AdminTotpService {
    private static final char[] BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567".toCharArray();
    private static final SecureRandom RANDOM = new SecureRandom();

    public String generateSecret() {
        byte[] bytes = new byte[20];
        RANDOM.nextBytes(bytes);
        return encodeBase32(bytes);
    }

    public boolean verify(String secret, String code) {
        return matchingCounter(secret, code, Instant.now()) != null;
    }

    boolean verify(String secret, String code, Instant now) {
        return matchingCounter(secret, code, now) != null;
    }

    public Long matchingCounter(String secret, String code) {
        return matchingCounter(secret, code, Instant.now());
    }

    Long matchingCounter(String secret, String code, Instant now) {
        if (!StringUtils.hasText(secret) || code == null || !code.matches("\\d{6}")) {
            return null;
        }
        long counter = now.getEpochSecond() / 30L;
        int candidate;
        try {
            candidate = Integer.parseInt(code);
        } catch (NumberFormatException ex) {
            return null;
        }
        for (long offset = -1; offset <= 1; offset++) {
            long candidateCounter = counter + offset;
            if ((candidate ^ codeAt(secret, candidateCounter)) == 0) {
                return candidateCounter;
            }
        }
        return null;
    }

    public String provisioningUri(String issuer, String username, String secret) {
        String safeIssuer = encode(StringUtils.hasText(issuer) ? issuer.trim() : "Nexion Ops");
        String safeUsername = encode(StringUtils.hasText(username) ? username.trim() : "admin");
        return "otpauth://totp/" + safeIssuer + ":" + safeUsername
                + "?secret=" + secret + "&issuer=" + safeIssuer + "&algorithm=SHA1&digits=6&period=30";
    }

    private int codeAt(String secret, long counter) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(decodeBase32(secret), "HmacSHA1"));
            byte[] digest = mac.doFinal(ByteBuffer.allocate(Long.BYTES).putLong(counter).array());
            int offset = digest[digest.length - 1] & 0x0f;
            int binary = ((digest[offset] & 0x7f) << 24)
                    | ((digest[offset + 1] & 0xff) << 16)
                    | ((digest[offset + 2] & 0xff) << 8)
                    | (digest[offset + 3] & 0xff);
            return binary % 1_000_000;
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("TOTP unavailable", ex);
        }
    }

    private String encodeBase32(byte[] input) {
        StringBuilder result = new StringBuilder((input.length * 8 + 4) / 5);
        int buffer = 0;
        int bits = 0;
        for (byte value : input) {
            buffer = (buffer << 8) | (value & 0xff);
            bits += 8;
            while (bits >= 5) {
                result.append(BASE32[(buffer >> (bits - 5)) & 31]);
                bits -= 5;
            }
        }
        if (bits > 0) {
            result.append(BASE32[(buffer << (5 - bits)) & 31]);
        }
        return result.toString();
    }

    private byte[] decodeBase32(String value) {
        String normalized = value.replace("=", "").replaceAll("\\s+", "").toUpperCase();
        byte[] output = new byte[normalized.length() * 5 / 8];
        int buffer = 0;
        int bits = 0;
        int index = 0;
        for (char ch : normalized.toCharArray()) {
            int digit = ch >= 'A' && ch <= 'Z' ? ch - 'A' : ch >= '2' && ch <= '7' ? ch - '2' + 26 : -1;
            if (digit < 0) {
                throw new IllegalArgumentException("invalid base32 secret");
            }
            buffer = (buffer << 5) | digit;
            bits += 5;
            if (bits >= 8) {
                output[index++] = (byte) ((buffer >> (bits - 8)) & 0xff);
                bits -= 8;
            }
        }
        return output;
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
