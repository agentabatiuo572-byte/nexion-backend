package ffdd.opsconsole.emergency.web;

import ffdd.opsconsole.emergency.application.GeoBlockDecision;
import ffdd.opsconsole.emergency.application.GeoBlockPolicyService;
import ffdd.opsconsole.emergency.application.GeoEdgeSourceRegistry;
import ffdd.opsconsole.emergency.application.GeoEdgeHealthMonitor;
import ffdd.opsconsole.emergency.domain.EmergencyControlRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

/** Enforces J2 at the first application request boundary using only trusted proxy metadata. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 30)
@RequiredArgsConstructor
public class GeoBlockEnforcementFilter extends OncePerRequestFilter {
    private static final String EDGE_SETTING = "emergency.geo.edgeJudgeSource";
    private static final String EDGE_FALLBACK_SETTING = "emergency.geo.edgeJudgeFallbackSource";
    private static final String EDGE_FALLBACK_UNTIL_SETTING = "emergency.geo.edgeJudgeFallbackUntilEpochMs";
    private static final String ORIGINAL_REMOTE_ADDRESS = "org.apache.catalina.AccessLog.RemoteAddr";
    private static final Set<String> ISO_COUNTRIES = Set.of(Locale.getISOCountries());

    private final GeoBlockPolicyService policyService;
    private final EmergencyControlRepository repository;
    private final GeoBlockEnforcementProperties properties;
    private final GeoEdgeHealthMonitor healthMonitor;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        if (!properties.isEnabled() || path == null || "/api/admin".equals(path) || path.startsWith("/api/admin/")) {
            return true;
        }
        return !(path.startsWith("/api/")
                || path.startsWith("/auth/")
                || path.startsWith("/commerce/app/")
                || path.startsWith("/content/app/")
                || path.startsWith("/openapi/v1/"));
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        String remoteAddress = connectionPeerAddress(request);
        if (!properties.isTrustedProxy(remoteAddress)) {
            reject(response, 503, "GEO_EDGE_TRUST_REQUIRED");
            return;
        }
        String source = repository.settingValue(EDGE_SETTING).orElse(GeoEdgeSourceRegistry.DEFAULT_SOURCE);
        String header = GeoEdgeSourceRegistry.headerName(source);
        if (!StringUtils.hasText(header)) {
            reject(response, 503, "GEO_EDGE_SOURCE_INVALID");
            return;
        }
        long resolutionStartedAt = System.nanoTime();
        for (String candidate : GeoEdgeSourceRegistry.keys()) {
            String candidateValue = normalizeCountry(request.getHeader(GeoEdgeSourceRegistry.headerName(candidate)));
            // Every trusted request is a probe for every supported source. Missing
            // and invalid candidate headers must be part of the failure denominator;
            // otherwise a few successful samples could hide a mostly absent feed.
            healthMonitor.record(candidate, ISO_COUNTRIES.contains(candidateValue), System.nanoTime() - resolutionStartedAt);
        }
        String countryCode = normalizeCountry(request.getHeader(header));
        String resolvedSource = source;
        boolean primaryValid = ISO_COUNTRIES.contains(countryCode);
        if (!primaryValid && fallbackActive()) {
            String fallbackSource = repository.settingValue(EDGE_FALLBACK_SETTING).orElse("");
            String fallbackHeader = GeoEdgeSourceRegistry.headerName(fallbackSource);
            String fallbackCountry = StringUtils.hasText(fallbackHeader)
                    ? normalizeCountry(request.getHeader(fallbackHeader))
                    : "";
            if (ISO_COUNTRIES.contains(fallbackCountry)) {
                countryCode = fallbackCountry;
                resolvedSource = fallbackSource;
            }
        }
        if (!StringUtils.hasText(countryCode)) {
            if (properties.isAllowLoopbackWithoutCountry() && properties.isLoopback(remoteAddress)) {
                filterChain.doFilter(request, response);
                return;
            }
            reject(response, 503, "GEO_COUNTRY_UNRESOLVED");
            return;
        }
        if (!ISO_COUNTRIES.contains(countryCode)) {
            // The candidate-probe loop above already recorded the invalid sample.
            reject(response, 503, "GEO_COUNTRY_INVALID");
            return;
        }

        GeoBlockDecision decision = policyService.evaluate(countryCode, request.getMethod(), request.getRequestURI());
        if (!decision.blocked()) {
            filterChain.doFilter(request, response);
            return;
        }
        repository.recordGeoBlockEvent(
                countryCode,
                new Locale("", countryCode).getDisplayCountry(Locale.SIMPLIFIED_CHINESE),
                decision.endpointKey(),
                resolvedSource);
        reject(response, 403, decision.code());
    }

    private boolean fallbackActive() {
        return repository.settingValue(EDGE_FALLBACK_UNTIL_SETTING)
                .map(value -> {
                    try {
                        return Long.parseLong(value) >= System.currentTimeMillis();
                    } catch (NumberFormatException ignored) {
                        return false;
                    }
                })
                .orElse(false);
    }

    private String normalizeCountry(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private String connectionPeerAddress(HttpServletRequest request) {
        Object original = request.getAttribute(ORIGINAL_REMOTE_ADDRESS);
        if (original != null && StringUtils.hasText(String.valueOf(original))) {
            return String.valueOf(original).trim();
        }
        return request.getRemoteAddr();
    }

    private void reject(HttpServletResponse response, int status, String code) throws IOException {
        response.setStatus(status);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"code\":" + status + ",\"message\":\"" + code + "\"}");
    }
}
