package ffdd.opsconsole.finance.hdpay;

/** Literal public IPv4 only: no DNS, request headers, private NIC addresses or callback-host guesses. */
public final class HdPayServerIp {
    private HdPayServerIp() { }

    public static String requirePublicIpv4(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (!value.matches("(?:0|[1-9][0-9]{0,2})(?:\\.(?:0|[1-9][0-9]{0,2})){3}")) throw invalid();
        String[] parts = value.split("\\.");
        int[] octets = new int[4];
        for (int i = 0; i < 4; i++) {
            octets[i] = Integer.parseInt(parts[i]);
            if (octets[i] > 255) throw invalid();
        }
        int a = octets[0], b = octets[1], c = octets[2];
        if (a == 0 || a == 10 || a == 127 || a >= 224
                || (a == 100 && b >= 64 && b <= 127)
                || (a == 169 && b == 254) || (a == 172 && b >= 16 && b <= 31)
                || (a == 192 && b == 168) || (a == 192 && b == 0 && (c == 0 || c == 2))
                || (a == 198 && (b == 18 || b == 19 || (b == 51 && c == 100)))
                || (a == 203 && b == 0 && c == 113)) throw invalid();
        return value;
    }

    private static HdPayGatewayException invalid() {
        return new HdPayGatewayException("HDPAY_SERVER_IP_INVALID", false);
    }
}
