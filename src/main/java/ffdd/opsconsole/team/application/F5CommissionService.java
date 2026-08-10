package ffdd.opsconsole.team.application;

import ffdd.opsconsole.common.api.OpsErrorCode;
import ffdd.opsconsole.common.boundary.ApplicationService;
import ffdd.opsconsole.platform.application.A2ReplayContext;
import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import ffdd.opsconsole.shared.outbox.EventOutboxService;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.team.dto.F5CommissionAnomalyConfigRequest;
import ffdd.opsconsole.team.dto.F5CommissionQuery;
import ffdd.opsconsole.team.dto.F5CommissionReissueRequest;
import ffdd.opsconsole.team.dto.F5CommissionReverseRequest;
import ffdd.opsconsole.team.dto.F5CommissionSuspensionRequest;
import ffdd.opsconsole.team.mapper.F5CommissionMapper;
import ffdd.opsconsole.treasury.facade.TreasuryCoverageFacade;
import ffdd.opsconsole.treasury.facade.TreasuryCoverageSnapshot;
import ffdd.opsconsole.treasury.facade.TreasuryLedgerPostingFacade;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.util.StringUtils;

@ApplicationService
@RequiredArgsConstructor
public class F5CommissionService {
    private static final Set<String> KINDS =
            Set.of("network", "binary", "peer", "cultivation", "leadership", "genesis");
    private static final Set<String> STATUSES =
            Set.of("cooling", "unlocked", "withdrawn", "reversed", "frozen");
    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 100;
    private static final String ANOMALY_SIGMA_KEY = "commission/anomaly-sigma";
    private static final String LAYER_RATIO_KEY = "commission/layer-ratio-anomaly-pct";
    private static final String COOLING_DAYS_KEY = "commission/cooling-days";

    private final F5CommissionMapper mapper;
    private final PlatformConfigFacade configFacade;
    private final TreasuryCoverageFacade coverageFacade;
    private final TreasuryLedgerPostingFacade ledgerPostingFacade;
    private final AuditLogService auditLogService;
    private final EventOutboxService eventOutboxService;
    private final AdminIdempotencyService idempotencyService;

    public ApiResult<Map<String, Object>> overview(F5CommissionQuery query) {
        NormalizedQuery normalized = normalizeQuery(query);
        List<Map<String, Object>> rows = mapper.queryEvents(
                normalized.kind(), normalized.currency(), normalized.userId(), normalized.status(),
                normalized.cohort(), normalized.cursor(), normalized.limit() + 1);
        boolean hasNext = rows.size() > normalized.limit();
        List<Map<String, Object>> items = rows.stream().limit(normalized.limit()).map(this::eventView).toList();
        Long nextCursor = hasNext && !items.isEmpty()
                ? longValue(items.get(items.size() - 1).get("eventId"))
                : null;
        long total = mapper.countEvents(
                normalized.kind(), normalized.currency(), normalized.userId(), normalized.status(), normalized.cohort());
        List<Map<String, Object>> sample = mapper.queryEvents(null, null, null, null, null, null, 200);
        List<Map<String, Object>> anomalies = anomalyViews(sample);
        List<Map<String, Object>> kindAggregates = mapper.aggregateCommissionKinds();
        List<Map<String, Object>> statusAggregates = mapper.aggregateCommissionStatuses();
        if (kindAggregates == null || statusAggregates == null) {
            throw new BizException(500, "F5_COMMISSION_AGGREGATE_UNAVAILABLE");
        }
        if (mapper.unknownCommissionKindCount() > 0) {
            throw new BizException(500, "F5_COMMISSION_KIND_UNKNOWN");
        }
        if (kindAggregates.stream().anyMatch(row -> !KINDS.contains(text(row.get("kind"))))) {
            throw new BizException(500, "F5_COMMISSION_KIND_UNKNOWN");
        }
        if (java.util.stream.Stream.concat(kindAggregates.stream(), statusAggregates.stream())
                .anyMatch(row -> !Set.of("USDT", "NEX").contains(text(row.get("currency")).toUpperCase(Locale.ROOT)))) {
            throw new BizException(500, "F5_COMMISSION_CURRENCY_UNKNOWN");
        }
        if (statusAggregates.stream().anyMatch(row -> !STATUSES.contains(text(row.get("status"))))) {
            throw new BizException(500, "F5_COMMISSION_STATUS_UNKNOWN");
        }
        TreasuryCoverageSnapshot coverage = coverageFacade.snapshot();

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("domain", "F5");
        response.put("summary", summary(kindAggregates, statusAggregates));
        response.put("commissionKinds", kindSummary(kindAggregates));
        response.put("commissionFilters", List.of(
                Map.of("key", "all", "label", "全部状态"),
                Map.of("key", "cooling", "label", "冷却计提"),
                Map.of("key", "unlocked", "label", "已解锁可提"),
                Map.of("key", "withdrawn", "label", "已提现"),
                Map.of("key", "reversed", "label", "已撤销"),
                Map.of("key", "frozen", "label", "已冻结")));
        response.put("commissionEvents", items);
        response.put("items", items);
        response.put("nextCursor", nextCursor == null ? "" : String.valueOf(nextCursor));
        response.put("total", total);
        response.put("pagination", linked(
                "mode", "server-cursor",
                "defaultWindow", "全量游标",
                "defaultPageSize", DEFAULT_LIMIT,
                "pageSize", normalized.limit(),
                "maxPageSize", MAX_LIMIT,
                "requestCursor", normalized.cursor() == null ? "" : String.valueOf(normalized.cursor()),
                "nextCursor", nextCursor == null ? "" : String.valueOf(nextCursor),
                "total", total));
        response.put("anomalies", anomalies);
        response.put("coolingPolicy", coolingPolicy());
        response.put("operationHistory", mapper.recentOperations(50));
        response.put("activeSuspensions", mapper.activeSuspensions(100));
        response.put("statusDistribution", statusDistribution(statusAggregates));
        response.put("recentAuditFeed", recentAudit(sample));
        response.put("configValues", linked(
                "commissionAnomalySigma", anomalySigma().toPlainString(),
                "layerRatioAnomalyPct", layerRatioThreshold().toPlainString(),
                "commissionCoolingDays", String.valueOf(coolingDays()),
                "F.commission.anomalyThreshold",
                "{\"commissionAnomalySigma\":" + anomalySigma().toPlainString()
                        + ",\"layerRatioAnomalyPct\":" + layerRatioThreshold().toPlainString() + "}"));
        response.put("commissionPolicy", Map.of(
                "coolingAuthority", COOLING_DAYS_KEY,
                "coolingKinds", List.of("network", "binary"),
                "immediateKinds", List.of("peer", "cultivation", "leadership", "genesis")));
        response.put("guardrails", List.of(
                "F5 writes require Idempotency-Key and an 8-200 character reason",
                "reissue is fail-closed on an unavailable or below-redline B1 snapshot",
                "reverse uses a status CAS and a server-validated refund/evidence reference",
                "D4 ledger, A2 audit and A4 admin event share one transaction"));
        response.put("coverage", linked(
                "coverageRatio", coverage.coverageRatio(),
                "redlinePct", coverage.redlinePct(),
                "reliable", coverage.reliable()));
        response.put("sources", List.of(
                "nx_commission_event",
                "nx_commission_operation",
                "nx_commission_user_suspension",
                "nx_wallet_ledger",
                "nx_event_outbox"));
        return ApiResult.ok(response);
    }

