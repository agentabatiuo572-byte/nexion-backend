package ffdd.opsconsole.emergency.application;

import ffdd.opsconsole.common.boundary.ApplicationService;
import ffdd.opsconsole.emergency.domain.EmergencyControlRepository;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.util.StringUtils;

/** The single server-canonical evaluator consumed by the request-edge filter. */
@ApplicationService
@RequiredArgsConstructor
public class GeoBlockPolicyService {
    private static final Set<String> ISO_COUNTRIES = Set.of(Locale.getISOCountries());
    private final EmergencyControlRepository repository;

    public GeoBlockDecision evaluate(String rawCountryCode, String method, String requestPath) {
        String countryCode = normalizeCountry(rawCountryCode);
        if (!ISO_COUNTRIES.contains(countryCode)) {
            return GeoBlockDecision.blocked("GEO_COUNTRY_INVALID", "invalid", null);
        }
        String path = normalizePath(requestPath);
        String globalStatus = repository.geoCountryPolicies().stream()
                .filter(row -> countryCode.equalsIgnoreCase(text(row.get("cc"))))
                .map(row -> text(row.get("status")).toLowerCase(Locale.ROOT))
                .findFirst()
                .orElse("allowed");
        if ("blocked".equals(globalStatus)) {
            return GeoBlockDecision.blocked("GEO_BLOCKED", globalStatus, null);
        }

        String endpointKey = matchingEndpoint(path);
        if (endpointKey != null && repository.geoEndpointPolicies().stream()
                .anyMatch(row -> endpointKey.equalsIgnoreCase(text(row.get("endpointKey")))
                        && countryCode.equalsIgnoreCase(text(row.get("countryCode"))))) {
            return GeoBlockDecision.blocked("GEO_ENDPOINT_BLOCKED", "endpoint-specific", endpointKey);
        }

        if ("limited".equals(globalStatus) && GeoProtectedRouteRegistry.isFundsMutation(method, path)) {
            return GeoBlockDecision.blocked("GEO_LIMITED", globalStatus, endpointKey);
        }
        return GeoBlockDecision.allowed();
    }

    private String matchingEndpoint(String requestPath) {
        return repository.geoEndpointCatalogs().stream()
                .filter(row -> "ACTIVE".equalsIgnoreCase(text(row.get("status"))))
                .filter(row -> matches(requestPath, text(row.get("endpointPath"))))
                .sorted((left, right) -> Integer.compare(
                        text(right.get("endpointPath")).length(), text(left.get("endpointPath")).length()))
                .map(row -> text(row.get("endpointKey")))
                .filter(StringUtils::hasText)
                .findFirst()
                .orElse(null);
    }

    private boolean matches(String requestPath, String configuredPath) {
        if (!StringUtils.hasText(configuredPath)) {
            return false;
        }
        String candidate = configuredPath.trim();
        int firstSpace = candidate.indexOf(' ');
        if (firstSpace > 0) {
            candidate = candidate.substring(firstSpace + 1);
        }
        candidate = normalizePath(candidate.replace("/**", "").replace("*", ""));
        return requestPath.equals(candidate)
                || requestPath.startsWith(candidate.endsWith("/") ? candidate : candidate + "/");
    }

    private String normalizeCountry(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private String normalizePath(String value) {
        if (!StringUtils.hasText(value)) {
            return "/";
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return normalized.startsWith("/") ? normalized : "/" + normalized;
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }
}
