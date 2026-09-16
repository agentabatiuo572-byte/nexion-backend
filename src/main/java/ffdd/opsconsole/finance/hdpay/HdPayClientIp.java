package ffdd.opsconsole.finance.hdpay;

import ffdd.opsconsole.shared.security.GatewaySecurityProperties;
import jakarta.servlet.http.HttpServletRequest;
import java.net.InetAddress;

/** Resolve the original request IP only across an explicitly trusted proxy boundary. */
public final class HdPayClientIp {
    private HdPayClientIp() { }

    public static String resolve(HttpServletRequest request, GatewaySecurityProperties gateway) {
        String remote = request == null ? null : request.getRemoteAddr();
        if (request != null && gateway.isTrustedProxy(remote)) {
            String forwarded = literal(request.getHeader("X-Nexion-Client-IP"));
            if (forwarded == null) {
                String chain = request.getHeader("X-Forwarded-For");
                forwarded = literal(chain == null ? null : chain.split(",", 2)[0]);
            }
            if (forwarded != null) return forwarded;
        }
        return requireLiteral(remote);
    }

    public static String requireLiteral(String value) {
        String result = literal(value);
        if (result == null) throw new HdPayGatewayException("HDPAY_CLIENT_IP_INVALID", false);
        return result;
    }

    static String literal(String value) {
        if (value == null) return null;
        String candidate = value.trim();
        if (candidate.length() > 45) return null;
        if (candidate.matches("[0-9]{1,3}(\\.[0-9]{1,3}){3}")) {
            for (String octet : candidate.split("\\.")) if (Integer.parseInt(octet) > 255) return null;
            return candidate;
        }
        // A colon and literal-only alphabet prevent DNS resolution and zone identifiers.
        if (candidate.contains(":") && candidate.matches("[0-9A-Fa-f:.]+")) {
            try { InetAddress.getByName(candidate); return candidate; }
            catch (java.net.UnknownHostException invalid) { return null; }
        }
        return null;
    }
}