    public ApiResult<Map<String, Object>> anomalies(String type, Long cursor) {
        List<Map<String, Object>> anomalies = anomalyViews(
                mapper.queryEvents(null, null, null, null, null, cursor, 200));
        if (StringUtils.hasText(type)) {
            String expected = type.trim().toLowerCase(Locale.ROOT);
            anomalies = anomalies.stream()
                    .filter(row -> expected.equals(String.valueOf(row.get("type")).toLowerCase(Locale.ROOT)))
                    .toList();
        }
        return ApiResult.ok(linked("items", anomalies, "nextCursor", ""));
    }

    public ApiResult<Map<String, Object>> reverse(
            String commissionId, String idempotencyKey, F5CommissionReverseRequest request) {
        ApiResult<Map<String, Object>> denied = requireApprovedReplay();
        if (denied != null) return denied;
        Long eventId = eventId(commissionId);
        String reason = reason(request == null ? null : request.reason());
        String refundRef = requireText(request == null ? null : request.refundRef(), "REFUND_REF_REQUIRED");
        String operator = operator(request == null ? null : request.operator());
        String hash = requestHash("reverse", commissionId, refundRef, reason);
        return executeIdempotent("F5_COMMISSION_REVERSE", idempotencyKey, hash,
                () -> reverseInternal(eventId, commissionId, refundRef, reason, operator, idempotencyKey));
    }

    public ApiResult<Map<String, Object>> reissue(
            String idempotencyKey, F5CommissionReissueRequest request) {
        ApiResult<Map<String, Object>> denied = requireApprovedReplay();
        if (denied != null) return denied;
        String reason = reason(request == null ? null : request.reason());
        String operator = operator(request == null ? null : request.operator());
        List<Long> eventIds = normalizeCommissionIds(request == null ? null : request.commissionIds());
        String hash = requestHash("reissue", eventIds.toString(), reason);
        return executeIdempotent("F5_COMMISSION_REISSUE", idempotencyKey, hash,
                () -> reissueInternal(eventIds, reason, operator, idempotencyKey));
    }

    public ApiResult<Map<String, Object>> suspend(
            Long userId, String idempotencyKey, F5CommissionSuspensionRequest request) {
        ApiResult<Map<String, Object>> denied = requireApprovedReplay();
        if (denied != null) return denied;
        if (userId == null || userId <= 0) {
            return ApiResult.fail(422, "USER_ID_INVALID");
        }
        String reason = reason(request == null ? null : request.reason());
        String operator = operator(request == null ? null : request.operator());
        List<String> kinds = normalizeKinds(request == null ? null : request.kinds());
        boolean suspended = request == null || request.suspended() == null || request.suspended();
        String hash = requestHash("suspension", userId.toString(), kinds.toString(), String.valueOf(suspended), reason);
        return executeIdempotent("F5_COMMISSION_SUSPENSION", idempotencyKey, hash,
                () -> suspensionInternal(userId, kinds, suspended, reason, operator, idempotencyKey));
    }

