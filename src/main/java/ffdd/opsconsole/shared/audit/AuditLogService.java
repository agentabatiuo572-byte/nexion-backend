package ffdd.opsconsole.shared.audit;

import ffdd.opsconsole.auth.infrastructure.AdminEntity;
import ffdd.opsconsole.auth.mapper.AdminMapper;
import ffdd.opsconsole.platform.application.A2RuntimePolicy;
import ffdd.opsconsole.shared.audit.mapper.AuditLogMapper;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.security.AdminActorResolver;
import jakarta.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.LinkedHashMap;
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
    private final AdminMapper adminMapper;
    private final A2RuntimePolicy a2RuntimePolicy;

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
     * Writes a required audit using an actor recovered from a server-validated durable record.
     * This is reserved for flows such as read-only impersonation where the current SecurityContext
     * intentionally represents the target user rather than the administrator who opened the session.
     */
    public void recordRequiredForTrustedActor(AuditLogWriteRequest request) {
        if (!auditProperties.isEnabled()) {
            throw new IllegalStateException("AUDIT_REQUIRED_DISABLED");
        }
        if (request == null || !StringUtils.hasText(request.getAction())
                || !StringUtils.hasText(request.getActorUsername())) {
            throw new IllegalArgumentException("AUDIT_TRUSTED_ACTOR_INVALID");
        }
        auditLogMapper.insertAuditLog(buildWrite(request, true));
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
        return list(request, MAX_LIMIT);
    }

    /** Returns only the exact maker/key association, never a recent-log approximation. */
    public String findA2ProposalId(long actorId, String key) {
        if (actorId < 1 || !StringUtils.hasText(key) || key.length() > 128)
            throw new BizException(422, "A2_PROPOSAL_KEY_INVALID");
        var audit = findA2CommandAudit(actorId, key, "A2_OPERATION_PROPOSED", null);
        return audit == null ? null : audit.operationId();
    }

    public AuditLogMapper.A2CommandAudit findA2CommandAudit(long actorId, String key, String action, String operationId) {
        var matches = auditLogMapper.findA2CommandAudits(actorId, key.trim(), action, operationId);
        if (matches.size() > 1) {
            throw new BizException(409, "A2_PROPOSAL_RESULT_AMBIGUOUS");
        }
        return matches.isEmpty() ? null : matches.get(0);
    }

    /** An old K4 receipt may be replayed only by the single actor recorded on its success audit. */
    public boolean isK4BatchRecomputeOwner(long recordId, String scope, String key, String hash, long actor) {
        if (recordId < 1 || actor < 1 || !StringUtils.hasText(scope)
                || !StringUtils.hasText(key) || !StringUtils.hasText(hash)) return false;
        List<Long> owners = auditLogMapper.findK4BatchRecomputeOwners(recordId, scope, key.trim(), hash);
        return owners != null && owners.size() == 1 && Long.valueOf(actor).equals(owners.get(0));
    }

    public List<AuditLogRecord> listForExport(AuditLogQueryRequest request, int maxRows) {
        return list(request, Math.max(1, Math.min(maxRows, 5000)));
    }

    public Map<String, Long> findBiReportMakers(Collection<String> reportIds) {
        Map<String, Long> makers = new LinkedHashMap<>();
        if (!reportIds.isEmpty()) {
            auditLogMapper.findBiReportMakers(reportIds).forEach(row -> makers.put(row.reportId(), row.makerId()));
        }
        return makers;
    }

    /** Pairs the exact successful create-audit maker with the current admin display name; never falls back to an id or email. */
    public Map<String, BiReportMakerIdentity> findBiReportMakerIdentities(Collection<String> reportIds) {
        Map<String, Long> makers = findBiReportMakers(reportIds);
        if (makers.isEmpty()) {
            return Map.of();
        }
        List<Long> ids = makers.values().stream()
                .filter(id -> id != null && id > 0)
                .distinct()
                .toList();
        Map<Long, String> names = new LinkedHashMap<>();
        if (!ids.isEmpty()) {
            List<AdminEntity> admins = adminMapper.selectBatchIds(ids);
            if (admins != null) {
                for (AdminEntity admin : admins) {
                    if (admin == null || admin.getId() == null || Integer.valueOf(1).equals(admin.getIsDeleted())) {
                        continue;
                    }
                    String name = firstText(admin.getNickname(), admin.getUsername());
                    if (StringUtils.hasText(name)) {
                        names.put(admin.getId(), name.trim());
                    }
                }
            }
        }
        Map<String, BiReportMakerIdentity> identities = new LinkedHashMap<>();
        makers.forEach((reportId, makerId) -> identities.put(reportId,
                new BiReportMakerIdentity(makerId, names.get(makerId))));
        return identities;
    }

    public record BiReportMakerIdentity(Long makerId, String displayName) { }

    /**
     * Returns only successful, exact A2 terminal outcomes for the supplied operation ids.
     * Callers must still verify each action against the operation's terminal status before
     * presenting its actor as the approver or executor.
     */
    public List<AuditLogRecord> listSuccessfulA2OutcomeRecords(Collection<String> operationIds) {
        if (operationIds == null || operationIds.isEmpty()) {
            return List.of();
        }
        return auditLogMapper.listSuccessfulA2OutcomeRecords(operationIds);
    }

    private List<AuditLogRecord> list(AuditLogQueryRequest request, int maxRows) {
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
                textOrNull(query.getDomain()),
                textOrNull(query.getOperator()),
                textOrNull(query.getOperatorExact()),
                textOrNull(query.getObject()),
                query.getStartTime(),
                query.getEndTime(),
                query.getAllowedDomains(),
                Math.min(maxRows, query.getLimit() == null || query.getLimit() < 1 ? maxRows : query.getLimit()));
    }

    public long countByActionAndResourceType(String action, String resourceType) {
        if (!StringUtils.hasText(action) || !StringUtils.hasText(resourceType)) {
            return 0L;
        }
        return auditLogMapper.countByActionAndResourceType(
                normalizeCode(action, "UNKNOWN"), normalizeCode(resourceType, "UNKNOWN"));
    }

    public long count(AuditLogQueryRequest request) {
        return auditLogMapper.countFiltered(request == null ? new AuditLogQueryRequest() : request);
    }

    public List<A2AuditAggregate> aggregate(AuditLogQueryRequest request) {
        List<A2AuditAggregate> rows = auditLogMapper.aggregateFiltered(
                request == null ? new AuditLogQueryRequest() : request);
        return rows == null ? List.of() : rows;
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
        return buildWrite(request, false);
    }

    private AuditLogMapper.AuditLogWrite buildWrite(AuditLogWriteRequest request, boolean trustedRecordedActor) {
        HttpServletRequest servletRequest = currentRequest();
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        String actorName = authentication == null ? null : authentication.getName();
        String authenticatedActor = AdminActorResolver.resolve(null);
        String requestedActor = textOrNull(request.getActorUsername());
        Long actorId = trustedRecordedActor
                ? firstActorId(request.getActorId(), requestedActor)
                : (StringUtils.hasText(authenticatedActor) ? parseLong(actorName) : request.getActorId());
        String actorUsername = trustedRecordedActor
                ? requestedActor
                : (StringUtils.hasText(authenticatedActor) ? authenticatedActor : requestedActor);
        String action = normalizeCode(request.getAction(), "UNKNOWN");
        boolean retentionExecutorAudit = action.startsWith("A2_AUDIT_RETENTION_");
        Integer retentionMonths = retentionExecutorAudit ? null : a2RuntimePolicy.retentionMonths();
        LocalDateTime expireAt = retentionMonths == null ? null : LocalDateTime.now().plusMonths(retentionMonths);
        var detail = sanitizer.withRequiredSchemaVersion(request.getDetail(), a2RuntimePolicy.schemaVersion());
        // Reserved correlation is always server-owned, including when the caller supplies the same key.
        detail.remove("_adminCommand");
        var command = ffdd.opsconsole.shared.idempotency.AdminIdempotencyExecutionContext.current();
        if (command != null) detail.put("_adminCommand", Map.of("recordId", command.recordId(),
                "scope", command.scope(), "commandKey", command.commandKey(), "requestHash", command.requestHash()));
        return new AuditLogMapper.AuditLogWrite(
                firstText(request.getTraceId(), AuditTraceContext.currentTraceId(), requestTraceId(servletRequest)),
                applicationNameProperties.getName(),
                action,
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
                sanitizer.toSafeJson(detail),
                retentionMonths,
                expireAt);
    }

    private Long firstActorId(Long requestedActorId, String actorUsername) {
        if (requestedActorId != null && requestedActorId > 0) {
            return requestedActorId;
        }
        if (!StringUtils.hasText(actorUsername) || "system".equalsIgnoreCase(actorUsername)) {
            return null;
        }
        return adminMapper.findIdByUsername(actorUsername);
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

    /** Latest actual F4 leadership-pool settlement aggregate; per-rank data was never recorded. */
    public LeadershipPoolSettlement latestLeadershipPoolSettlement() {
        AuditLogMapper.LeadershipPoolSettlementAudit row =
                auditLogMapper.findLatestLeadershipPoolSettlement();
        if (row == null || !StringUtils.hasText(row.weekKey())) return null;
        BigDecimal pool = decimalOrNull(row.poolAmountUsdt());
        BigDecimal votes = decimalOrNull(row.totalVotes());
        Integer users = integerOrNull(row.settledUsers());
        if (pool == null || votes == null || users == null) return null;
        return new LeadershipPoolSettlement(row.weekKey().trim(), pool, votes, users);
    }

    private static BigDecimal decimalOrNull(String raw) {
        if (!StringUtils.hasText(raw)) return null;
        try { return new BigDecimal(raw.trim()); } catch (NumberFormatException ex) { return null; }
    }

    private static Integer integerOrNull(String raw) {
        if (!StringUtils.hasText(raw)) return null;
        try { return Integer.valueOf(raw.trim()); } catch (NumberFormatException ex) { return null; }
    }

    public record LeadershipPoolSettlement(String weekKey, BigDecimal poolAmountUsdt,
                                           BigDecimal totalVotes, Integer settledUsers) { }

    /** Current admin display names by exact username; missing/deleted/blank maps to null, never id or email. */
    public Map<String, String> findDisplayNamesByUsernames(Collection<String> usernames) {
        if (usernames == null || usernames.isEmpty()) {
            return Map.of();
        }
        List<String> normalized = usernames.stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .distinct()
                .toList();
        if (normalized.isEmpty()) {
            return Map.of();
        }
        Map<String, String> result = new LinkedHashMap<>();
        List<ffdd.opsconsole.auth.mapper.AdminMapper.AdminDisplayName> admins =
                adminMapper.findDisplayNamesByUsernames(normalized);
        if (admins != null) {
            for (ffdd.opsconsole.auth.mapper.AdminMapper.AdminDisplayName admin : admins) {
                if (admin != null && StringUtils.hasText(admin.username())
                        && StringUtils.hasText(admin.displayName())) {
                    result.put(admin.username(), admin.displayName().trim());
                }
            }
        }
        return result;
    }
    /**
     * H8 batch evidence keyed by the settlement audit's correlation trace.
     * The trace is not a transaction-unique ID (a compliant client may supply/reuse it),
     * so multiple/ambiguous batch evidence is returned as unavailable rather than merged.
     * No command key is used for grouping: keys are scoped and reusable.
     */
    public Map<String, H8SettlementBatch> findH8SettlementBatches(Collection<String> settlementNos) {
        if (settlementNos == null || settlementNos.isEmpty()) {
            return Map.of();
        }
        List<String> requested = settlementNos.stream().map(AuditLogService::clean)
                .filter(java.util.Objects::nonNull).distinct().toList();
        if (requested.isEmpty()) {
            return Map.of();
        }
        List<AuditLogMapper.H8SettlementAudit> requestedAudits =
                auditLogMapper.findH8SettlementAuditsBySettlementNos(requested);
        Map<String, java.util.Set<String>> tracesBySettlement = new LinkedHashMap<>();
        Map<String, java.util.Set<String>> actorsBySettlement = new LinkedHashMap<>();
        if (requestedAudits != null) {
            for (AuditLogMapper.H8SettlementAudit row : requestedAudits) {
                String settlementNo = row == null ? null : clean(row.settlementNo());
                String traceId = row == null ? null : clean(row.traceId());
                if (settlementNo == null || traceId == null) continue;
                tracesBySettlement.computeIfAbsent(settlementNo,
                        ignored -> new java.util.LinkedHashSet<>()).add(traceId);
                String actor = clean(row.actorUsername());
                if (actor != null) {
                    actorsBySettlement.computeIfAbsent(settlementNo,
                            ignored -> new java.util.LinkedHashSet<>()).add(actor);
                }
            }
        }
        Map<String, String> settlementTrace = new LinkedHashMap<>();
        Map<String, String> settlementActor = new LinkedHashMap<>();
        for (String no : requested) {
            java.util.Set<String> traces = tracesBySettlement.get(no);
            if (traces != null && traces.size() == 1) {
                settlementTrace.put(no, traces.iterator().next());
                java.util.Set<String> actors = actorsBySettlement.get(no);
                if (actors != null && actors.size() == 1) {
                    settlementActor.put(no, actors.iterator().next());
                }
            }
        }
        if (settlementTrace.isEmpty()) {
            return Map.of();
        }
        List<String> traceIds = settlementTrace.values().stream().distinct().toList();
        List<AuditLogMapper.H8SettlementBatchAudit> batchRows =
                auditLogMapper.findH8SettlementBatchAuditsByTraces(traceIds);
        Map<String, List<AuditLogMapper.H8SettlementBatchAudit>> batchesByTrace = new LinkedHashMap<>();
        if (batchRows != null) {
            for (AuditLogMapper.H8SettlementBatchAudit row : batchRows) {
                String traceId = row == null ? null : clean(row.traceId());
                if (traceId == null) continue;
                batchesByTrace.computeIfAbsent(traceId,
                        ignored -> new java.util.ArrayList<>()).add(row);
            }
        }
        List<AuditLogMapper.H8SettlementAudit> traceAudits =
                auditLogMapper.findH8SettlementAuditsByTraces(traceIds);
        Map<String, java.util.Set<String>> settlementNosByTrace = new LinkedHashMap<>();
        if (traceAudits != null) {
            for (AuditLogMapper.H8SettlementAudit row : traceAudits) {
                String traceId = row == null ? null : clean(row.traceId());
                String settlementNo = row == null ? null : clean(row.settlementNo());
                if (traceId == null || settlementNo == null) continue;
                settlementNosByTrace.computeIfAbsent(traceId,
                        ignored -> new java.util.LinkedHashSet<>()).add(settlementNo);
            }
        }
        List<AuditLogMapper.H8A2Approval> approvals =
                auditLogMapper.findH8A2ApprovalsByTraces(traceIds);
        Map<String, java.util.Set<String>> operationsByTrace = new LinkedHashMap<>();
        Map<String, java.util.Set<String>> actorsByTrace = new LinkedHashMap<>();
        if (approvals != null) {
            for (AuditLogMapper.H8A2Approval row : approvals) {
                String traceId = row == null ? null : clean(row.traceId());
                String operationId = row == null ? null : clean(row.operationId());
                if (traceId == null || operationId == null) continue;
                operationsByTrace.computeIfAbsent(traceId, ignored -> new java.util.LinkedHashSet<>())
                        .add(operationId);
                String actor = clean(row.actorUsername());
                if (actor != null) {
                    actorsByTrace.computeIfAbsent(traceId, ignored -> new java.util.LinkedHashSet<>())
                            .add(actor);
                }
            }
        }
        List<String> operationIds = operationsByTrace.values().stream()
                .filter(values -> values.size() == 1).map(values -> values.iterator().next())
                .distinct().toList();
        Map<String, String> proposers = new LinkedHashMap<>();
        if (!operationIds.isEmpty()) {
            auditLogMapper.findA2SettlementProposals(operationIds).forEach(row -> {
                String operationId = row == null ? null : clean(row.operationId());
                String actor = row == null ? null : clean(row.actorUsername());
                if (operationId != null && actor != null) {
                    proposers.putIfAbsent(operationId, actor);
                }
            });
        }
        List<String> usernames = new java.util.ArrayList<>();
        actorsByTrace.values().forEach(usernames::addAll);
        proposers.values().forEach(usernames::add);
        Map<String, String> names = new LinkedHashMap<>();
        List<String> distinctUsernames = usernames.stream().filter(StringUtils::hasText).distinct().toList();
        if (!distinctUsernames.isEmpty()) {
            List<AdminMapper.H8DisplayName> admins =
                    adminMapper.findH8DisplayNamesByUsernames(distinctUsernames);
            if (admins != null) {
                for (AdminMapper.H8DisplayName admin : admins) {
                    if (admin != null && StringUtils.hasText(admin.username())
                            && StringUtils.hasText(admin.displayName())) {
                        names.put(admin.username(), admin.displayName().trim());
                    }
                }
            }
        }
        Map<String, H8SettlementBatch> result = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : settlementTrace.entrySet()) {
            String trace = entry.getValue();
            List<AuditLogMapper.H8SettlementBatchAudit> batch = batchesByTrace.get(trace);
            if (batch == null || batch.size() != 1) continue;
            Integer settled = parseSettled(batch.get(0).settled());
            java.util.Set<String> siblings = settlementNosByTrace.get(trace);
            if (settled == null || siblings == null || !siblings.contains(entry.getKey())
                    || siblings.size() != settled || settled < 1) continue;
            java.util.Set<String> operations = operationsByTrace.get(trace);
            String operationId = operations != null && operations.size() == 1
                    ? operations.iterator().next() : null;
            java.util.Set<String> actors = actorsByTrace.get(trace);
            String actor = settlementActor.get(entry.getKey());
            String approver = operationId != null && actor != null && actors != null
                    && actors.size() == 1 && actors.contains(actor) ? names.get(actor) : null;
            String proposer = approver == null ? null : names.get(proposers.get(operationId));
            result.put(entry.getKey(), new H8SettlementBatch(trace, proposer, approver,
                    List.copyOf(siblings), true));
        }
        return result;
    }

    private static String clean(String value) {
        if (!StringUtils.hasText(value)) return null;
        String trimmed = value.trim();
        return "null".equalsIgnoreCase(trimmed) ? null : trimmed;
    }

    private static Integer parseSettled(String raw) {
        if (!StringUtils.hasText(raw)) return null;
        try {
            int value = Integer.parseInt(raw.trim());
            return value >= 0 ? value : null;
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    public record H8SettlementBatch(String traceId, String proposerName, String approverName,
                                    List<String> settlementNos, boolean complete) { }
}
