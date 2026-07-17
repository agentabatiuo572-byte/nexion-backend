package ffdd.opsconsole.emergency.application;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Explicit business-route registry for J2 limited-region enforcement.
 * A route is protected because of its declared capability, never because an
 * unrelated URL happens to contain words such as "finance" or "order".
 */
public final class GeoProtectedRouteRegistry {
    private static final Set<String> MUTATION_METHODS = Set.of("POST", "PUT", "PATCH", "DELETE");
    private static final List<ProtectedRoute> ROUTES = List.of(
            new ProtectedRoute("/api/treasury", "treasury-mutation"),
            new ProtectedRoute("/api/wallet", "wallet-mutation"),
            new ProtectedRoute("/api/app/wallet", "app-wallet-mutation"),
            new ProtectedRoute("/api/withdrawals", "withdrawal"),
            new ProtectedRoute("/api/deposits", "deposit"),
            new ProtectedRoute("/api/exchange", "exchange"),
            new ProtectedRoute("/api/swap", "swap"),
            new ProtectedRoute("/api/finance/swap", "finance-swap"),
            new ProtectedRoute("/api/staking", "staking"),
            new ProtectedRoute("/api/market/staking", "market-staking"),
            new ProtectedRoute("/api/genesis", "genesis-purchase"),
            new ProtectedRoute("/api/market/genesis", "market-genesis-purchase"),
            new ProtectedRoute("/api/device/purchase", "device-purchase"),
            new ProtectedRoute("/api/market/orders", "market-order"),
            new ProtectedRoute("/api/orders", "commerce-order"),
            new ProtectedRoute("/api/subscriptions", "subscription"),
            new ProtectedRoute("/api/rewards/claim", "reward-claim"),
            new ProtectedRoute("/api/content/learning/courses", "learning-reward", Set.of("complete", "quiz")),
            new ProtectedRoute("/api/team/commission/withdraw", "commission-withdraw"),
            new ProtectedRoute("/commerce/app", "app-commerce"));

    private GeoProtectedRouteRegistry() {
    }

    public static boolean isFundsMutation(String method, String requestPath) {
        if (method == null || !MUTATION_METHODS.contains(method.trim().toUpperCase(Locale.ROOT))) {
            return false;
        }
        String path = normalizePath(requestPath);
        return ROUTES.stream().anyMatch(route -> route.matches(path));
    }

    private static String normalizePath(String value) {
        if (value == null || value.isBlank()) {
            return "/";
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        int queryIndex = normalized.indexOf('?');
        if (queryIndex >= 0) {
            normalized = normalized.substring(0, queryIndex);
        }
        return normalized.startsWith("/") ? normalized : "/" + normalized;
    }

    private record ProtectedRoute(String path, String capability, Set<String> terminalActions) {
        private ProtectedRoute(String path, String capability) {
            this(path, capability, Set.of());
        }

        private boolean matches(String candidate) {
            if (terminalActions.isEmpty()) {
                return candidate.equals(path) || candidate.startsWith(path + "/");
            }
            if (!candidate.startsWith(path + "/")) {
                return false;
            }
            int lastSlash = candidate.lastIndexOf('/');
            return lastSlash >= 0 && terminalActions.contains(candidate.substring(lastSlash + 1));
        }
    }
}
