package ffdd.opsconsole.finance.hdpay;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;

public final class HdPaySigner {
    private HdPaySigner() {}

    public static String sign(Map<String, String> fields, String key) {
        if (fields == null || key == null) throw new IllegalArgumentException("HDPAY_SIGN_INPUT_REQUIRED");
        TreeMap<String, String> sorted = new TreeMap<>();
        fields.forEach((name, value) -> {
            if (name != null && !"sign".equals(name) && value != null) sorted.put(name, value);
        });
        StringBuilder canonical = new StringBuilder();
        sorted.forEach((name, value) -> {
            if (!canonical.isEmpty()) canonical.append('&');
            canonical.append(name).append('=').append(value);
        });
        if (!canonical.isEmpty()) canonical.append('&');
        canonical.append(key);
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("MD5")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("MD5 unavailable", ex);
        }
    }

    public static boolean verify(Map<String, String> fields, String key, String supplied) {
        if (supplied == null || !supplied.matches("(?i)[0-9a-f]{32}")) return false;
        byte[] expected = sign(fields, key).getBytes(StandardCharsets.US_ASCII);
        byte[] actual = supplied.toLowerCase(java.util.Locale.ROOT).getBytes(StandardCharsets.US_ASCII);
        return MessageDigest.isEqual(expected, actual);
    }
}
