package ffdd.opsconsole.shared.audit;

import ffdd.opsconsole.shared.audit.mapper.AuditLogMapper;
import ffdd.opsconsole.shared.exception.BizException;
import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuditLogService {
    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 200;
    private static final int DEFAULT_STATS_DAYS = 7;
    private static final int MAX_STATS_DAYS = 90;
    private static final int DEFAULT_TOP_LIMIT = 10;
    private static final int MAX_TOP_LIMIT = 50;

    private final AuditLogMapper auditLogMapper;
    private final AuditLogSanitizer sanitizer;
    private final ApplicationNameProperties applicationNameProperties;
    private final AuditProperties auditProperties;

    public void record(AuditLogWriteRequest request) {
        if (!auditProperties.isEnabled() || request == null || !StringUtils.hasText(request.getAction())) {
            return;
        }
        try {
            auditLogMapper.insertAuditLog(buildWrite(request));
        } catch (RuntimeException ex) {
            if (auditProperties.isFailFast()) {
                throw ex;
            }
            log.warn("Audit log write failed action={}, resourceType={}, resourceId={}, error={}",
                    request.getAction(), request.getResourceType(), request.getResourceId(), ex.getMessage());
        }
    }

    /**
     * Writes an audit record as part of a mutation that is not allowed to succeed without audit.
     * Unlike {@link #record(AuditLogWriteRequest)}, this method is deliberately fail-closed.
     */
    public void recordRequired(AuditLogWriteRequest request) {
        if (!auditProperties.isEnabled()) {
            throw new IllegalStateException("AUDIT_REQUIRED_DISABLED");
        }
        if (request == null || !StringUtils.hasText(request.getAction())) {
            throw new IllegalArgumentException("AUDIT_REQUIRED_INVALID");
        }
        auditLogMapper.insertAuditLog(buildWrite(request));
    }

    /**
     * Persists the failure of a high-risk mutation after its business transaction has failed.
     * The separate transaction prevents the failure audit from being rolled back with business data.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordRequiredInNewTransaction(AuditLogWriteRequest request) {
        recordRequired(request);
    }

    public List<AuditLogRecord> list(AuditLogQueryRequest request) {
        AuditLogQueryRequest query = request == null ? new AuditLogQueryRequest() : request;
        return auditLogMapper.list(
                textOrNull(query.getTraceId()),
                textOrNull(query.getServiceName()),
                textOrNull(query.getAction()),
                textOrNull(query.getResourceType()),
                textOrNull(query.getResourceId()),
                textOrNull(query.getBizNo()),
                query.getUserId(),
                query.getActorId(),
                textOrNull(query.getResult()),
                textOrNull(query.getRiskLevel()),
                normalizeLimit(query.getLimit()));
    }

    public long countByActionAndResourceType(String action, String resourceType) {
        if (!StringUtils.hasText(action) || !StringUtils.hasText(resourceType)) {
            return 0L;
        }
        return auditLogMapper.countByActionAndResourceType(
                normalizeCode(action, "UNKNOWN"), normalizeCode(resourceType, "UNKNOWN"));
    }

    public AuditStatsSummaryResponse summary(AuditStatsQueryRequest request) {
        NormalizedAuditStatsQuery query = normalizeStatsQuery(request);
        AuditStatsSummaryResponse response = new AuditStatsSummaryResponse();
        response.setStartAt(query.startAt());
        response.setEndAt(query.endAt());
        response.setTotal(countStats(query));
        response.setByResult(groupByResult(query));
        response.setByRiskLevel(groupByRiskLevel(query));
        return response;
    }

    public List<AuditStatsBucket> topActions(AuditStatsQueryRequest request) {
        NormalizedAuditStatsQuery query = normalizeStatsQuery(request);
        return auditLogMapper.topActions(
                query.startAt(), query.endAt(), query.serviceName(), query.action(), query.riskLevel(),
                query.result(), query.userId(), query.actorId(), query.limit());
    }

    public List<AuditStatsBucket> topServices(AuditStatsQueryRequest request) {
        NormalizedAuditStatsQuery query = normalizeStatsQuery(request);
        return auditLogMapper.topServices(
                query.startAt(), query.endAt(), query.serviceName(), query.action(), query.riskLevel(),
                query.result(), query.userId(), query.actorId(), query.limit());
    }

    public List<AuditStatsBucket> topUsers(AuditStatsQueryRequest request) {
        NormalizedAuditStatsQuery query = normalizeStatsQuery(request);
        return auditLogMapper.topUsers(
                query.startAt(), query.endAt(), query.serviceName(), query.action(), query.riskLevel(),
                query.result(), query.userId(), query.actorId(), query.limit());
    }

    public List<AuditStatsBucket> countActionsByPrefixes(AuditStatsQueryRequest request, List<String> prefixes) {
        NormalizedAuditStatsQuery query = normalizeStatsQuery(request);
        List<String> normalizedPrefixes = prefixes == null ? List.of() : prefixes.stream()
                .filter(StringUtils::hasText)
                .map(prefix -> prefix.trim().toUpperCase(Locale.ROOT))
                .distinct()
                .toList();
        if (normalizedPrefixes.isEmpty()) {
            return List.of();
        }
        return auditLogMapper.countActionsByPrefixes(
                query.startAt(), query.endAt(), query.serviceName(), query.action(), query.riskLevel(),
                query.result(), query.userId(), query.actorId(), normalizedPrefixes);
    }

    private AuditLogMapper.AuditLogWrite buildWrite(AuditLogWriteRequest request) {
        HttpServletRequest servletRequest = currentRequest();
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        String actorName = authentication == null ? null : authentication.getName();
        Long actorId = request.getActorId() == null ? parseLong(actorName) : request.getActorId();
        String actorUsername = StringUtils.hasText(request.getActorUsername())
                ? request.getActorUsername()
                : authenticatedUsername(authentication, actorName);
        return new AuditLogMapper.AuditLogWrite(
                firstText(request.getTraceId(), AuditTraceContext.currentTraceId(), requestTraceId(servletRequest)),
                applicationNameProperties.getName(),
                normalizeCode(request.getAction(), "UNKNOWN"),
                normalizeCode(request.getResourceType(), "UNKNOWN"),
                textOrNull(request.getResourceId()),
                textOrNull(request.getBizNo()),
                request.getUserId(),
                actorId,
                firstText(request.getActorType(), "ADMIN"),
                textOrNull(actorUsername),
                firstText(request.getClientIp(), clientIp(servletRequest)),
                firstText(request.getMethod(), servletRequest == null ? null : servletRequest.getMethod()),
                firstText(request.getPath(), servletRequest == null ? null : servletRequest.getRequestURI()),
                normalizeCode(request.getResult(), "SUCCESS"),
                normalizeCode(request.getRiskLevel(), "INFO"),
                sanitizer.toSafeJson(request.getDetail()));
    }

    private NormalizedAuditStatsQuery normalizeStatsQuery(AuditStatsQueryRequest request) {
        AuditStatsQueryRequest query = request == null ? new AuditStatsQueryRequest() : request;
        int days = normalizeStatsDays(query.getDays());
        LocalDateTime endAt = query.getEndAt() == null ? LocalDateTime.now() : query.getEndAt();
        LocalDateTime startAt = query.getStartAt() == null ? endAt.minusDays(days) : query.getStartAt();
        if (!startAt.isBefore(endAt)) {
            throw new BizException("audit stats endAt must be after startAt");
        }
        LocalDateTime earliestAllowed = endAt.minusDays(MAX_STATS_DAYS);
        if (startAt.isBefore(earliestAllowed)) {
            startAt = earliestAllowed;
        }
        return new NormalizedAuditStatsQuery(
                startAt,
                endAt,
                textOrNull(query.getServiceName()),
                normalizeOptionalCode(query.getAction()),
                normalizeOptionalCode(query.getRiskLevel()),
                normalizeOptionalCode(query.getResult()),
                query.getUserId(),
                query.getActorId(),
                normalizeTopLimit(query.getLimit()));
    }

    private long countStats(NormalizedAuditStatsQuery query) {
        return auditLogMapper.countStats(
                query.startAt(), query.endAt(), query.serviceName(), query.action(), query.riskLevel(),
                query.result(), query.userId(), query.actorId());
    }

    private List<AuditStatsBucket> groupByResult(NormalizedAuditStatsQuery query) {
        return auditLogMapper.groupByResult(
                query.startAt(), query.endAt(), query.serviceName(), query.action(), query.riskLevel(),
                query.result(), query.userId(), query.actorId(), query.limit());
    }

    private List<AuditStatsBucket> groupByRiskLevel(NormalizedAuditStatsQuery query) {
        return auditLogMapper.groupByRiskLevel(
                query.startAt(), query.endAt(), query.serviceName(), query.action(), query.riskLevel(),
                query.result(), query.userId(), query.actorId(), query.limit());
    }

    private HttpServletRequest currentRequest() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            return attributes.getRequest();
        }
        return null;
    }

    private String authenticatedUsername(Authentication authentication, String fallback) {
        if (authentication == null) {
            return fallback;
        }
        Object details = authentication.getDetails();
        if (details instanceof Map<?, ?> values) {
            Object username = values.get("username");
            if (username instanceof String text && StringUtils.hasText(text)) {
                return text.trim();
            }
        }
        Object principal = authentication.getPrincipal();
        if (principal instanceof String text && StringUtils.hasText(text) && parseLong(text) == null) {
            return text.trim();
        }
        return fallback;
    }

    private String requestTraceId(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        Object attribute = request.getAttribute(AuditTraceContext.REQUEST_ATTRIBUTE_TRACE_ID);
        return attribute == null ? request.getHeader(AuditTraceContext.TRACE_ID_HEADER) : String.valueOf(attribute);
    }

    private String clientIp(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        // Tomcat's RemoteIpValve has already applied the configured internal-proxy trust
        // boundary when forward-headers-strategy=native. Reading raw forwarding headers
        // here would let a direct caller forge the evidence IP in a high-risk audit row.
        return request.getRemoteAddr();
    }

    private String firstText(String... values) {
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value.trim();
            }
        }
        return null;
    }

    private String normalizeCode(String value, String fallback) {
        if (!StringUtils.hasText(value)) {
            return fallback;
        }
        return value.trim().toUpperCase(Locale.ROOT);
    }

    private String normalizeOptionalCode(String value) {
        return StringUtils.hasText(value) ? value.trim().toUpperCase(Locale.ROOT) : null;
    }

    private String textOrNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private Long parseLong(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private int normalizeLimit(Integer limit) {
        if (limit == null || limit < 1) {
            return DEFAULT_LIMIT;
        }
        return Math.min(limit, MAX_LIMIT);
    }

    private int normalizeStatsDays(Integer days) {
        if (days == null || days < 1) {
            return DEFAULT_STATS_DAYS;
        }
        return Math.min(days, MAX_STATS_DAYS);
    }

    private int normalizeTopLimit(Integer limit) {
        if (limit == null || limit < 1) {
            return DEFAULT_TOP_LIMIT;
        }
        return Math.min(limit, MAX_TOP_LIMIT);
    }

    private record NormalizedAuditStatsQuery(
            LocalDateTime startAt,
            LocalDateTime endAt,
            String serviceName,
            String action,
            String riskLevel,
            String result,
            Long userId,
            Long actorId,
            int limit) {}
}
