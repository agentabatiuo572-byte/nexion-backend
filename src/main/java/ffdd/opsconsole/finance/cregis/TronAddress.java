package ffdd.opsconsole.finance.cregis;

import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;

/** TRON wire addresses retain their Base58Check case; hex is only an equivalent encoding. */
public final class TronAddress {
    private static final String ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz";
    private static final BigInteger BASE = BigInteger.valueOf(58);
    private TronAddress() { }

    public static String normalize(String address) {
        return fromHex(toHex(address));
    }

    public static String toHex(String address) {
        if (address != null && address.matches("(?i)41[0-9a-f]{40}")) return address.toLowerCase(java.util.Locale.ROOT);
        if (address == null || !address.matches("T[1-9A-HJ-NP-Za-km-z]{33}")) throw invalid();
        BigInteger number = BigInteger.ZERO;
        for (char c : address.toCharArray()) number = number.multiply(BASE).add(BigInteger.valueOf(ALPHABET.indexOf(c)));
        byte[] decoded = number.toByteArray();
        if (decoded.length == 26 && decoded[0] == 0) decoded = Arrays.copyOfRange(decoded, 1, decoded.length);
        if (decoded.length != 25 || decoded[0] != 0x41) throw invalid();
        byte[] payload = Arrays.copyOf(decoded, 21);
        if (!MessageDigest.isEqual(Arrays.copyOfRange(decoded,21,25), Arrays.copyOf(sha256(sha256(payload)),4))) throw invalid();
        return HexFormat.of().formatHex(payload);
    }

    public static String fromHex(String hex) {
        if (hex == null || !hex.matches("(?i)41[0-9a-f]{40}")) throw invalid();
        byte[] payload = HexFormat.of().parseHex(hex);
        byte[] bytes = Arrays.copyOf(payload,25);
        System.arraycopy(sha256(sha256(payload)),0,bytes,21,4);
        BigInteger number = new BigInteger(1,bytes);
        StringBuilder encoded = new StringBuilder();
        while (number.signum() > 0) {
            BigInteger[] parts = number.divideAndRemainder(BASE);
            encoded.append(ALPHABET.charAt(parts[1].intValue())); number = parts[0];
        }
        return encoded.reverse().toString();
    }

    private static byte[] sha256(byte[] value) {
        try { return MessageDigest.getInstance("SHA-256").digest(value); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("TRON_ADDRESS_INVALID"); }
}