    /** F5 reverse/reissue/suspension mutate commission entitlements and may run only from an approved A2 replay. */
    private ApiResult<Map<String, Object>> requireApprovedReplay() {
        if (!A2ReplayContext.isReplaying() || !StringUtils.hasText(A2ReplayContext.operationId())) {
            return ApiResult.fail(409, "A2_CONFIRMATION_REQUIRED");
        }
        return null;
    }

    public ApiResult<Map<String, Object>> updateAnomalyConfig(
            String idempotencyKey, F5CommissionAnomalyConfigRequest request) {
        String reason = reason(request == null ? null : request.reason());
        String operator = operator(request == null ? null : request.operator());
        BigDecimal sigma = request == null ? null : request.commissionAnomalySigma();
        BigDecimal layerRatio = request == null ? null : request.layerRatioAnomalyPct();
        if (sigma == null || sigma.compareTo(new BigDecimal("2")) < 0
                || sigma.compareTo(new BigDecimal("5")) > 0
                || sigma.multiply(new BigDecimal("2")).stripTrailingZeros().scale() > 0) {
            return ApiResult.fail(422, "COMMISSION_ANOMALY_SIGMA_RANGE_2_5_STEP_0_5");
        }
        if (layerRatio == null || layerRatio.compareTo(new BigDecimal("10")) < 0
                || layerRatio.compareTo(new BigDecimal("50")) > 0) {
            return ApiResult.fail(422, "LAYER_RATIO_ANOMALY_PCT_RANGE_10_50");
        }
        String hash = requestHash("anomaly-config", sigma.toPlainString(), layerRatio.toPlainString(), reason);
        return executeIdempotent("F5_COMMISSION_ANOMALY_CONFIG", idempotencyKey, hash, () -> {
            BigDecimal beforeSigma = anomalySigma();
            BigDecimal beforeLayer = layerRatioThreshold();
            configFacade.upsertAdminValue(
                    ANOMALY_SIGMA_KEY, sigma.stripTrailingZeros().toPlainString(),
                    "NUMBER", "team", "F5 commission amount anomaly sigma");
            configFacade.upsertAdminValue(
                    LAYER_RATIO_KEY, layerRatio.stripTrailingZeros().toPlainString(),
                    "NUMBER", "team", "F5 network layer-ratio anomaly threshold");
            String operationNo = operationNo("CFG");
            mapper.insertOperation(
                    operationNo, "ANOMALY_CONFIG", null, null, null, "all", null, null, null,
                    reason, operator, idempotencyKey);
            Map<String, Object> detail = linked(
                    "beforeCommissionAnomalySigma", beforeSigma,
                    "afterCommissionAnomalySigma", sigma,
                    "beforeLayerRatioAnomalyPct", beforeLayer,
                    "afterLayerRatioAnomalyPct", layerRatio,
                    "operator", operator,
                    "reason", reason);
            audit("ADMIN_COMMISSION_ANOMALY_CONFIG_CHANGED", operationNo, null, operator, detail);
            eventOutboxService.publish(
                    "ADMIN_COMMISSION", operationNo, "admin.commission_anomaly_config_changed", detail);
            return ApiResult.ok(linked(
                    "operationNo", operationNo,
                    "commissionAnomalySigma", sigma,
                    "layerRatioAnomalyPct", layerRatio));
        });
    }

    private ApiResult<Map<String, Object>> reverseInternal(
            Long eventId,
            String commissionId,
            String refundRef,
            String reason,
            String operator,
            String idempotencyKey) {
        Map<String, Object> event = mapper.findEventForUpdate(eventId);
        if (event == null) {
            return ApiResult.fail(404, "COMMISSION_EVENT_NOT_FOUND");
        }
        String status = text(event.get("rawStatus")).toUpperCase(Locale.ROOT);
        if (!Set.of("PENDING", "COOLING", "UNLOCKED", "AVAILABLE").contains(status)) {
            return ApiResult.fail(409, "COMMISSION_REVERSE_STATE_CONFLICT");
        }
        if (mapper.countEvidenceReference(eventId, refundRef) < 1) {
            return ApiResult.fail(422, "REFUND_REF_NOT_FOUND");
        }
        if (mapper.reverseEventCas(eventId) != 1) {
            return ApiResult.fail(409, "COMMISSION_REVERSE_CAS_CONFLICT");
        }
        BigDecimal amount = decimal(event.get("amount"));
        Long userId = longValue(event.get("userId"));
        String currency = text(event.get("currency")).toUpperCase(Locale.ROOT);
        if (amount.signum() > 0) {
            ledgerPostingFacade.postLedgerEntry(
                    "F5-REVERSE-" + eventId,
                    userId,
                    "TEAM_COMMISSION",
                    currency,
                    "OUT",
                    amount,
                    "SUCCESS",
                    "F5 commission reverse | commissionId=" + commissionId + " | refundRef=" + refundRef);
        }
        String operationNo = operationNo("REV");
        mapper.insertOperation(
                operationNo, "REVERSE", eventId, null, userId, text(event.get("kind")), amount,
                currency, refundRef, reason, operator, idempotencyKey);
        Map<String, Object> detail = linked(
                "commissionId", commissionId,
                "userId", userId,
                "kind", event.get("kind"),
                "amount", amount,
                "currency", currency,
                "refundRef", refundRef,
                "operator", operator,
                "reason", reason);
        audit("ADMIN_COMMISSION_REVERSED", commissionId, userId, operator, detail);
        eventOutboxService.publish("ADMIN_COMMISSION", commissionId, "admin.commission_reversed", detail);
        return ApiResult.ok(linked(
                "operationNo", operationNo,
                "commissionId", commissionId,
                "status", "reversed",
                "ledgerBizNo", "F5-REVERSE-" + eventId));
    }

