package ffdd.opsconsole.shared.audit;

import ffdd.opsconsole.shared.exception.BizException;
import jakarta.servlet.http.HttpServletRequest;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@Slf4j
@Service
@ConditionalOnClass(NamedParameterJdbcTemplate.class)
public class AuditLogService {
    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 200;
    private static final int DEFAULT_STATS_DAYS = 7;
    private static final int MAX_STATS_DAYS = 90;
    private static final int DEFAULT_TOP_LIMIT = 10;
    private static final int MAX_TOP_LIMIT = 50;

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final AuditLogSanitizer sanitizer;
    private final String serviceName;
    private final boolean enabled;
    private final boolean failFast;

    public AuditLogService(
            NamedParameterJdbcTemplate jdbcTemplate,
            AuditLogSanitizer sanitizer,
            @Value("${spring.application.name:nexion-service}") String serviceName,
            @Value("${nexion.audit.enabled:true}") boolean enabled,
            @Value("${nexion.audit.fail-fast:false}") boolean failFast) {
        this.jdbcTemplate = jdbcTemplate;
        this.sanitizer = sanitizer;
        this.serviceName = serviceName;
        this.enabled = enabled;
        this.failFast = failFast;
    }

    public void record(AuditLogWriteRequest request) {
        if (!enabled || request == null || !StringUtils.hasText(request.getAction())) {
            return;
        }
        try {
            jdbcTemplate.update("""
                    INSERT INTO nx_audit_log (
                      trace_id, service_name, action, resource_type, resource_id, biz_no,
                      user_id, actor_id, actor_type, actor_username, client_ip, method, path,
                      result, risk_level, detail_json, created_at, is_deleted
                    ) VALUES (
                      :traceId, :serviceName, :action, :resourceType, :resourceId, :bizNo,
                      :userId, :actorId, :actorType, :actorUsername, :clientIp, :method, :path,
                      :result, :riskLevel, :detailJson, NOW(), 0
                    )
                    """, buildParams(request));
        } catch (RuntimeException ex) {
            if (failFast) {
                throw ex;
            }
            log.warn("Audit log write failed action={}, resourceType={}, resourceId={}, error={}",
                    request.getAction(), request.getResourceType(), request.getResourceId(), ex.getMessage());
        }
    }

    public List<AuditLogRecord> list(AuditLogQueryRequest request) {
        AuditLogQueryRequest query = request == null ? new AuditLogQueryRequest() : request;
        StringBuilder sql = new StringBuilder("""
                SELECT id, trace_id, service_name, action, resource_type, resource_id, biz_no,
                       user_id, actor_id, actor_type, actor_username, client_ip, method, path,
                       result, risk_level, detail_json, created_at
                  FROM nx_audit_log
                 WHERE is_deleted = 0
                """);
        Map<String, Object> params = new LinkedHashMap<>();
        addTextFilter(sql, params, "trace_id", "traceId", query.getTraceId());
        addTextFilter(sql, params, "service_name", "serviceName", query.getServiceName());
        addTextFilter(sql, params, "action", "action", query.getAction());
        addTextFilter(sql, params, "resource_type", "resourceType", query.getResourceType());
        addTextFilter(sql, params, "resource_id", "resourceId", query.getResourceId());
        addTextFilter(sql, params, "biz_no", "bizNo", query.getBizNo());
        addLongFilter(sql, params, "user_id", "userId", query.getUserId());
        addLongFilter(sql, params, "actor_id", "actorId", query.getActorId());
        addTextFilter(sql, params, "result", "result", query.getResult());
        addTextFilter(sql, params, "risk_level", "riskLevel", query.getRiskLevel());
        params.put("limit", normalizeLimit(query.getLimit()));
        sql.append(" ORDER BY id DESC LIMIT :limit");
        return jdbcTemplate.query(sql.toString(), params, this::mapRecord);
    }

