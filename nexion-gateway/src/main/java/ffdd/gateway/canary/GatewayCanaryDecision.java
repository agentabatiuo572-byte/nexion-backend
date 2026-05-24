package ffdd.gateway.canary;

import java.net.URI;

public record GatewayCanaryDecision(boolean matched, String routeGroup, URI canaryUri, String reason) {
    public static GatewayCanaryDecision noMatch(String routeGroup) {
        return new GatewayCanaryDecision(false, routeGroup, null, "none");
    }

    public static GatewayCanaryDecision match(String routeGroup, URI canaryUri, String reason) {
        return new GatewayCanaryDecision(true, routeGroup, canaryUri, reason);
    }
}