    private ApiResult<Map<String, Object>> reissueInternal(
            List<Long> eventIds, String reason, String operator, String idempotencyKey) {
        TreasuryCoverageSnapshot coverage = coverageFacade.snapshot();
        if (coverage == null || !coverage.reliable()) {
            return ApiResult.fail(503, "COVERAGE_SNAPSHOT_UNAVAILABLE");
        }
        if (coverage.coverageRatio() == null || coverage.redlinePct() == null
                || coverage.coverageRatio().compareTo(coverage.redlinePct()) < 0) {
            return ApiResult.fail(
                    OpsErrorCode.COVERAGE_BELOW_REDLINE.httpStatus(),
                    OpsErrorCode.COVERAGE_BELOW_REDLINE.name());
        }
        String batchNo = operationNo("REI");
        List<Map<String, Object>> reissued = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        for (Long eventId : eventIds) {
            Map<String, Object> original = mapper.findEventForUpdate(eventId);
            if (original == null) {
                return ApiResult.fail(409, "COMMISSION_REISSUE_SOURCE_NOT_FOUND:CM-" + eventId);
            }
            String status = text(original.get("rawStatus")).toUpperCase(Locale.ROOT);
            if (!Set.of("REVERSED", "REJECTED", "ROLLBACK").contains(status)) {
                return ApiResult.fail(409, "COMMISSION_REISSUE_STATE_CONFLICT:CM-" + eventId);
            }
            if (mapper.insertReissueFromOriginal(eventId, batchNo, coolingDays(), reason) != 1) {
                return ApiResult.fail(409, "COMMISSION_REISSUE_CAS_CONFLICT:CM-" + eventId);
            }
            Long newEventId = mapper.selectLastInsertId();
            if (newEventId == null || newEventId <= 0) {
                throw new IllegalStateException("COMMISSION_REISSUE_INSERT_ID_MISSING");
            }
            BigDecimal amount = decimal(original.get("amount"));
            Long userId = longValue(original.get("userId"));
            String currency = text(original.get("currency")).toUpperCase(Locale.ROOT);
            String kind = text(original.get("kind")).toLowerCase(Locale.ROOT);
            String ledgerStatus = Set.of("network", "binary").contains(kind) ? "PENDING" : "SUCCESS";
            if (amount.signum() > 0) {
                ledgerPostingFacade.postLedgerEntry(
                        "F5-REISSUE-" + newEventId,
                        userId,
                        "TEAM_COMMISSION",
                        currency,
                        "IN",
                        amount,
                        ledgerStatus,
                        "F5 commission reissue | source=CM-" + eventId + " | batch=" + batchNo);
            }
            mapper.insertOperation(
                    batchNo + "-" + eventId, "REISSUE", eventId, newEventId, userId, kind, amount,
                    currency, null, reason, operator, idempotencyKey);
            total = total.add(amount);
            reissued.add(linked(
                    "sourceCommissionId", "CM-" + eventId,
                    "commissionId", "CM-" + newEventId,
                    "userId", userId,
                    "kind", kind,
                    "currency", currency,
                    "amount", amount));
        }
        Map<String, Object> detail = linked(
                "batchNo", batchNo,
                "commissionIds", eventIds.stream().map(id -> "CM-" + id).toList(),
                "count", reissued.size(),
                "amount", total,
                "operator", operator,
                "reason", reason);
        audit("ADMIN_COMMISSION_REISSUED", batchNo, null, operator, detail);
        eventOutboxService.publish("ADMIN_COMMISSION", batchNo, "admin.commission_reissued", detail);
        return ApiResult.ok(linked(
                "batchNo", batchNo,
                "count", reissued.size(),
                "totalAmount", total,
                "items", reissued));
    }

