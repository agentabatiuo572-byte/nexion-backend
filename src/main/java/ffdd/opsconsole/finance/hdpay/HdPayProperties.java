package ffdd.opsconsole.finance.hdpay;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.Arrays;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Getter
@Setter
@ToString
@Component
@ConfigurationProperties(prefix = "nexion.finance.hdpay")
public class HdPayProperties {
    public static final String PAY_IN_CALLBACK_PATH = "/openapi/v1/payments/hdpay/pay-in/callback";

    public enum Mode {
        DISABLED,
        PROVIDER
    }

    private Mode mode = Mode.DISABLED;
    private String baseUrl = "";
    private String callbackBaseUrl = "";
    private String merchantId = "";
    @ToString.Exclude
    private String md5Key = "";
    private String payType = "BANKQR";
    private String countryCode = "VN";
    /** Exact public hosts allowed to receive the provider callback. */
    private List<String> callbackHosts = new ArrayList<>();
    private List<String> paymentPageHosts = new ArrayList<>();
    /** Optional outbound HTTP proxy scoped to HDPay only. */
    private String proxyHost = "";
    private int proxyPort;
    private int connectTimeoutMs = 1000;
    private int readTimeoutMs = 10_000;
    /** Test-only seam for a loopback HTTP server. Never configure this in a deployed profile. */
    @ToString.Exclude
    private boolean allowInsecureBaseUrlForTests;
    /** Local acceptance only. Production callback hosts must be stable and controlled. */
    @ToString.Exclude
    private boolean allowTemporaryCallbackHostsForTests;
    @Getter(AccessLevel.NONE)
    @Setter(AccessLevel.NONE)
    @ToString.Exclude
    private Environment environment;

    @Autowired
    void captureEnvironment(Environment environment) {
        this.environment = environment;
    }

    public boolean providerMode() {
        return mode == Mode.PROVIDER;
    }

    public boolean ready() {
        if (!providerMode()) return false;
        URI base = uri(baseUrl);
        URI callback = uri(callbackBaseUrl);
        return base != null
                && ("https".equalsIgnoreCase(base.getScheme()) || allowInsecureLoopback(base))
                && cleanPath(base.getPath()).endsWith("/api/order")
                && callback != null
                && "https".equalsIgnoreCase(callback.getScheme())
                && publicCallbackTarget(callback)
                && approvedCallbackHost(callback)
                && callback.getQuery() == null
                && callback.getFragment() == null
                && callback.getUserInfo() == null
                && digits(merchantId)
                && md5Key != null
                && md5Key.length() >= 16
                && "BANKQR".equals(clean(payType).toUpperCase(Locale.ROOT))
                && "VN".equals(clean(countryCode).toUpperCase(Locale.ROOT))
                && validProxyConfiguration()
                && connectTimeoutMs >= 100
                && readTimeoutMs >= 100;
    }

    public String callbackUrl() {
        if (!ready()) return "";
        return trimTrailingSlash(callbackBaseUrl) + PAY_IN_CALLBACK_PATH;
    }

    public boolean isTrustedPaymentPage(String raw) {
        URI candidate = pageUri(raw);
        if (candidate == null
                || !"https".equalsIgnoreCase(candidate.getScheme())
                || candidate.getUserInfo() != null
                || candidate.getFragment() != null
                || candidate.getHost() == null
                || (candidate.getPort() != -1 && candidate.getPort() != 443)) {
            return false;
        }
        Set<String> allowed = new LinkedHashSet<>();
        URI base = uri(baseUrl);
        if (base != null && base.getHost() != null) allowed.add(base.getHost().toLowerCase(Locale.ROOT));
        if (paymentPageHosts != null) {
            paymentPageHosts.stream()
                    .filter(java.util.Objects::nonNull)
                    .map(String::trim)
                    .filter(value -> !value.isEmpty())
                    .map(value -> value.toLowerCase(Locale.ROOT))
                    .forEach(allowed::add);
        }
        return allowed.contains(candidate.getHost().toLowerCase(Locale.ROOT));
    }

