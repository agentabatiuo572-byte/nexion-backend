package ffdd.opsconsole.finance.cregis;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public final class CregisSigner {
    public String sign(String apiKey, Map<String, ?> parameters) {
        if (apiKey == null || apiKey.isEmpty() || parameters == null) {
            throw new IllegalArgumentException("CREGIS_SIGNATURE_INPUT_INVALID");
        }
        StringBuilder canonical = new StringBuilder(apiKey);
        List<Map.Entry<String, ?>> entries = new ArrayList<>(parameters.entrySet());
        entries.sort(Comparator.comparing(Map.Entry::getKey));
        for (Map.Entry<String, ?> entry : entries) {
            if ("sign".equals(entry.getKey()) || entry.getValue() == null) continue;
            String value = scalar(entry.getValue());
            if (value.isEmpty()) continue;
            canonical.append(entry.getKey()).append(value);
        }
        try {
            byte[] digest = MessageDigest.getInstance("MD5")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest).toLowerCase(Locale.ROOT);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("CREGIS_SIGNATURE_ALGORITHM_UNAVAILABLE", impossible);
        }
    }

    public boolean verify(String apiKey, Map<String, ?> parameters, String suppliedSignature) {
        if (suppliedSignature == null || !suppliedSignature.matches("(?i)[0-9a-f]{32}")) return false;
        byte[] expected = sign(apiKey, parameters).getBytes(StandardCharsets.US_ASCII);
        byte[] supplied = suppliedSignature.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.US_ASCII);
        return MessageDigest.isEqual(expected, supplied);
    }

    private String scalar(Object value) {
        if (value instanceof String text) return text;
        if (value instanceof Character character) return character.toString();
        if (value instanceof Boolean flag) return flag.toString();
        if (value instanceof BigDecimal decimal) return decimal.stripTrailingZeros().toPlainString();
        if (value instanceof BigInteger integer) return integer.toString();
        if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long) {
            return value.toString();
        }
        if (value instanceof Float number) {
            if (!Float.isFinite(number)) throw unsupported();
            return BigDecimal.valueOf(number.doubleValue()).stripTrailingZeros().toPlainString();
        }
        if (value instanceof Double number) {
            if (!Double.isFinite(number)) throw unsupported();
            return BigDecimal.valueOf(number).stripTrailingZeros().toPlainString();
        }
        throw unsupported();
    }

    private IllegalArgumentException unsupported() {
        return new IllegalArgumentException("CREGIS_SIGNATURE_VALUE_UNSUPPORTED");
    }
}