    public AuditStatsSummaryResponse summary(AuditStatsQueryRequest request) {
        NormalizedAuditStatsQuery query = normalizeStatsQuery(request);
        Long total = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM nx_audit_log " + statsWhereClause(query),
                query.params(),
                Long.class);
        AuditStatsSummaryResponse response = new AuditStatsSummaryResponse();
        response.setStartAt(query.startAt());
        response.setEndAt(query.endAt());
        response.setTotal(total == null ? 0L : total);
        response.setByResult(groupStats(query, "COALESCE(result, 'UNKNOWN')", null));
        response.setByRiskLevel(groupStats(query, "COALESCE(risk_level, 'UNKNOWN')", null));
        return response;
    }

    public List<AuditStatsBucket> topActions(AuditStatsQueryRequest request) {
        return groupStats(normalizeStatsQuery(request), "COALESCE(action, 'UNKNOWN')", null);
    }

    public List<AuditStatsBucket> topServices(AuditStatsQueryRequest request) {
        return groupStats(normalizeStatsQuery(request), "COALESCE(service_name, 'UNKNOWN')", null);
    }

    public List<AuditStatsBucket> topUsers(AuditStatsQueryRequest request) {
        return groupStats(normalizeStatsQuery(request), "CAST(user_id AS CHAR)", "user_id IS NOT NULL");
    }

    private Map<String, Object> buildParams(AuditLogWriteRequest request) {
        HttpServletRequest servletRequest = currentRequest();
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        String actorName = authentication == null ? null : authentication.getName();
        Long actorId = request.getActorId() == null ? parseLong(actorName) : request.getActorId();
        String actorUsername = StringUtils.hasText(request.getActorUsername())
                ? request.getActorUsername()
                : actorName;
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("traceId", firstText(request.getTraceId(), AuditTraceContext.currentTraceId(), requestTraceId(servletRequest)));
        params.put("serviceName", serviceName);
        params.put("action", normalizeCode(request.getAction(), "UNKNOWN"));
        params.put("resourceType", normalizeCode(request.getResourceType(), "UNKNOWN"));
        params.put("resourceId", textOrNull(request.getResourceId()));
        params.put("bizNo", textOrNull(request.getBizNo()));
        params.put("userId", request.getUserId());
        params.put("actorId", actorId);
        params.put("actorType", firstText(request.getActorType(), "ADMIN"));
        params.put("actorUsername", textOrNull(actorUsername));
        params.put("clientIp", firstText(request.getClientIp(), clientIp(servletRequest)));
        params.put("method", firstText(request.getMethod(), servletRequest == null ? null : servletRequest.getMethod()));
        params.put("path", firstText(request.getPath(), servletRequest == null ? null : servletRequest.getRequestURI()));
        params.put("result", normalizeCode(request.getResult(), "SUCCESS"));
        params.put("riskLevel", normalizeCode(request.getRiskLevel(), "INFO"));
        params.put("detailJson", sanitizer.toSafeJson(request.getDetail()));
        return params;
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
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("startAt", startAt);
        params.put("endAt", endAt);
        addTextFilterParam(params, "serviceName", query.getServiceName());
        addTextFilterParam(params, "action", normalizeOptionalCode(query.getAction()));
        addTextFilterParam(params, "riskLevel", normalizeOptionalCode(query.getRiskLevel()));
        addTextFilterParam(params, "result", normalizeOptionalCode(query.getResult()));
        if (query.getUserId() != null) {
            params.put("userId", query.getUserId());
        }
        if (query.getActorId() != null) {
            params.put("actorId", query.getActorId());
        }
        return new NormalizedAuditStatsQuery(startAt, endAt, params, normalizeTopLimit(query.getLimit()));
    }

    private String statsWhereClause(NormalizedAuditStatsQuery query) {
        StringBuilder sql = new StringBuilder("""
                WHERE is_deleted = 0
                  AND created_at >= :startAt
                  AND created_at < :endAt
                """);
        if (query.params().containsKey("serviceName")) {
            sql.append(" AND service_name = :serviceName");
        }
        if (query.params().containsKey("action")) {
            sql.append(" AND action = :action");
        }
        if (query.params().containsKey("riskLevel")) {
            sql.append(" AND risk_level = :riskLevel");
        }
        if (query.params().containsKey("result")) {
            sql.append(" AND result = :result");
        }
        if (query.params().containsKey("userId")) {
            sql.append(" AND user_id = :userId");
        }
        if (query.params().containsKey("actorId")) {
            sql.append(" AND actor_id = :actorId");
        }
        return sql.toString();
    }

    private List<AuditStatsBucket> groupStats(
            NormalizedAuditStatsQuery query, String bucketExpression, String extraWhere) {
        Map<String, Object> params = new LinkedHashMap<>(query.params());
        params.put("limit", query.limit());
        StringBuilder sql = new StringBuilder()
                .append("SELECT ")
                .append(bucketExpression)
                .append(" AS bucket_key, COUNT(*) AS bucket_count FROM nx_audit_log ")
                .append(statsWhereClause(query));
        if (StringUtils.hasText(extraWhere)) {
            sql.append(" AND ").append(extraWhere);
        }
        sql.append(" GROUP BY ")
                .append(bucketExpression)
                .append(" ORDER BY bucket_count DESC, bucket_key ASC LIMIT :limit");
        return jdbcTemplate.query(sql.toString(), params, this::mapBucket);
    }

    private AuditLogRecord mapRecord(ResultSet rs, int rowNum) throws SQLException {
        AuditLogRecord record = new AuditLogRecord();
        record.setId(rs.getLong("id"));
        record.setTraceId(rs.getString("trace_id"));
        record.setServiceName(rs.getString("service_name"));
        record.setAction(rs.getString("action"));
        record.setResourceType(rs.getString("resource_type"));
        record.setResourceId(rs.getString("resource_id"));
        record.setBizNo(rs.getString("biz_no"));
        record.setUserId(longOrNull(rs, "user_id"));
        record.setActorId(longOrNull(rs, "actor_id"));
        record.setActorType(rs.getString("actor_type"));
        record.setActorUsername(rs.getString("actor_username"));
        record.setClientIp(rs.getString("client_ip"));
        record.setMethod(rs.getString("method"));
        record.setPath(rs.getString("path"));
        record.setResult(rs.getString("result"));
        record.setRiskLevel(rs.getString("risk_level"));
        record.setDetailJson(rs.getString("detail_json"));
        record.setCreatedAt(toLocalDateTime(rs, "created_at"));
        return record;
    }

    private AuditStatsBucket mapBucket(ResultSet rs, int rowNum) throws SQLException {
        return new AuditStatsBucket(rs.getString("bucket_key"), rs.getLong("bucket_count"));
    }

    private void addTextFilter(
            StringBuilder sql, Map<String, Object> params, String column, String paramName, String value) {
        if (!StringUtils.hasText(value)) {
            return;
        }
        sql.append(" AND ").append(column).append(" = :").append(paramName);
        params.put(paramName, value.trim());
    }

    private void addLongFilter(
            StringBuilder sql, Map<String, Object> params, String column, String paramName, Long value) {
        if (value == null) {
            return;
        }
        sql.append(" AND ").append(column).append(" = :").append(paramName);
        params.put(paramName, value);
    }

    private HttpServletRequest currentRequest() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            return attributes.getRequest();
        }
        return null;
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
        String forwarded = request.getHeader("X-Forwarded-For");
        if (StringUtils.hasText(forwarded)) {
            return forwarded.split(",")[0].trim();
        }
        String realIp = request.getHeader("X-Real-IP");
        return StringUtils.hasText(realIp) ? realIp.trim() : request.getRemoteAddr();
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

    private void addTextFilterParam(Map<String, Object> params, String paramName, String value) {
        if (StringUtils.hasText(value)) {
            params.put(paramName, value.trim());
        }
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

    private Long longOrNull(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private LocalDateTime toLocalDateTime(ResultSet rs, String column) throws SQLException {
        var timestamp = rs.getTimestamp(column);
        return timestamp == null ? null : timestamp.toLocalDateTime();
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
            LocalDateTime startAt, LocalDateTime endAt, Map<String, Object> params, int limit) {}
}
