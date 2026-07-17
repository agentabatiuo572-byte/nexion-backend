package ffdd.opsconsole.emergency.web;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "nexion.geo-block")
public class GeoBlockEnforcementProperties {
    private boolean enabled = true;
    private boolean allowLoopbackWithoutCountry = false;
    private List<String> trustedProxyAddresses = new ArrayList<>(List.of(
            "127.0.0.1", "::1", "0:0:0:0:0:0:0:1"));

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isAllowLoopbackWithoutCountry() {
        return allowLoopbackWithoutCountry;
    }

    public void setAllowLoopbackWithoutCountry(boolean allowLoopbackWithoutCountry) {
        this.allowLoopbackWithoutCountry = allowLoopbackWithoutCountry;
    }

    public List<String> getTrustedProxyAddresses() {
        return trustedProxyAddresses;
    }

    public void setTrustedProxyAddresses(List<String> trustedProxyAddresses) {
        this.trustedProxyAddresses = trustedProxyAddresses == null ? new ArrayList<>() : new ArrayList<>(trustedProxyAddresses);
    }

    public boolean isTrustedProxy(String remoteAddress) {
        return remoteAddress != null && trustedProxyAddresses.stream()
                .anyMatch(rule -> matchesAddressRule(remoteAddress.trim(), rule));
    }

    public boolean isLoopback(String remoteAddress) {
        return "127.0.0.1".equals(remoteAddress)
                || "::1".equals(remoteAddress)
                || "0:0:0:0:0:0:0:1".equals(remoteAddress);
    }

    private boolean matchesAddressRule(String remoteAddress, String rawRule) {
        if (rawRule == null || rawRule.isBlank()) {
            return false;
        }
        String rule = rawRule.trim();
        if (rule.equalsIgnoreCase(remoteAddress)) {
            return true;
        }
        try {
            InetAddress remote = InetAddress.getByName(remoteAddress);
            int slash = rule.indexOf('/');
            if (slash < 0) {
                return Arrays.equals(remote.getAddress(), InetAddress.getByName(rule).getAddress());
            }
            InetAddress network = InetAddress.getByName(rule.substring(0, slash));
            byte[] remoteBytes = remote.getAddress();
            byte[] networkBytes = network.getAddress();
            int prefix = Integer.parseInt(rule.substring(slash + 1));
            if (remoteBytes.length != networkBytes.length || prefix < 0 || prefix > remoteBytes.length * 8) {
                return false;
            }
            int wholeBytes = prefix / 8;
            int remainder = prefix % 8;
            for (int i = 0; i < wholeBytes; i++) {
                if (remoteBytes[i] != networkBytes[i]) {
                    return false;
                }
            }
            if (remainder == 0) {
                return true;
            }
            int mask = 0xff << (8 - remainder);
            return (remoteBytes[wholeBytes] & mask) == (networkBytes[wholeBytes] & mask);
        } catch (UnknownHostException | NumberFormatException ex) {
            return false;
        }
    }
}