    private ApiResult<Map<String, Object>> suspensionInternal(
            Long userId,
            List<String> kinds,
            boolean suspended,
            String reason,
            String operator,
            String idempotencyKey) {
        int changed = 0;
        int frozen = 0;
        for (String kind : kinds) {
            int rows = suspended
                    ? mapper.suspendUserKind(userId, kind, reason, operator)
                    : mapper.resumeUserKind(userId, kind, reason, operator);
            changed += rows;
            if (suspended) {
                frozen += mapper.freezeOpenEventsForSuspension(userId, kind);
            }
        }
        if (changed < 1) {
            return ApiResult.fail(409, suspended
                    ? "COMMISSION_USER_KINDS_ALREADY_SUSPENDED"
                    : "COMMISSION_USER_KINDS_NOT_SUSPENDED");
        }
        String operationNo = operationNo(suspended ? "SUS" : "RES");
        String operationType = suspended ? "SUSPEND" : "RESUME";
        mapper.insertOperation(
                operationNo, operationType, null, null, userId, String.join(",", kinds), null, null,
                null, reason, operator, idempotencyKey);
        Map<String, Object> detail = linked(
                "userId", userId,
                "kinds", kinds,
                "suspended", suspended,
                "frozenOpenEvents", frozen,
                "operator", operator,
                "reason", reason);
        String action = suspended
                ? "ADMIN_COMMISSION_USER_SUSPENDED"
                : "ADMIN_COMMISSION_USER_RESUMED";
        audit(action, String.valueOf(userId), userId, operator, detail);
        eventOutboxService.publish(
                "ADMIN_COMMISSION_USER",
                String.valueOf(userId),
                suspended ? "admin.commission_user_suspended" : "admin.commission_user_resumed",
                detail);
        return ApiResult.ok(linked(
                "operationNo", operationNo,
                "userId", userId,
                "kinds", kinds,
                "suspended", suspended,
                "frozenOpenEvents", frozen));
    }

    private List<Map<String, Object>> anomalyViews(List<Map<String, Object>> source) {
        List<Map<String, Object>> rows = source.stream().map(this::eventView).toList();
        Map<String, List<Map<String, Object>>> byKind = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            byKind.computeIfAbsent(text(row.get("kind")), ignored -> new ArrayList<>()).add(row);
        }
        List<Map<String, Object>> anomalies = new ArrayList<>();
        double sigmaThreshold = anomalySigma().doubleValue();
        for (Map.Entry<String, List<Map<String, Object>>> entry : byKind.entrySet()) {
            double mean = entry.getValue().stream().mapToDouble(row -> decimal(row.get("amount")).doubleValue()).average().orElse(0);
            double variance = entry.getValue().stream()
                    .mapToDouble(row -> {
                        double delta = decimal(row.get("amount")).doubleValue() - mean;
                        return delta * delta;
                    })
                    .average().orElse(0);
            double standardDeviation = Math.sqrt(variance);
            if (standardDeviation > 0) {
                for (Map<String, Object> row : entry.getValue()) {
                    double z = Math.abs(decimal(row.get("amount")).doubleValue() - mean) / standardDeviation;
                    if (z > sigmaThreshold) {
                        anomalies.add(anomaly(
                                "amount",
                                row,
                                "单笔金额偏离 " + entry.getKey() + " 均值 "
                                        + BigDecimal.valueOf(z).setScale(2, RoundingMode.HALF_UP) + "σ",
                                "K4 金额异常维度"));
                    }
                }
            }
        }