    private URI pageUri(String raw) {
        try {
            URI value = URI.create(clean(raw));
            return value.isAbsolute() ? value : null;
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private boolean allowInsecureLoopback(URI value) {
        return allowInsecureBaseUrlForTests
                && "http".equalsIgnoreCase(value.getScheme())
                && ("127.0.0.1".equals(value.getHost()) || "localhost".equalsIgnoreCase(value.getHost()));
    }

    private boolean publicCallbackTarget(URI value) {
        if (value.getPort() != -1 && value.getPort() != 443) return false;
        String host = clean(value.getHost()).toLowerCase(Locale.ROOT);
        if (host.isEmpty()
                || host.contains(":")
                || host.equals("localhost")
                || host.endsWith(".localhost")
                || host.endsWith(".local")
                || host.endsWith(".internal")) {
            return false;
        }
        if (!host.matches("[0-9]{1,3}(?:\\.[0-9]{1,3}){3}")) return true;
        String[] raw = host.split("\\.");
        int[] octets = new int[4];
        for (int i = 0; i < raw.length; i++) {
            octets[i] = Integer.parseInt(raw[i]);
            if (octets[i] > 255) return false;
        }
        int first = octets[0];
        int second = octets[1];
        int third = octets[2];
        return first != 0
                && first != 10
                && first != 127
                && !(first == 100 && second >= 64 && second <= 127)
                && !(first == 169 && second == 254)
                && !(first == 172 && second >= 16 && second <= 31)
                && !(first == 192 && second == 168)
                && !(first == 192 && second == 0 && (third == 0 || third == 2))
                && !(first == 198 && (second == 18 || second == 19))
                && !(first == 198 && second == 51 && third == 100)
                && !(first == 203 && second == 0 && third == 113)
                && first < 224;
    }

    private boolean approvedCallbackHost(URI value) {
        String host = clean(value.getHost()).toLowerCase(Locale.ROOT);
        if (callbackHosts == null || callbackHosts.stream()
                .filter(java.util.Objects::nonNull)
                .map(String::trim)
                .map(candidate -> candidate.toLowerCase(Locale.ROOT))
                .noneMatch(host::equals)) {
            return false;
        }
        boolean temporary = host.endsWith(".trycloudflare.com")
                || host.endsWith(".free.pinggy.net")
                || host.endsWith(".run.pinggy-free.link");
        return !temporary || (allowTemporaryCallbackHostsForTests && localAcceptanceProfile());
    }

    private boolean localAcceptanceProfile() {
        if (environment == null) return false;
        Set<String> profiles = Arrays.stream(environment.getActiveProfiles())
                .map(value -> value.toLowerCase(Locale.ROOT))
                .collect(java.util.stream.Collectors.toSet());
        if (profiles.contains("prod") || profiles.contains("production") || profiles.contains("staging")) {
            return false;
        }
        return profiles.contains("dev")
                || profiles.contains("local")
                || profiles.contains("test")
                || profiles.contains("acceptance");
    }

    private URI uri(String raw) {
        try {
            URI value = URI.create(clean(raw));
            return value.isAbsolute() && value.getHost() != null && value.getUserInfo() == null
                    && value.getQuery() == null && value.getFragment() == null ? value : null;
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private boolean digits(String value) {
        return value != null && value.matches("[0-9]{6,32}");
    }

    private boolean validProxyConfiguration() {
        String host = clean(proxyHost);
        if (host.isEmpty()) return proxyPort == 0;
        return proxyPort >= 1 && proxyPort <= 65_535
                && host.length() <= 253
                && host.matches("[A-Za-z0-9.-]+");
    }

    private String cleanPath(String value) {
        return trimTrailingSlash(value == null ? "" : value.trim());
    }

    private String trimTrailingSlash(String value) {
        String result = clean(value);
        while (result.endsWith("/")) result = result.substring(0, result.length() - 1);
        return result;
    }

    private String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
