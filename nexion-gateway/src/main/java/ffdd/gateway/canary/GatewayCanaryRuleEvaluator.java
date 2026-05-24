package ffdd.gateway.canary;

import ffdd.common.security.AuthHeaders;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class GatewayCanaryRuleEvaluator {
    private static final int MIN_PERCENT = 0;
    private static final int MAX_PERCENT = 100;

    private final GatewayCanaryProperties properties;

    public GatewayCanaryRuleEvaluator(GatewayCanaryProperties properties) {
        this.properties = properties;
    }

    public GatewayCanaryDecision evaluate(String routeGroup, ServerHttpRequest request) {
        if (!properties.isEnabled() || !StringUtils.hasText(routeGroup)) {
            return GatewayCanaryDecision.noMatch(routeGroup);
        }
        GatewayCanaryProperties.RouteRule rule = properties.getRoutes().get(routeGroup);
        if (rule == null || !rule.isEnabled()) {
            return GatewayCanaryDecision.noMatch(routeGroup);
        }

        URI canaryUri = parseCanaryUri(rule.getCanaryUri());
        if (canaryUri == null) {
            return GatewayCanaryDecision.noMatch(routeGroup);
        }
        if (matchesForceHeader(rule, request)) {
            return GatewayCanaryDecision.match(routeGroup, canaryUri, "header");
        }
        if (matchesVersion(rule, request)) {
            return GatewayCanaryDecision.match(routeGroup, canaryUri, "version");
        }
        if (matchesPercent(routeGroup, rule, request)) {
            return GatewayCanaryDecision.match(routeGroup, canaryUri, "percent");
        }
        return GatewayCanaryDecision.noMatch(routeGroup);
    }

    private boolean matchesForceHeader(GatewayCanaryProperties.RouteRule rule, ServerHttpRequest request) {
        String headerName = firstText(rule.getForceHeaderName(), properties.getForceHeaderName());
        String expected = firstText(rule.getForceHeaderValue(), properties.getForceHeaderValue());
        if (!StringUtils.hasText(headerName) || !StringUtils.hasText(expected)) {
            return false;
        }
        String actual = request.getHeaders().getFirst(headerName);
        return expected.equalsIgnoreCase(actual);
    }

    private boolean matchesVersion(GatewayCanaryProperties.RouteRule rule, ServerHttpRequest request) {
        String versionHeaderName = properties.getVersionHeaderName();
        if (!StringUtils.hasText(versionHeaderName) || rule.getVersions().isEmpty()) {
            return false;
        }
        String actualVersion = request.getHeaders().getFirst(versionHeaderName);
        if (!StringUtils.hasText(actualVersion)) {
            return false;
        }
        return rule.getVersions().stream()
                .filter(StringUtils::hasText)
                .anyMatch(version -> version.equalsIgnoreCase(actualVersion));
    }

    private boolean matchesPercent(String routeGroup, GatewayCanaryProperties.RouteRule rule, ServerHttpRequest request) {
        int percent = Math.max(MIN_PERCENT, Math.min(rule.getPercent(), MAX_PERCENT));
        if (percent <= MIN_PERCENT) {
            return false;
        }
        if (percent >= MAX_PERCENT) {
            return true;
        }
        return bucket(routeGroup + ":" + identity(request)) < percent;
    }

    private String identity(ServerHttpRequest request) {
        String subjectId = request.getHeaders().getFirst(AuthHeaders.SUBJECT_ID);
        if (StringUtils.hasText(subjectId)) {
            return "user:" + subjectId;
        }
        String forwardedFor = request.getHeaders().getFirst("X-Forwarded-For");
        if (StringUtils.hasText(forwardedFor)) {
            int comma = forwardedFor.indexOf(',');
            String clientIp = comma < 0 ? forwardedFor.trim() : forwardedFor.substring(0, comma).trim();
            return StringUtils.hasText(clientIp) ? "ip:" + clientIp : "ip:unknown";
        }
        InetSocketAddress remoteAddress = request.getRemoteAddress();
        if (remoteAddress == null || remoteAddress.getAddress() == null) {
            return "ip:unknown";
        }
        return "ip:" + remoteAddress.getAddress().getHostAddress();
    }

    private int bucket(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            int raw = ((bytes[0] & 0xFF) << 24)
                    | ((bytes[1] & 0xFF) << 16)
                    | ((bytes[2] & 0xFF) << 8)
                    | (bytes[3] & 0xFF);
            return (int) (Integer.toUnsignedLong(raw) % 100);
        } catch (NoSuchAlgorithmException ex) {
            return Math.floorMod(value.hashCode(), 100);
        }
    }

    private URI parseCanaryUri(String canaryUri) {
        if (!StringUtils.hasText(canaryUri)) {
            return null;
        }
        try {
            URI uri = URI.create(canaryUri.trim());
            if (!uri.isAbsolute()) {
                return null;
            }
            return uri;
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private String firstText(String first, String second) {
        return StringUtils.hasText(first) ? first : second;
    }
}