        Map<Long, Long> frequency = rows.stream()
                .filter(row -> row.get("userId") != null)
                .collect(java.util.stream.Collectors.groupingBy(
                        row -> longValue(row.get("userId")),
                        LinkedHashMap::new,
                        java.util.stream.Collectors.counting()));
        for (Map.Entry<Long, Long> entry : frequency.entrySet()) {
            if (entry.getValue() >= 10) {
                Map<String, Object> row = rows.stream()
                        .filter(candidate -> Objects.equals(entry.getKey(), longValue(candidate.get("userId"))))
                        .findFirst().orElse(Map.of());
                anomalies.add(anomaly(
                        "frequency",
                        row,
                        "最近 200 笔样本中同用户出现 " + entry.getValue() + " 笔",
                        "K1/K2 高频簇复核"));
            }
        }
        anomalies.addAll(layerRatioAnomalies(rows));
        return anomalies;
    }

    private List<Map<String, Object>> layerRatioAnomalies(List<Map<String, Object>> rows) {
        Map<Integer, BigDecimal> theory = new LinkedHashMap<>();
        for (Map<String, Object> row : mapper.theoreticalLayerRates()) {
            Long layer = longValue(row.get("layer"));
            if (layer != null) {
                theory.put(layer.intValue(), decimal(row.get("theoreticalPct")));
            }
        }
        BigDecimal networkTotal = rows.stream()
                .filter(row -> "network".equals(text(row.get("kind"))))
                .map(row -> decimal(row.get("amount")))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (networkTotal.signum() <= 0 || theory.isEmpty()) {
            return List.of();
        }
        BigDecimal theoryTotal = theory.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        if (theoryTotal.signum() <= 0) {
            return List.of();
        }
        Map<Integer, BigDecimal> actual = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            if (!"network".equals(text(row.get("kind")))) continue;
            Long layer = longValue(row.get("layer"));
            if (layer != null) {
                actual.merge(layer.intValue(), decimal(row.get("amount")), BigDecimal::add);
            }
        }
        BigDecimal threshold = layerRatioThreshold();
        List<Map<String, Object>> anomalies = new ArrayList<>();
        for (Map.Entry<Integer, BigDecimal> entry : actual.entrySet()) {
            BigDecimal expected = theory.get(entry.getKey());
            if (expected == null) continue;
            BigDecimal actualPct = entry.getValue()
                    .multiply(new BigDecimal("100"))
                    .divide(networkTotal, 6, RoundingMode.HALF_UP);
            BigDecimal theoreticalPct = expected
                    .multiply(new BigDecimal("100"))
                    .divide(theoryTotal, 6, RoundingMode.HALF_UP);
            BigDecimal deviation = actualPct.subtract(theoreticalPct).abs();
            if (deviation.compareTo(threshold) > 0) {
                anomalies.add(linked(
                        "id", "LAYER-L" + entry.getKey(),
                        "type", "layer-ratio",
                        "userId", "",
                        "cluster", "K4",
                        "evidence", "L" + entry.getKey() + " 实际占比 "
                                + actualPct.setScale(2, RoundingMode.HALF_UP) + "% / 理论占比 "
                                + theoreticalPct.setScale(2, RoundingMode.HALF_UP) + "%",
                        "relatedKCluster", "K4 层比例套利维度",
                        "status", "open"));
            }
        }
        return anomalies;
    }

    private Map<String, Object> anomaly(
            String type, Map<String, Object> row, String evidence, String cluster) {
        return linked(
                "id", "AN-" + text(row.getOrDefault("commissionId", UUID.randomUUID().toString())),
                "type", type,
                "commissionId", row.getOrDefault("commissionId", ""),
                "userId", row.getOrDefault("userId", ""),
                "cluster", cluster,
                "evidence", evidence,
                "relatedKCluster", cluster,
                "status", "open");
    }

    private Map<String, Object> eventView(Map<String, Object> raw) {
        Map<String, Object> row = new LinkedHashMap<>(raw);
        String commissionId = text(raw.get("commissionId"));
        Long userId = longValue(raw.get("userId"));
        String status = text(raw.get("status"));
        row.put("id", commissionId);
        row.put("user", userId == null ? "" : "U" + String.format("%08d", userId));
        row.put("cooldownPercent", "cooling".equals(status) ? 0 : 100);
        row.put("cooldownLabel", switch (status) {
            case "cooling" -> "冷却计提";
            case "unlocked" -> "已解锁";
            case "withdrawn" -> "已提现";
            case "reversed" -> "已撤销";
            case "frozen" -> "已冻结";
            default -> status;
        });
        row.put("state", switch (status) {
            case "cooling" -> "计提";
            case "unlocked" -> "unlocked";
            case "withdrawn" -> "withdrawn";
            case "reversed" -> "异常回退";
            case "frozen" -> "frozen";
            default -> status;
        });
        row.put("auditKey", "F.commission." + commissionId + ".status");
        return row;
    }

    private Map<String, Object> summary(
            List<Map<String, Object>> kindAggregates,
            List<Map<String, Object>> statusAggregates) {
        long frozen = statusAggregates.stream()
                .filter(row -> "frozen".equals(text(row.get("status"))))
                .mapToLong(row -> longValue(row.get("count")) == null ? 0L : longValue(row.get("count")))
                .sum();
        List<Map<String, Object>> cooling = statusAggregates.stream()
                .filter(row -> "cooling".equals(text(row.get("status")))).toList();
        List<Map<String, Object>> unlocked = statusAggregates.stream()
                .filter(row -> "unlocked".equals(text(row.get("status")))).toList();
        return linked(
                "monthlyCommissionSpendLabel", currencyLabel(kindAggregates),
                "monthlyCommissionSpend", currencySnapshot(kindAggregates),
                "coolingBalanceLabel", currencyLabel(cooling),
                "coolingBalance", currencySnapshot(cooling),
                "withdrawableThisMonthLabel", currencyLabel(unlocked),
                "withdrawableThisMonth", currencySnapshot(unlocked),
                "frozenCount", frozen);
    }

    private List<Map<String, Object>> kindSummary(List<Map<String, Object>> rows) {
        Map<String, List<Map<String, Object>>> grouped = new LinkedHashMap<>();
        Map<String, Long> counts = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            String kind = text(row.get("kind"));
            grouped.computeIfAbsent(kind, ignored -> new ArrayList<>()).add(row);
            Long count = longValue(row.get("count"));
            counts.merge(kind, count == null ? 0L : count, Long::sum);
        }
        Map<String, String> labels = Map.of(
                "network", "网络版税",
                "binary", "双轨平衡匹配",
                "peer", "平级奖",
                "cultivation", "培育奖",
                "leadership", "领导奖池",
                "genesis", "创世排放");
        return List.of("network", "binary", "peer", "cultivation", "leadership", "genesis")
                .stream()
                .map(kind -> {
                    List<Map<String, Object>> aggregateRows = grouped.getOrDefault(kind, List.of());
                    long count = counts.getOrDefault(kind, 0L);
                    return linked(
                            "key", kind,
                            "code", kind.toUpperCase(Locale.ROOT),
                            "label", labels.get(kind),
                            "amountLabel", currencyLabel(aggregateRows),
                            "amounts", currencySnapshot(aggregateRows),
                            "count", count,
                            "countLabel", count + " 笔",
                            "className", "k-" + kind,
                            "amountColor", "");
                })
                .toList();
    }

    private List<Map<String, Object>> coolingPolicy() {
        int days = coolingDays();
        return List.of(
                linked("kind", "network", "days", days, "policy", COOLING_DAYS_KEY),
                linked("kind", "binary", "days", days, "policy", COOLING_DAYS_KEY),
                linked("kind", "peer", "days", 0, "policy", "无独立冷却"),
                linked("kind", "cultivation", "days", 0, "policy", "NEX 即时入账"),
                linked("kind", "leadership", "days", 0, "policy", "周结后即时入账"),
                linked("kind", "genesis", "days", 0, "policy", "每日排放即时入账"));
    }

    private List<Map<String, Object>> statusDistribution(List<Map<String, Object>> rows) {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            Long count = longValue(row.get("count"));
            counts.merge(text(row.get("status")), count == null ? 0L : count, Long::sum);
        }
        return List.of(
                linked("name", "已解锁可提", "color", "var(--success)", "count", counts.getOrDefault("unlocked", 0L)),
                linked("name", "冷却计提中", "color", "var(--warning)", "count", counts.getOrDefault("cooling", 0L)),
                linked("name", "已提现", "color", "var(--cyan)", "count", counts.getOrDefault("withdrawn", 0L)),
                linked("name", "已撤销", "color", "var(--danger)", "count", counts.getOrDefault("reversed", 0L)),
                linked("name", "已冻结", "color", "var(--ink-4)", "count", counts.getOrDefault("frozen", 0L)));
    }

    private String currencyLabel(List<Map<String, Object>> rows) {
        Map<String, Object> totals = currencySnapshot(rows);
        return "USDT " + totals.get("usdt") + " · NEX " + totals.get("nex");
    }

    private Map<String, Object> currencySnapshot(List<Map<String, Object>> rows) {
        Map<String, BigDecimal> totals = new LinkedHashMap<>();
        long count = 0L;
        for (Map<String, Object> row : rows) {
            String currency = text(row.get("currency")).toUpperCase(Locale.ROOT);
            if (!Set.of("USDT", "NEX").contains(currency)) {
                throw new BizException(500, "F5_COMMISSION_CURRENCY_UNKNOWN");
            }
            BigDecimal amount = decimal(row.get("amount"));
            Long rowCount = longValue(row.get("count"));
            if (amount.signum() < 0 || rowCount == null || rowCount < 0) {
                throw new BizException(500, "F5_COMMISSION_AGGREGATE_INVALID");
            }
            totals.merge(currency, amount, BigDecimal::add);
            count = Math.addExact(count, rowCount);
        }
        return linked(
                "usdt", totals.getOrDefault("USDT", BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP),
                "nex", totals.getOrDefault("NEX", BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP),
                "count", count);
    }

    private List<Map<String, Object>> recentAudit(List<Map<String, Object>> rows) {
        return rows.stream().limit(12).map(this::eventView).map(row -> linked(
                "when", row.getOrDefault("settledAt", ""),
                "text", "佣金事件 " + row.get("commissionId") + " 状态 " + row.get("status")
                        + " · " + row.get("kind"),
                "level", Set.of("reversed", "frozen").contains(text(row.get("status"))) ? "HIGH" : "LOW"))
                .toList();
    }

    private NormalizedQuery normalizeQuery(F5CommissionQuery query) {
        String kind = normalizedOptional(query == null ? null : query.kind());
        if (kind != null && !KINDS.contains(kind)) {
            throw new IllegalArgumentException("COMMISSION_KIND_INVALID");
        }
        String currency = normalizedOptional(query == null ? null : query.currency());
        if (currency != null && !Set.of("usdt", "nex").contains(currency)) {
            throw new IllegalArgumentException("COMMISSION_CURRENCY_INVALID");
        }
        String status = normalizedOptional(query == null ? null : query.status());
        if (status != null && !"all".equals(status) && !STATUSES.contains(status)) {
            throw new IllegalArgumentException("COMMISSION_STATUS_INVALID");
        }
        if ("all".equals(status)) status = null;
        String cohort = StringUtils.hasText(query == null ? null : query.cohort())
                ? query.cohort().trim()
                : null;
        if (cohort != null && !cohort.matches("^\\d{4}-(0[1-9]|1[0-2])$")) {
            throw new IllegalArgumentException("COMMISSION_COHORT_INVALID");
        }
        Long userId = query == null ? null : query.userId();
        if (userId != null && userId <= 0) {
            throw new IllegalArgumentException("COMMISSION_USER_ID_INVALID");
        }
        int limit = query == null || query.limit() == null
                ? DEFAULT_LIMIT
                : Math.max(1, Math.min(query.limit(), MAX_LIMIT));
        return new NormalizedQuery(
                kind,
                currency == null ? null : currency.toUpperCase(Locale.ROOT),
                userId,
                status,
                cohort,
                query == null ? null : query.cursor(),
                limit);
    }

    private List<Long> normalizeCommissionIds(List<String> ids) {
        if (ids == null || ids.isEmpty() || ids.size() > 100) {
            throw new IllegalArgumentException("COMMISSION_IDS_REQUIRED_1_100");
        }
        List<Long> normalized = ids.stream().map(this::eventId).distinct().toList();
        if (normalized.size() != ids.size()) {
            throw new IllegalArgumentException("COMMISSION_IDS_DUPLICATED");
        }
        return normalized;
    }

    private List<String> normalizeKinds(List<String> kinds) {
        if (kinds == null || kinds.isEmpty()) {
            throw new IllegalArgumentException("COMMISSION_KINDS_REQUIRED");
        }
        List<String> normalized = kinds.stream().map(this::normalizedOptional).distinct().toList();
        if (normalized.stream().anyMatch(kind -> kind == null || !KINDS.contains(kind))) {
            throw new IllegalArgumentException("COMMISSION_KIND_INVALID");
        }
        return normalized;
    }

    private Long eventId(String commissionId) {
        String value = requireText(commissionId, "COMMISSION_ID_REQUIRED").toUpperCase(Locale.ROOT);
        if (!value.matches("^CM-\\d+$")) {
            throw new IllegalArgumentException("COMMISSION_ID_INVALID");
        }
        return Long.parseLong(value.substring(3));
    }

    private String reason(String value) {
        String normalized = requireText(value, OpsErrorCode.REASON_REQUIRED.name());
        if (normalized.length() < 8 || normalized.length() > 200) {
            throw new IllegalArgumentException("REASON_LENGTH_8_200");
        }
        return normalized;
    }

    private String operator(String value) {
        return StringUtils.hasText(value) ? value.trim() : "authenticated-admin";
    }

    private String requireText(String value, String error) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException(error);
        }
        return value.trim();
    }

    private String normalizedOptional(String value) {
        return StringUtils.hasText(value) ? value.trim().toLowerCase(Locale.ROOT) : null;
    }

    private int coolingDays() {
        return configFacade.activeValue(COOLING_DAYS_KEY)
                .map(value -> intValue(value, 30))
                .filter(value -> value >= 0 && value <= 90)
                .orElse(30);
    }

    private BigDecimal anomalySigma() {
        return configFacade.activeValue(ANOMALY_SIGMA_KEY)
                .map(value -> decimal(value, new BigDecimal("3")))
                .orElse(new BigDecimal("3"));
    }

    private BigDecimal layerRatioThreshold() {
        return configFacade.activeValue(LAYER_RATIO_KEY)
                .map(value -> decimal(value, new BigDecimal("20")))
                .orElse(new BigDecimal("20"));
    }

    private void audit(
            String action, String resourceId, Long userId, String operator, Map<String, Object> detail) {
        auditLogService.recordRequired(AuditLogWriteRequest.builder()
                .action(action)
                .resourceType("COMMISSION_EVENT")
                .resourceId(resourceId)
                .bizNo(resourceId)
                .userId(userId)
                .actorUsername(operator)
                .result("SUCCESS")
                .riskLevel("HIGH")
                .detail(detail)
                .build());
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private ApiResult<Map<String, Object>> executeIdempotent(
            String scope,
            String idempotencyKey,
            String hash,
            java.util.function.Supplier<ApiResult<Map<String, Object>>> action) {
        return (ApiResult<Map<String, Object>>) (ApiResult) idempotencyService.execute(
                scope,
                idempotencyKey,
                hash,
                ApiResult.class,
                action::get);
    }

    private String requestHash(String... parts) {
        String joined = String.join("\u001f", parts);
        return UUID.nameUUIDFromBytes(joined.getBytes(StandardCharsets.UTF_8)).toString();
    }

    private String operationNo(String prefix) {
        return "F5-" + prefix + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
    }

    private String money(BigDecimal value) {
        return "$" + value.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private BigDecimal decimal(Object value) {
        return decimal(value, BigDecimal.ZERO);
    }

    private BigDecimal decimal(Object value, BigDecimal fallback) {
        if (value instanceof BigDecimal decimal) return decimal;
        if (value == null) return fallback;
        try {
            return new BigDecimal(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private Long longValue(Object value) {
        if (value instanceof Number number) return number.longValue();
        if (value == null) return null;
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private int intValue(String value, int fallback) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private Map<String, Object> linked(Object... values) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int index = 0; index + 1 < values.length; index += 2) {
            map.put(String.valueOf(values[index]), values[index + 1]);
        }
        return map;
    }

    private record NormalizedQuery(
            String kind,
            String currency,
            Long userId,
            String status,
            String cohort,
            Long cursor,
            int limit) {
    }
}
