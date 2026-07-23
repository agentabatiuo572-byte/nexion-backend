package ffdd.opsconsole.janus.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import ffdd.opsconsole.common.api.OpsErrorCode;
import ffdd.opsconsole.janus.domain.JanusDeviceView;
import ffdd.opsconsole.janus.domain.JanusRepository;
import ffdd.opsconsole.janus.domain.JanusRole;
import ffdd.opsconsole.janus.domain.JanusRuleEvaluator;
import ffdd.opsconsole.janus.domain.JanusStrategyVersionView;
import ffdd.opsconsole.janus.domain.JanusStrategyView;
import ffdd.opsconsole.janus.domain.JanusTransitionPolicy;
import ffdd.opsconsole.janus.dto.JanusDeviceQueryRequest;
import ffdd.opsconsole.janus.dto.JanusDeviceReportRequest;
import ffdd.opsconsole.janus.dto.JanusCommandAckRequest;
import ffdd.opsconsole.janus.dto.JanusStatusChangeRequest;
import ffdd.opsconsole.janus.dto.JanusStrategyActionRequest;
import ffdd.opsconsole.janus.dto.JanusStrategyUpsertRequest;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.api.PageResult;
import ffdd.opsconsole.shared.audit.AuditLogQueryRequest;
import ffdd.opsconsole.shared.audit.AuditLogRecord;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.shared.outbox.EventOutboxService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class OpsJanusService {
    private record ReportEvaluation(String status, String strategyId, Integer strategyVersion, String action,
                                    String remoteUrlKey, Map<String, Object> ruleResults, boolean quotaReserved) {}

    private static final List<String> STATUSES = List.of("NEW", "OBSERVING", "RECOMMENDED", "HIT", "ACTIVATED",
            "ENV_FILTERED", "MANUAL_HOLD", "MANUAL_FORCED", "BLOCKED", "STALE", "RESET", "ERROR");
    private static final List<String> ACTIONS = List.of("BENIGN", "RECOMMEND", "REVERSAL_IMMEDIATE",
            "REVERSAL_SESSION_EDGE", "ENV_FILTER", "MANUAL_HOLD", "BLOCK", "DRY_RUN_ONLY");
    private static final List<String> RULE_FIELDS = List.of("installDays", "appOpenCount", "sessionCount",
            "foregroundDurationSeconds", "repeatStreakDays", "benchmarkViewed", "optimizeDone", "marketViewed",
            "walletViewed", "maturityScore", "inviteCode", "channel", "environmentRiskScore", "isHeadless",
            "automationSignalCount", "fpBlocklistHit", "screenAnomaly", "timezoneMismatch", "activated", "status");
    private static final Set<String> REMOTE_TARGETS = Set.of("default", "backup", "promo");
    private static final Set<String> CHANNELS = Set.of("official", "ad", "invite", "test", "internal");
    private static final Set<String> COMMAND_ACTIONS = Set.of("REVERSAL_IMMEDIATE", "REVERSAL_SESSION_EDGE",
            "ENV_FILTER", "MANUAL_HOLD", "BLOCK");
    private static final Set<String> RULE_MODES = Set.of("ALL", "ANY", "N_OF_M", "NOT", "WEIGHTED_SCORE");
    private static final Set<String> RULE_OPERATORS = Set.of("=", "!=", ">", ">=", "<", "<=", "in",
            "notIn", "between", "contains");
    private static final Set<String> NUMERIC_RULE_FIELDS = Set.of("installDays", "appOpenCount", "sessionCount",
            "foregroundDurationSeconds", "repeatStreakDays", "maturityScore", "recommendationScore",
            "environmentRiskScore", "automationSignalCount");
    private static final Set<String> BOOLEAN_RULE_FIELDS = Set.of("benchmarkViewed", "optimizeDone", "marketViewed",
            "walletViewed", "isHeadless", "fpBlocklistHit", "screenAnomaly", "timezoneMismatch", "activated");
    private static final Set<String> STRING_RULE_FIELDS = Set.of("inviteCode", "channel", "status");
    private static final Set<String> SCOPE_FIELDS = Set.of("channels", "inviteCodes", "cohortIds");
    private static final Set<String> SAFEGUARD_FIELDS = Set.of("maxDailyRecommendations", "maxDailyHits",
            "requireFreshReportMinutes", "minDecisionIntervalHours");
    private static final Set<String> ROLLOUT_FIELDS = Set.of("percent", "cohortIds", "startedAt", "endsAt");
    private static final Set<String> HEALTH_FIELDS = Set.of("maxHitRate", "maxFilterRate", "minActivationRate");
    private static final Set<String> REASON_CATEGORIES = Set.of(
            "等待更多行为数据", "复核环境信号", "已知合作 / 内部设备", "疑似审核 / 自动化环境",
            "高风险设备排除", "客诉 / 线索跟进", "现场演示需要", "其他(在详细原因说明)");

    private final JanusRepository repository;
    private final JanusRuleEvaluator ruleEvaluator;
    private final ObjectMapper objectMapper;
    private final AuditLogService auditLogService;
    private final EventOutboxService outboxService;
    private final JanusTransitionPolicy transitionPolicy = new JanusTransitionPolicy();

    public ApiResult<Map<String, Object>> metadata() {
        return ApiResult.ok(Map.of(
                "statuses", STATUSES,
                "actions", ACTIONS,
                "ruleFields", RULE_FIELDS,
                "remoteTargets", List.of("default", "backup", "promo"),
                "transitions", transitionPolicy.metadata(),
                "sourceTables", List.of("nx_janus_device", "nx_janus_strategy", "nx_janus_strategy_version",
                        "nx_janus_evaluation", "nx_janus_daily_quota", "nx_janus_command",
                        "nx_audit_log", "nx_event_outbox")));
    }

    public ApiResult<PageResult<JanusDeviceView>> devices(JanusDeviceQueryRequest request) {
        return ApiResult.ok(repository.pageDevices(request));
    }

    public ApiResult<JanusDeviceView> device(String sid) {
        if (!StringUtils.hasText(sid)) return ApiResult.fail(422, "SID_REQUIRED");
        return repository.findDevice(sid.trim()).map(ApiResult::ok)
                .orElseGet(() -> ApiResult.fail(404, "JANUS_DEVICE_NOT_FOUND"));
    }

    @Transactional
    public ApiResult<JanusDeviceView> reportDevice(Long userId, JanusDeviceReportRequest request) {
        String validation = validateReport(userId, request);
        if (validation != null) return ApiResult.fail(422, validation);
        String sid = deviceSid(userId, request.deviceId());
        long startedAt = System.nanoTime();
        JanusDeviceReportRequest telemetry = normalizedReport(request);
        String requestHash = hash(telemetry);
        repository.expireDeviceOverride(userId, sid, System.currentTimeMillis());
        JanusDeviceView prior = repository.findDevice(sid).orElse(null);
        Optional<String> existingRequestHash = repository.findEvaluationRequestHash(sid, telemetry.reportId());
        if (existingRequestHash.isPresent()) return reportReplay(sid, requestHash, existingRequestHash);
        if (prior != null && prior.lastSeenAt() != null && telemetry.reportedAt() < prior.lastSeenAt()) {
            int elapsedMs = (int) Math.min(Integer.MAX_VALUE, (System.nanoTime() - startedAt) / 1_000_000L);
            boolean accepted = repository.insertEvaluation(sid, telemetry.reportId(), requestHash,
                    sessionId(telemetry), null, null,
                    json(reportInput(telemetry)),
                    json(Map.of("passed", false, "trace", List.of("OUT_OF_ORDER_REPORT"))),
                    "BENIGN", prior.status(), "OUT_OF_ORDER_REPORT", elapsedMs, "janus-server-v1");
            if (!accepted) return reportReplay(sid, requestHash,
                    repository.findEvaluationRequestHash(sid, telemetry.reportId()));
            return ApiResult.ok(prior);
        }
        ReportEvaluation evaluation = evaluateReport(sid, telemetry, prior);
        JanusDeviceReportRequest authoritative = authoritativeReport(telemetry, evaluation);
        int elapsedMs = (int) Math.min(Integer.MAX_VALUE, (System.nanoTime() - startedAt) / 1_000_000L);
        boolean accepted = repository.insertEvaluation(sid, authoritative.reportId(), requestHash,
                sessionId(authoritative), evaluation.strategyId(), evaluation.strategyVersion(), json(reportInput(authoritative)),
                json(evaluation.ruleResults()), evaluation.action(), evaluation.status(), null, elapsedMs,
                "janus-server-v1");
        if (!accepted) {
            if (evaluation.quotaReserved()) {
                repository.releaseDailyEvaluation(evaluation.strategyId(), evaluation.action());
            }
            return reportReplay(sid, requestHash,
                    repository.findEvaluationRequestHash(sid, authoritative.reportId()));
        }
        repository.upsertDeviceReport(userId, sid, authoritative);
        JanusDeviceView persisted = repository.findDevice(sid).orElse(null);
        if (persisted == null) throw new IllegalStateException("JANUS_REPORT_NOT_PERSISTED");
        boolean recovery = prior != null && "strategy".equals(prior.statusSource())
                && (!evaluation.status().equals(prior.status())
                || !java.util.Objects.equals(evaluation.remoteUrlKey(), prior.remoteUrlKey()));
        if (COMMAND_ACTIONS.contains(evaluation.action()) || recovery) {
            ObjectNode command = objectMapper.createObjectNode();
            command.put("source", "strategy");
            command.put("reportId", authoritative.reportId());
            command.put("action", evaluation.action());
            if (evaluation.strategyId() != null) command.put("strategyId", evaluation.strategyId());
            boolean published = repository.publishStrategyCommand(sid, persisted.version(), evaluation.status(),
                    evaluation.remoteUrlKey(), command.toString());
            if (published) {
                outboxService.publish("JANUS_DEVICE", sid, "JANUS_STRATEGY_COMMAND_PUBLISHED", command);
                persisted = repository.findDevice(sid).orElse(persisted);
            } else if (evaluation.quotaReserved() && COMMAND_ACTIONS.contains(evaluation.action())) {
                repository.releaseDailyEvaluation(evaluation.strategyId(), evaluation.action());
            }
        }
        return ApiResult.ok(persisted);
    }

    private ApiResult<JanusDeviceView> reportReplay(String sid, String requestHash,
                                                     Optional<String> existingRequestHash) {
        if (existingRequestHash.isEmpty() || !requestHash.equals(existingRequestHash.get())) {
            return ApiResult.fail(409, "JANUS_REPORT_REPLAY_CONFLICT");
        }
        return repository.findDevice(sid).map(ApiResult::ok)
                .orElseGet(() -> ApiResult.fail(409, "JANUS_REPORT_REPLAY_STATE_MISSING"));
    }

    public ApiResult<Map<String, Object>> pendingCommand(Long userId, String deviceId) {
        if (userId == null || userId <= 0) return ApiResult.fail(403, "USER_AUTH_REQUIRED");
        if (!validText(deviceId, 128)) return ApiResult.fail(422, "DEVICE_ID_REQUIRED");
        String sid = deviceSid(userId, deviceId);
        repository.expireDeviceOverride(userId, sid, System.currentTimeMillis());
        Optional<Map<String, Object>> command = repository.findPendingDeviceCommand(userId, sid);
        if (command.isEmpty()) return ApiResult.ok(Map.of("hasCommand", false));
        Map<String, Object> response = new LinkedHashMap<>(command.get());
        response.put("hasCommand", true);
        response.put("sid", sid);
        return ApiResult.ok(response);
    }

    @Transactional
    public ApiResult<Map<String, Object>> acknowledgeCommand(Long userId, JanusCommandAckRequest request) {
        if (userId == null || userId <= 0) return ApiResult.fail(403, "USER_AUTH_REQUIRED");
        if (request == null || !validText(request.deviceId(), 128) || request.revision() == null
                || request.revision() <= 0 || request.success() == null
                || request.message() != null && request.message().length() > 500) {
            return ApiResult.fail(422, "JANUS_ACK_INVALID");
        }
        String applied = request.appliedStatus() == null ? null : request.appliedStatus().trim().toUpperCase(Locale.ROOT);
        if (applied != null && !STATUSES.contains(applied)
                || Boolean.TRUE.equals(request.success()) && applied == null) {
            return ApiResult.fail(422, "APPLIED_STATUS_INVALID");
        }
        String sid = deviceSid(userId, request.deviceId());
        boolean updated = repository.acknowledgeDeviceCommand(userId, sid, request.revision(),
                request.success(), applied);
        if (!updated && !repository.isDeviceCommandAckReplay(userId, sid, request.revision(),
                request.success(), applied)) {
            return ApiResult.fail(409, "JANUS_ACK_CONFLICT");
        }
        String state = request.success() ? "ACKED" : "FAILED";
        if (updated) {
            repository.updateDeviceCommandRecord(sid, state);
            ObjectNode event = objectMapper.createObjectNode();
            event.put("sid", sid);
            event.put("revision", request.revision());
            event.put("state", state);
            event.put("success", request.success());
            if (applied != null) event.put("appliedStatus", applied);
            if (StringUtils.hasText(request.message())) event.put("message", request.message().trim());
            outboxService.publish("JANUS_DEVICE", sid, "JANUS_DEVICE_COMMAND_" + state, event);
        }
        return ApiResult.ok(Map.of("sid", sid, "revision", request.revision(), "state", state));
    }

    public ApiResult<Map<String, Object>> dashboard() {
        List<JanusDeviceView> devices = loadAllDevices();
        List<JanusStrategyView> strategies = repository.strategies();
        Map<String, Long> distribution = new LinkedHashMap<>();
        STATUSES.forEach(status -> distribution.put(status, 0L));
        devices.forEach(device -> distribution.computeIfPresent(device.status(), (ignored, count) -> count + 1));
        long active = devices.stream().filter(d -> d.lastSeenAt() != null && d.lastSeenAt() >= System.currentTimeMillis() - 15 * 60_000L).count();
        long filtered = distribution.get("ENV_FILTERED");
        long hit = distribution.get("HIT");
        long activated = distribution.get("ACTIVATED");
        long recommended = distribution.get("RECOMMENDED");
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("totalDevices", devices.size());
        summary.put("activeDevices", active);
        summary.put("newDevices", distribution.get("NEW"));
        summary.put("recommended", recommended);
        summary.put("hit", hit);
        summary.put("activated", activated);
        summary.put("envFiltered", filtered);
        summary.put("manualHold", distribution.get("MANUAL_HOLD"));
        summary.put("manualOverrides", devices.stream().filter(d -> !d.manualOverride().isEmpty()).count());
        summary.put("hitRate", devices.isEmpty() ? 0 : Math.round((hit + activated) * 10_000.0 / devices.size()) / 100.0);
        JanusStrategyView primary = strategies.stream().filter(s -> "active".equals(s.status()))
                .max(Comparator.comparingInt(JanusStrategyView::priority)).orElse(null);
        return ApiResult.ok(Map.of(
                "summary", summary,
                "distribution", distribution,
                "funnel", funnelData(devices, summary),
                "primaryStrategy", primary == null ? Map.of() : primary,
                "health", healthData(devices, filtered, activated),
                "recentAudit", auditData(null, null, 5)));
    }

    @Transactional
    public ApiResult<JanusDeviceView> updateStatus(String sid, String idempotencyKey, JanusStatusChangeRequest request) {
        if (!StringUtils.hasText(sid)) return ApiResult.fail(422, "SID_REQUIRED");
        if (request == null || !StringUtils.hasText(request.targetStatus())) return ApiResult.fail(422, "TARGET_STATUS_REQUIRED");
        if (!StringUtils.hasText(request.reasonCategory()) || !StringUtils.hasText(request.reasonText())
                || request.reasonText().trim().length() < 8) return ApiResult.fail(422, "REASON_REQUIRED");
        if (!REASON_CATEGORIES.contains(request.reasonCategory().trim())) {
            return ApiResult.fail(422, "REASON_CATEGORY_INVALID");
        }
        if (request.reasonText().trim().length() > 500) return ApiResult.fail(422, "REASON_TOO_LONG");
        if (request.expectedDeviceVersion() == null) return ApiResult.fail(422, "EXPECTED_VERSION_REQUIRED");
        JanusDeviceView before = repository.findDevice(sid).orElse(null);
        if (before == null) return ApiResult.fail(404, "JANUS_DEVICE_NOT_FOUND");
        String target = request.targetStatus().trim().toUpperCase(Locale.ROOT);
        String requestHash = hash(Map.of("sid", sid, "request", request));
        CommandGuard guard = commandGuard(idempotencyKey, "DEVICE_STATUS", sid, requestHash);
        if (guard.failure() != null) return cast(guard.failure());
        if (guard.duplicate()) return commandResult(idempotencyKey, JanusDeviceView.class).map(ApiResult::ok)
                .or(() -> repository.findDevice(sid).map(ApiResult::ok))
                .orElseGet(() -> ApiResult.fail(404, "JANUS_DEVICE_NOT_FOUND"));
        JanusTransitionPolicy.Validation validation = transitionPolicy.validate(before.status(), target, currentRole(),
                request.remoteUrlKey(), request.expireAt(), request.confirmationMode());
        if (!validation.allowed()) {
            repository.releaseCommandReservation(idempotencyKey.trim());
            return ApiResult.fail("ROLE_FORBIDDEN".equals(validation.code()) ? 403 : 409, validation.code());
        }
        ObjectNode payload = objectMapper.valueToTree(request);
        payload.put("sid", sid);
        payload.put("fromStatus", before.status());
        payload.put("actorId", currentActor());
        if (StringUtils.hasText(before.commandState()) && List.of("PENDING", "PUBLISHED").contains(before.commandState())) {
            repository.releaseCommandReservation(idempotencyKey.trim());
            return ApiResult.fail(409, "JANUS_COMMAND_IN_FLIGHT");
        }
        ObjectNode override = payload.deepCopy();
        override.put("createdAt", System.currentTimeMillis());
        override.put("source", "manual");
        boolean updated = repository.updateDeviceStatus(sid, request.expectedDeviceVersion(), target,
                request.remoteUrlKey(), currentActor(), request.reasonText().trim(), override.toString(), "PUBLISHED");
        if (!updated) {
            repository.releaseCommandReservation(idempotencyKey.trim());
            return ApiResult.fail(409, "VERSION_CONFLICT");
        }
        JanusDeviceView after = repository.findDevice(sid).orElseThrow();
        repository.completeCommand(idempotencyKey.trim(), "PUBLISHED", json(after));
        outboxService.publish("JANUS_DEVICE", sid, "JANUS_DEVICE_STATUS_REQUESTED", payload);
        requiredAudit("K6_DEVICE_STATUS_REQUESTED", "JANUS_DEVICE", sid, request.reasonText(),
                Map.of("before", before, "after", after, "reasonCategory", request.reasonCategory(), "idempotencyKey", idempotencyKey));
        return ApiResult.ok(after);
    }

    public ApiResult<List<JanusStrategyView>> strategies() {
        return ApiResult.ok(repository.strategies());
    }

    public ApiResult<JanusStrategyView> strategy(String strategyId) {
        return repository.findStrategy(strategyId).map(ApiResult::ok)
                .orElseGet(() -> ApiResult.fail(404, "JANUS_STRATEGY_NOT_FOUND"));
    }

    @Transactional
    public ApiResult<JanusStrategyView> createStrategy(String idempotencyKey, JanusStrategyUpsertRequest request) {
        if (!canManageStrategies()) return ApiResult.fail(403, "ROLE_FORBIDDEN");
        String error = validateStrategy(request);
        if (error != null) return ApiResult.fail(422, error);
        String strategyId = deterministicStrategyId(idempotencyKey);
        String requestHash = hash(request);
        CommandGuard guard = commandGuard(idempotencyKey, "STRATEGY_CREATE", strategyId, requestHash);
        if (guard.failure() != null) return cast(guard.failure());
        if (guard.duplicate()) return commandResult(idempotencyKey, JanusStrategyView.class).map(ApiResult::ok)
                .or(() -> repository.findStrategy(strategyId).map(ApiResult::ok))
                .orElseGet(() -> ApiResult.fail(409, "IDEMPOTENCY_RESULT_MISSING"));
        JanusStrategyView created = repository.createStrategy(strategyId, request);
        repository.completeCommand(idempotencyKey.trim(), "ACKED", json(created));
        requiredAudit("K6_STRATEGY_CREATED", "JANUS_STRATEGY", strategyId, request.reason(),
                Map.of("before", Map.of(), "after", created));
        return ApiResult.ok(created);
    }

    @Transactional
    public ApiResult<JanusStrategyView> updateStrategy(String strategyId, String idempotencyKey,
                                                       JanusStrategyUpsertRequest request) {
        if (!canManageStrategies()) return ApiResult.fail(403, "ROLE_FORBIDDEN");
        String error = validateStrategy(request);
        if (error != null) return ApiResult.fail(422, error);
        if (request.expectedVersion() == null) return ApiResult.fail(422, "EXPECTED_VERSION_REQUIRED");
        JanusStrategyView before = repository.findStrategy(strategyId).orElse(null);
        if (before == null) return ApiResult.fail(404, "JANUS_STRATEGY_NOT_FOUND");
        if (!"draft".equals(before.status())) return ApiResult.fail(409, "ONLY_DRAFT_EDITABLE");
        String requestHash = hash(request);
        CommandGuard guard = commandGuard(idempotencyKey, "STRATEGY_UPDATE", strategyId, requestHash);
        if (guard.failure() != null) return cast(guard.failure());
        if (guard.duplicate()) return ApiResult.ok(commandResult(idempotencyKey, JanusStrategyView.class).orElse(before));
        Optional<JanusStrategyView> updated = repository.updateStrategy(strategyId, request.expectedVersion(), request);
        if (updated.isEmpty()) {
            repository.releaseCommandReservation(idempotencyKey.trim());
            return ApiResult.fail(409, "VERSION_CONFLICT");
        }
        repository.completeCommand(idempotencyKey.trim(), "ACKED", json(updated.get()));
        requiredAudit("K6_STRATEGY_UPDATED", "JANUS_STRATEGY", strategyId, request.reason(),
                Map.of("before", before, "after", updated.get()));
        return ApiResult.ok(updated.get());
    }

    @Transactional
    public ApiResult<Map<String, Object>> dryRun(String strategyId, String idempotencyKey, JanusStrategyActionRequest request) {
        if (!canManageStrategies()) return ApiResult.fail(403, "ROLE_FORBIDDEN");
        JanusStrategyView strategy = repository.findStrategy(strategyId).orElse(null);
        if (strategy == null) return ApiResult.fail(404, "JANUS_STRATEGY_NOT_FOUND");
        String reason = request == null ? null : first(request.reason(), request.note());
        if (!StringUtils.hasText(reason) || reason.trim().length() < 4) return ApiResult.fail(422, "REASON_REQUIRED");
        if (request.expectedVersion() == null || request.expectedVersion() != strategy.lockVersion()) {
            return ApiResult.fail(409, "VERSION_CONFLICT");
        }
        String requestHash = hash(Map.of("strategyId", strategyId, "lockVersion", strategy.lockVersion(),
                "snapshot", snapshot(strategy), "reason", reason.trim()));
        CommandGuard guard = commandGuard(idempotencyKey, "STRATEGY_DRY_RUN", strategyId, requestHash);
        if (guard.failure() != null) return cast(guard.failure());
        if (guard.duplicate()) return ApiResult.ok(commandPayload(idempotencyKey));
        List<JanusDeviceView> devices = loadAllDevices();
        int evaluated = devices.size();
        int hit = 0;
        int conflicts = 0;
        long now = System.currentTimeMillis();
        int cap = dryRunCap(strategy);
        for (JanusDeviceView device : devices) {
            if (!isApplicable(strategy, device, now)) continue;
            if (!ruleEvaluator.evaluate(strategy.ruleTree(), device).passed()) continue;
            if (hit >= cap) continue;
            hit++;
            if (device.hitStrategy() != null && !device.hitStrategy().equals(strategyId)) conflicts++;
        }
        int recommend = actionType(strategy).equals("RECOMMEND") ? hit : 0;
        int filtered = actionType(strategy).equals("ENV_FILTER") ? hit : 0;
        int takeover = actionType(strategy).startsWith("REVERSAL_") ? hit : 0;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("evaluated", evaluated);
        result.put("hit", hit);
        result.put("recommend", recommend);
        result.put("filtered", filtered);
        result.put("takeover", takeover);
        result.put("other", Math.max(0, hit - recommend - filtered - takeover));
        result.put("conflicts", conflicts);
        result.put("hitRate", evaluated == 0 ? 0 : Math.round(hit * 10_000.0 / evaluated) / 100.0);
        String dryRunId = "DR-" + UUID.randomUUID().toString().replace("-", "");
        String configHash = hash(snapshot(strategy));
        repository.saveDryRun(dryRunId, strategyId, strategy.lockVersion(), configHash, json(result), currentActor(),
                System.currentTimeMillis() + 30 * 60_000L);
        Map<String, Object> response = new LinkedHashMap<>(result);
        response.put("dryRunId", dryRunId);
        response.put("configHash", configHash);
        response.put("expectedVersion", strategy.lockVersion());
        repository.completeCommand(idempotencyKey.trim(), "ACKED", json(response));
        return ApiResult.ok(response);
    }

    @Transactional
    public ApiResult<JanusStrategyView> lifecycle(String strategyId, String action, String idempotencyKey,
                                                  JanusStrategyActionRequest request) {
        String normalized = action == null ? "" : action.toLowerCase(Locale.ROOT);
        if (!List.of("publish", "pause", "archive").contains(normalized)) {
            return ApiResult.fail(422, "STRATEGY_ACTION_INVALID");
        }
        JanusRole role = currentRole();
        if (("publish".equals(normalized) && role != JanusRole.ADMIN)
                || (!"publish".equals(normalized)
                && role != JanusRole.SENIOR_OPERATOR && role != JanusRole.ADMIN)) {
            return ApiResult.fail(403, "ROLE_FORBIDDEN");
        }
        JanusStrategyView before = repository.findStrategy(strategyId).orElse(null);
        if (before == null) return ApiResult.fail(404, "JANUS_STRATEGY_NOT_FOUND");
        if (request == null) return ApiResult.fail(422, "STRATEGY_ACTION_REQUIRED");
        String reason = first(request.reason(), request.note());
        if (!StringUtils.hasText(reason) || reason.trim().length() < 4) return ApiResult.fail(422, "REASON_REQUIRED");
        if (request.expectedVersion() == null) return ApiResult.fail(422, "EXPECTED_VERSION_REQUIRED");
        String requestHash = hash(Map.of("action", normalized, "strategyId", strategyId, "request", request));
        CommandGuard guard = commandGuard(idempotencyKey, "STRATEGY_" + normalized.toUpperCase(), strategyId, requestHash);
        if (guard.failure() != null) return cast(guard.failure());
        if (guard.duplicate()) {
            return ApiResult.ok(commandResult(idempotencyKey, JanusStrategyView.class).orElse(before));
        }
        String targetStatus;
        int nextVersion = before.version();
        Long publishedAt = before.publishedAt();
        if ("publish".equals(normalized)) {
            if (!List.of("draft", "paused").contains(before.status())) {
                repository.releaseCommandReservation(idempotencyKey.trim());
                return ApiResult.fail(409, "ILLEGAL_STRATEGY_STATE");
            }
            ApiResult<Void> dryGuard = validateDryRun(before, request);
            if (dryGuard != null) {
                repository.releaseCommandReservation(idempotencyKey.trim());
                return cast(dryGuard);
            }
            targetStatus = "active";
            nextVersion++;
            publishedAt = System.currentTimeMillis();
        } else if ("pause".equals(normalized)) {
            if (!"active".equals(before.status())) {
                repository.releaseCommandReservation(idempotencyKey.trim());
                return ApiResult.fail(409, "ILLEGAL_STRATEGY_STATE");
            }
            targetStatus = "paused";
        } else if ("archive".equals(normalized)) {
            if ("active".equals(before.status())) {
                repository.releaseCommandReservation(idempotencyKey.trim());
                return ApiResult.fail(409, "ACTIVE_STRATEGY_CANNOT_ARCHIVE");
            }
            targetStatus = "archived";
        } else targetStatus = "archived";
        if (request.expectedVersion() == null || request.expectedVersion() != before.lockVersion()) {
            repository.releaseCommandReservation(idempotencyKey.trim());
            return ApiResult.fail(409, "VERSION_CONFLICT");
        }
        if (!repository.updateStrategyLifecycle(strategyId, before.lockVersion(), targetStatus, nextVersion, publishedAt)) {
            repository.releaseCommandReservation(idempotencyKey.trim());
            return ApiResult.fail(409, "VERSION_CONFLICT");
        }
        JanusStrategyView after = repository.findStrategy(strategyId).orElseThrow();
        if ("publish".equals(normalized)) {
            ObjectNode snapshot = snapshot(after);
            repository.addStrategyVersion(strategyId, nextVersion, reason.trim(), currentActor(), snapshot.toString(), hash(snapshot));
        }
        repository.completeCommand(idempotencyKey.trim(), "PUBLISHED", json(after));
        outboxService.publish("JANUS_STRATEGY", strategyId, "JANUS_STRATEGY_" + normalized.toUpperCase(), Map.of(
                "strategyId", strategyId, "version", nextVersion, "status", targetStatus));
        requiredAudit("K6_STRATEGY_" + normalized.toUpperCase(), "JANUS_STRATEGY", strategyId, reason,
                Map.of("before", before, "after", after, "idempotencyKey", idempotencyKey));
        return ApiResult.ok(after);
    }

    @Transactional
    public ApiResult<JanusStrategyView> rollback(String strategyId, String idempotencyKey,
                                                 JanusStrategyActionRequest request) {
        if (currentRole() != JanusRole.ADMIN) return ApiResult.fail(403, "ROLE_FORBIDDEN");
        JanusStrategyView before = repository.findStrategy(strategyId).orElse(null);
        if (before == null) return ApiResult.fail(404, "JANUS_STRATEGY_NOT_FOUND");
        if (request == null || request.targetVersion() == null || request.expectedVersion() == null) {
            return ApiResult.fail(422, "ROLLBACK_FIELDS_REQUIRED");
        }
        if (!StringUtils.hasText(request.reason()) || request.reason().trim().length() < 4) return ApiResult.fail(422, "REASON_REQUIRED");
        JanusStrategyVersionView target = repository.findStrategyVersion(strategyId, request.targetVersion()).orElse(null);
        if (target == null) return ApiResult.fail(404, "JANUS_STRATEGY_VERSION_NOT_FOUND");
        String requestHash = hash(request);
        CommandGuard guard = commandGuard(idempotencyKey, "STRATEGY_ROLLBACK", strategyId, requestHash);
        if (guard.failure() != null) return cast(guard.failure());
        if (guard.duplicate()) return ApiResult.ok(commandResult(idempotencyKey, JanusStrategyView.class).orElse(before));
        int nextVersion = before.version() + 1;
        if (!repository.replaceStrategyFromSnapshot(strategyId, request.expectedVersion(), nextVersion, "active",
                target.snapshot().toString())) {
            repository.releaseCommandReservation(idempotencyKey.trim());
            return ApiResult.fail(409, "VERSION_CONFLICT");
        }
        JanusStrategyView after = repository.findStrategy(strategyId).orElseThrow();
        ObjectNode snapshot = snapshot(after);
        snapshot.put("rolledBackFrom", request.targetVersion());
        repository.addStrategyVersion(strategyId, nextVersion, request.reason().trim(), currentActor(), snapshot.toString(), hash(snapshot));
        repository.completeCommand(idempotencyKey.trim(), "PUBLISHED", json(after));
        outboxService.publish("JANUS_STRATEGY", strategyId, "JANUS_STRATEGY_ROLLED_BACK", snapshot);
        requiredAudit("K6_STRATEGY_ROLLED_BACK", "JANUS_STRATEGY", strategyId, request.reason(),
                Map.of("before", before, "after", after, "targetVersion", request.targetVersion()));
        return ApiResult.ok(after);
    }

    @Transactional
    public ApiResult<Void> deleteStrategy(String strategyId, String idempotencyKey, JanusStrategyActionRequest request) {
        if (!canManageStrategies()) return ApiResult.fail(403, "ROLE_FORBIDDEN");
        String reason = request == null ? null : first(request.reason(), request.note());
        if (!StringUtils.hasText(reason) || reason.trim().length() < 4) return ApiResult.fail(422, "REASON_REQUIRED");
        if (request.expectedVersion() == null) return ApiResult.fail(422, "EXPECTED_VERSION_REQUIRED");
        String requestHash = hash(Map.of("strategyId", strategyId, "reason", reason,
                "expectedVersion", request.expectedVersion()));
        CommandGuard guard = commandGuard(idempotencyKey, "STRATEGY_DELETE", strategyId, requestHash);
        if (guard.failure() != null) return cast(guard.failure());
        if (guard.duplicate()) return ApiResult.ok();
        JanusStrategyView before = repository.findStrategy(strategyId).orElse(null);
        if (before == null) {
            repository.releaseCommandReservation(idempotencyKey.trim());
            return ApiResult.fail(404, "JANUS_STRATEGY_NOT_FOUND");
        }
        if (!"draft".equals(before.status())) {
            repository.releaseCommandReservation(idempotencyKey.trim());
            return ApiResult.fail(409, "ONLY_DRAFT_DELETABLE");
        }
        if (!repository.deleteStrategy(strategyId, request.expectedVersion())) {
            repository.releaseCommandReservation(idempotencyKey.trim());
            return ApiResult.fail(409, "STRATEGY_DELETE_FORBIDDEN");
        }
        repository.completeCommand(idempotencyKey.trim(), "ACKED", json(request));
        outboxService.publish("JANUS_STRATEGY", strategyId, "JANUS_STRATEGY_DRAFT_DELETED",
                Map.of("strategyId", strategyId, "expectedVersion", request.expectedVersion()));
        requiredAudit("K6_STRATEGY_DELETED", "JANUS_STRATEGY", strategyId, reason,
                Map.of("before", before, "after", Map.of()));
        return ApiResult.ok();
    }

    public ApiResult<Map<String, Object>> health() {
        List<JanusDeviceView> devices = loadAllDevices();
        long filtered = devices.stream().filter(d -> "ENV_FILTERED".equals(d.status())).count();
        long activated = devices.stream().filter(d -> "ACTIVATED".equals(d.status())).count();
        return ApiResult.ok(healthData(devices, filtered, activated));
    }

    public ApiResult<List<Map<String, Object>>> audit(String targetType, String q, Integer limit) {
        return ApiResult.ok(auditData(targetType, q, limit == null ? 100 : Math.max(1, Math.min(limit, 200))));
    }

    @Transactional
    public ApiResult<Map<String, Object>> export(String idempotencyKey, Map<String, Object> request) {
        String reportType = request == null ? null : String.valueOf(request.getOrDefault("reportType", ""));
        String format = request == null ? null : String.valueOf(request.getOrDefault("format", ""));
        if (!List.of("health", "audit", "funnel").contains(reportType) || !List.of("csv", "json").contains(format)) {
            return ApiResult.fail(422, "EXPORT_ARGUMENT_INVALID");
        }
        String requestHash = hash(request);
        CommandGuard guard = commandGuard(idempotencyKey, "EXPORT", reportType, requestHash);
        if (guard.failure() != null) return cast(guard.failure());
        Map<?, ?> filters = request.get("filters") instanceof Map<?, ?> value ? value : Map.of();
        String targetType = textFilter(filters.get("targetType"));
        String query = textFilter(filters.get("q"));
        Object payload = switch (reportType) {
            case "health" -> health().getData();
            case "funnel" -> {
                List<JanusDeviceView> devices = loadAllDevices();
                Map<String, Object> summary = new LinkedHashMap<>();
                summary.put("totalDevices", devices.size());
                summary.put("activeDevices", devices.stream().filter(d -> d.lastSeenAt() != null
                        && d.lastSeenAt() >= System.currentTimeMillis() - 15 * 60_000L).count());
                yield funnelData(devices, summary);
            }
            default -> auditData(targetType, query, 200);
        };
        Map<String, Object> response = Map.of("fileName", "janus-" + reportType + "." + format,
                "format", format, "data", payload);
        if (guard.duplicate()) return ApiResult.ok(commandResultMap(idempotencyKey).orElse(response));
        repository.completeCommand(idempotencyKey.trim(), "ACKED", json(response));
        requiredAudit("K6_" + reportType.toUpperCase() + "_EXPORTED", "JANUS_EXPORT", reportType,
                "导出 " + reportType + " " + format, Map.of("format", format, "filters", request.getOrDefault("filters", Map.of())));
        return ApiResult.ok(response);
    }

    private ApiResult<Void> validateDryRun(JanusStrategyView strategy, JanusStrategyActionRequest request) {
        if (!StringUtils.hasText(request.dryRunId()) || !StringUtils.hasText(request.configHash())) {
            return ApiResult.fail(422, "DRY_RUN_REQUIRED");
        }
        Map<String, Object> dry = repository.findDryRun(request.dryRunId()).orElse(null);
        if (dry == null) return ApiResult.fail(422, "DRY_RUN_STALE");
        if (!strategy.strategyId().equals(String.valueOf(dry.get("strategyId")))
                || !request.configHash().equals(String.valueOf(dry.get("configHash")))
                || !currentActor().equals(String.valueOf(dry.get("actorId")))
                || !(dry.get("expectedVersion") instanceof Number expectedVersion)
                || expectedVersion.longValue() != strategy.lockVersion()
                || !request.configHash().equals(hash(snapshot(strategy)))) return ApiResult.fail(422, "DRY_RUN_STALE");
        Object expiresAt = dry.get("expiresAt");
        if (expiresAt instanceof Number number && number.longValue() < System.currentTimeMillis()) {
            return ApiResult.fail(422, "DRY_RUN_STALE");
        }
        return null;
    }

    private List<JanusDeviceView> loadAllDevices() {
        List<JanusDeviceView> devices = new ArrayList<>();
        int pageNum = 1;
        long total;
        do {
            PageResult<JanusDeviceView> page = repository.pageDevices(
                    new JanusDeviceQueryRequest(null, null, null, null, null, pageNum, 200));
            devices.addAll(page.getRecords());
            total = page.getTotal();
            if (page.getRecords().isEmpty()) break;
            pageNum++;
        } while (devices.size() < total);
        return devices;
    }

    private String validateStrategy(JanusStrategyUpsertRequest request) {
        if (request == null) return "STRATEGY_REQUIRED";
        if (!validText(request.name(), 160) || request.name().trim().length() < 2) return "STRATEGY_NAME_REQUIRED";
        if (request.description() != null && request.description().length() > 1_000) return "STRATEGY_DESCRIPTION_INVALID";
        if (!validText(request.owner(), 96)) return "STRATEGY_OWNER_REQUIRED";
        if (request.priority() == null || request.priority() < 0 || request.priority() > 1000) return "STRATEGY_PRIORITY_INVALID";
        if (!validRuleTree(request.ruleTree())) return "INVALID_RULE_TREE";
        if (!validObject(request.action(), Set.of("type", "remoteUrlKey"))
                || !ACTIONS.contains(request.action().path("type").asText())) return "STRATEGY_ACTION_INVALID";
        String actionType = request.action().path("type").asText();
        boolean needsRemote = actionType.startsWith("REVERSAL_");
        if (needsRemote && !StringUtils.hasText(request.action().path("remoteUrlKey").asText())) return "REMOTE_TARGET_REQUIRED";
        String remoteUrlKey = request.action().path("remoteUrlKey").asText();
        if (StringUtils.hasText(remoteUrlKey)
                && (!remoteUrlKey.equals(remoteUrlKey.trim()) || !REMOTE_TARGETS.contains(remoteUrlKey))) {
            return "REMOTE_TARGET_INVALID";
        }
        if (!needsRemote && StringUtils.hasText(remoteUrlKey)) return "REMOTE_TARGET_NOT_ALLOWED";
        if (!validScope(request.scope())) return "STRATEGY_SCOPE_INVALID";
        if (!validSafeguards(request.safeguards())) return "STRATEGY_SAFEGUARD_INVALID";
        if (!validRollout(request.rollout())) return "STRATEGY_ROLLOUT_INVALID";
        if (!validHealthConfig(request.healthConfig())) return "STRATEGY_HEALTH_CONFIG_INVALID";
        if (request.templateKey() != null && !validText(request.templateKey(), 64)) return "TEMPLATE_KEY_INVALID";
        if (!validText(request.reason(), 500) || request.reason().trim().length() < 4) return "REASON_REQUIRED";
        return null;
    }

    private String validateReport(Long userId, JanusDeviceReportRequest request) {
        if (userId == null || userId <= 0) return "USER_AUTH_REQUIRED";
        if (request == null) return "JANUS_REPORT_REQUIRED";
        if (!validText(request.reportId(), 128)) return "REPORT_ID_REQUIRED";
        if (!validText(request.deviceId(), 128)) return "DEVICE_ID_REQUIRED";
        long now = System.currentTimeMillis();
        if (request.reportedAt() == null || request.reportedAt() <= 0 || request.reportedAt() > now + 5 * 60_000L) {
            return "REPORTED_AT_INVALID";
        }
        if (request.installAt() != null && (request.installAt() <= 0 || request.installAt() > request.reportedAt())) {
            return "INSTALL_AT_INVALID";
        }
        if (request.firstSeenAt() != null && (request.firstSeenAt() <= 0 || request.firstSeenAt() > request.reportedAt())) {
            return "FIRST_SEEN_AT_INVALID";
        }
        if (request.channel() != null && (!request.channel().equals(request.channel().trim())
                || !CHANNELS.contains(request.channel()))) return "CHANNEL_INVALID";
        if (normalizePlatform(request.platform()) == null) return "PLATFORM_INVALID";
        if (!jsonShape(request.maturity(), false) || !jsonShape(request.environment(), false)
                || !jsonShape(request.latestDecision(), false) || !jsonShape(request.latestSession(), false)
                || !jsonShape(request.tags(), true)) return "REPORT_JSON_INVALID";
        if (!validTelemetry(request.maturity(), request.environment())) return "TELEMETRY_INVALID";
        return null;
    }

    private JanusDeviceReportRequest normalizedReport(JanusDeviceReportRequest request) {
        long reportedAt = request.reportedAt();
        long installAt = request.installAt() == null ? reportedAt : request.installAt();
        long firstSeenAt = request.firstSeenAt() == null ? installAt : Math.min(request.firstSeenAt(), reportedAt);
        return new JanusDeviceReportRequest(request.reportId().trim(), request.deviceId().trim(), reportedAt,
                firstSeenAt, installAt, trim(request.inviteCode(), 96), trim(request.channel(), 64),
                trim(request.cohortId(), 96), null, false, trim(request.ua(), 1000),
                normalizePlatform(request.platform()), trim(request.model(), 128), trim(request.osName(), 128),
                trim(request.browser(), 128), 0, 0, 0, 0, object(request.maturity()),
                object(request.environment()), null, null, null, nullableObject(request.latestSession()),
                objectMapper.createArrayNode());
    }

    private ReportEvaluation evaluateReport(String sid, JanusDeviceReportRequest report, JanusDeviceView prior) {
        int installDays = Math.max(0, (int) Math.min(Integer.MAX_VALUE,
                (report.reportedAt() - report.installAt()) / 86_400_000L));
        int maturity = maturityScore(report, installDays);
        int environmentRisk = environmentRiskScore(report.environment());
        int recommendation = recommendationScore(report, installDays);
        String fallback = environmentRisk >= 80 ? "ENV_FILTERED"
                : recommendation >= 60 ? "RECOMMENDED" : "OBSERVING";
        JanusDeviceView candidate = reportDeviceView(sid, report, fallback, maturity, recommendation,
                environmentRisk, 0, null, null, prior);
        List<JanusStrategyView> active = repository.strategies().stream()
                .filter(strategy -> "active".equals(strategy.status()))
                .sorted(Comparator.comparingInt(JanusStrategyView::priority).reversed())
                .toList();
        for (JanusStrategyView strategy : active) {
            if (!isApplicable(strategy, candidate, System.currentTimeMillis())) continue;
            JanusRuleEvaluator.Result result = ruleEvaluator.evaluate(strategy.ruleTree(), candidate);
            if (!result.passed()) continue;
            String action = actionType(strategy);
            int dailyCap = dryRunCap(strategy);
            if (dailyCap <= 0) continue;
            boolean quotaReserved = dailyCap != Integer.MAX_VALUE;
            if (quotaReserved && !repository.reserveDailyEvaluation(strategy.strategyId(), action, dailyCap)) continue;
            String status = statusForAction(action, fallback);
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("passed", true);
            details.put("passedLeaves", result.passedLeaves());
            details.put("totalLeaves", result.totalLeaves());
            details.put("trace", result.trace());
            String remoteUrlKey = action.startsWith("REVERSAL_")
                    ? strategy.action().path("remoteUrlKey").asText(null) : null;
            return new ReportEvaluation(status, strategy.strategyId(), strategy.version(), action,
                    remoteUrlKey, details, quotaReserved);
        }
        return new ReportEvaluation(fallback, null, null, "BENIGN", null,
                Map.of("passed", false, "trace", List.of("NO_ACTIVE_STRATEGY_MATCH")), false);
    }

    private JanusDeviceReportRequest authoritativeReport(JanusDeviceReportRequest report, ReportEvaluation evaluation) {
        int installDays = Math.max(0, (int) Math.min(Integer.MAX_VALUE,
                (report.reportedAt() - report.installAt()) / 86_400_000L));
        int maturity = maturityScore(report, installDays);
        int environmentRisk = environmentRiskScore(report.environment());
        int recommendation = recommendationScore(report, installDays);
        int priority = priorityScore(evaluation.status(), recommendation, environmentRisk, report);
        ObjectNode decision = objectMapper.createObjectNode();
        decision.put("reportId", report.reportId());
        decision.put("decidedAt", System.currentTimeMillis());
        decision.put("status", evaluation.status());
        decision.put("action", evaluation.action());
        if (evaluation.strategyId() != null) decision.put("strategyId", evaluation.strategyId());
        if (evaluation.strategyVersion() != null) decision.put("strategyVersion", evaluation.strategyVersion());
        decision.set("ruleResults", objectMapper.valueToTree(evaluation.ruleResults()));
        return new JanusDeviceReportRequest(report.reportId(), report.deviceId(), report.reportedAt(),
                report.firstSeenAt(), report.installAt(), report.inviteCode(), report.channel(), report.cohortId(),
                evaluation.status(), false, report.ua(), report.platform(), report.model(), report.osName(),
                report.browser(), maturity, recommendation, environmentRisk, priority, report.maturity(),
                report.environment(), evaluation.strategyId(), evaluation.strategyVersion(), decision,
                report.latestSession(), objectMapper.createArrayNode());
    }

    private JanusDeviceView reportDeviceView(String sid, JanusDeviceReportRequest report, String status,
                                              int maturityScore, int recommendationScore, int environmentRiskScore,
                                              int priorityScore, String strategyId, Integer strategyVersion,
                                              JanusDeviceView prior) {
        int installDays = Math.max(0, (int) Math.min(Integer.MAX_VALUE,
                (report.reportedAt() - report.installAt()) / 86_400_000L));
        return new JanusDeviceView(sid, report.deviceId(), report.firstSeenAt(), report.reportedAt(), report.installAt(),
                installDays, report.inviteCode(), report.channel(), report.cohortId(),
                prior == null ? status : prior.status(), null, null, "system",
                prior != null && prior.activated(), null, maturityScore, recommendationScore, environmentRiskScore, priorityScore, report.ua(),
                report.platform(), report.model(), report.osName(), report.browser(), report.maturity(),
                report.environment(), strategyId, strategyVersion, prior == null ? null : prior.latestDecision(),
                report.latestSession(), prior == null ? objectMapper.createObjectNode() : prior.manualOverride(),
                prior == null ? null : prior.lastOperatorId(), prior == null ? null : prior.lastOperationReason(),
                prior == null ? null : prior.activationKind(), objectMapper.createArrayNode(), prior == null ? 0L : prior.version());
    }

    private int maturityScore(JanusDeviceReportRequest report, int installDays) {
        JsonNode m = report.maturity();
        int score = Math.min(20, metric(m, "appOpenCount") * 4)
                + Math.min(15, metric(m, "repeatStreakDays") * 5)
                + (flag(m, "benchmarkViewed") ? 15 : 0)
                + (flag(m, "optimizeDone") ? 15 : 0)
                + Math.min(10, metric(m, "foregroundDurationSeconds") / 60 * 2)
                + (StringUtils.hasText(report.inviteCode()) ? 10 : 0)
                + Math.min(15, installDays * 2);
        return Math.min(100, score);
    }

    private int recommendationScore(JanusDeviceReportRequest report, int installDays) {
        JsonNode m = report.maturity();
        int score = Math.min(20, metric(m, "appOpenCount") * 4)
                + Math.min(10, installDays * 3)
                + Math.min(15, metric(m, "repeatStreakDays") * 5)
                + (flag(m, "benchmarkViewed") ? 5 : 0)
                + (flag(m, "marketViewed") ? 5 : 0)
                + (flag(m, "walletViewed") ? 5 : 0)
                + (flag(m, "optimizeDone") ? 5 : 0)
                + Math.min(10, metric(m, "foregroundDurationSeconds") / 60 * 2)
                + (StringUtils.hasText(report.inviteCode()) || "invite".equals(report.channel())
                || "official".equals(report.channel()) ? 10 : 0)
                + (System.currentTimeMillis() - report.reportedAt() <= 5 * 60_000L ? 5 : 0);
        return Math.min(100, score);
    }

    private int environmentRiskScore(JsonNode environment) {
        int score = (flag(environment, "isHeadless") ? 45 : 0)
                + Math.min(30, metric(environment, "automationSignalCount") * 10)
                + (flag(environment, "fpBlocklistHit") ? 30 : 0)
                + (flag(environment, "screenAnomaly") ? 15 : 0)
                + (flag(environment, "timezoneMismatch") ? 10 : 0)
                + (flag(environment, "languageMismatch") ? 10 : 0);
        return Math.min(100, score);
    }

    private int priorityScore(String status, int recommendation, int environmentRisk,
                              JanusDeviceReportRequest report) {
        int score = switch (status) {
            case "RECOMMENDED" -> 80;
            case "HIT" -> 70;
            case "ENV_FILTERED" -> 45;
            case "MANUAL_HOLD" -> 35;
            case "STALE" -> -30;
            case "BLOCKED" -> -80;
            default -> 0;
        };
        score += recommendation;
        if (System.currentTimeMillis() - report.reportedAt() <= 5 * 60_000L) score += 20;
        if (StringUtils.hasText(report.inviteCode()) || "invite".equals(report.channel())
                || "official".equals(report.channel())) score += 20;
        return score - Math.min(80, Math.round(environmentRisk * 0.8f));
    }

    private String statusForAction(String action, String fallback) {
        return switch (action) {
            case "RECOMMEND" -> "RECOMMENDED";
            case "REVERSAL_IMMEDIATE", "REVERSAL_SESSION_EDGE" -> "HIT";
            case "ENV_FILTER" -> "ENV_FILTERED";
            case "MANUAL_HOLD" -> "MANUAL_HOLD";
            case "BLOCK" -> "BLOCKED";
            default -> fallback;
        };
    }

    private boolean validTelemetry(JsonNode maturity, JsonNode environment) {
        Set<String> maturityFields = Set.of("appOpenCount", "sessionCount", "foregroundDurationSeconds",
                "repeatStreakDays", "benchmarkViewed", "optimizeDone", "marketViewed", "walletViewed");
        Set<String> environmentFields = Set.of("isHeadless", "automationSignalCount", "fpBlocklistHit",
                "screenAnomaly", "timezoneMismatch", "languageMismatch");
        return validRequiredObject(maturity, maturityFields) && validRequiredObject(environment, environmentFields)
                && validMetrics(maturity, Map.of(
                "appOpenCount", 1_000_000, "sessionCount", 1_000_000,
                "foregroundDurationSeconds", 31_536_000, "repeatStreakDays", 3_650))
                && validFlags(maturity, List.of("benchmarkViewed", "optimizeDone", "marketViewed", "walletViewed"))
                && validMetrics(environment, Map.of("automationSignalCount", 1_000_000))
                && validFlags(environment, List.of("isHeadless", "fpBlocklistHit", "screenAnomaly",
                "timezoneMismatch", "languageMismatch"));
    }

    private boolean validRequiredObject(JsonNode node, Set<String> requiredFields) {
        if (node == null || !node.isObject() || node.size() != requiredFields.size()) return false;
        for (String field : requiredFields) if (!node.has(field) || node.get(field).isNull()) return false;
        return true;
    }

    private boolean validMetrics(JsonNode node, Map<String, Integer> limits) {
        if (node == null || node.isNull()) return true;
        for (Map.Entry<String, Integer> entry : limits.entrySet()) {
            JsonNode value = node.get(entry.getKey());
            if (value != null && (!value.isIntegralNumber() || value.asLong() < 0 || value.asLong() > entry.getValue())) {
                return false;
            }
        }
        return true;
    }

    private boolean validFlags(JsonNode node, List<String> fields) {
        if (node == null || node.isNull()) return true;
        for (String field : fields) {
            JsonNode value = node.get(field);
            if (value != null && !value.isBoolean()) return false;
        }
        return true;
    }

    private int metric(JsonNode node, String field) {
        return node == null ? 0 : Math.max(0, node.path(field).asInt(0));
    }

    private boolean flag(JsonNode node, String field) {
        return node != null && node.path(field).asBoolean(false);
    }

    private String sessionId(JanusDeviceReportRequest report) {
        String value = report.latestSession() == null ? null : report.latestSession().path("sessionId").asText(null);
        return trim(value, 128);
    }

    private Map<String, Object> reportInput(JanusDeviceReportRequest report) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("reportedAt", report.reportedAt());
        input.put("installAt", report.installAt());
        input.put("channel", report.channel());
        input.put("cohortId", report.cohortId());
        input.put("maturity", report.maturity());
        input.put("environment", report.environment());
        input.put("maturityScore", report.maturityScore());
        input.put("recommendationScore", report.recommendationScore());
        input.put("environmentRiskScore", report.environmentRiskScore());
        return input;
    }

    private String deviceSid(long userId, String deviceId) {
        return "J-" + hash(userId + ":" + deviceId.trim()).substring(0, 32).toUpperCase(Locale.ROOT);
    }

    private String normalizePlatform(String platform) {
        if (!StringUtils.hasText(platform)) return "unknown";
        return switch (platform.trim().toLowerCase(Locale.ROOT)) {
            case "ios" -> "iOS";
            case "android" -> "Android";
            case "windows", "win32" -> "windows";
            case "mac", "macos" -> "mac";
            case "linux" -> "linux";
            case "unknown", "devtools" -> "unknown";
            default -> null;
        };
    }

    private boolean validText(String value, int max) {
        return StringUtils.hasText(value) && value.trim().length() <= max;
    }

    private boolean score(Integer value) {
        return value == null || value >= 0 && value <= 100;
    }

    private boolean priorityScore(Integer value) {
        return value == null || value >= -1000 && value <= 1000;
    }

    private boolean jsonShape(JsonNode value, boolean array) {
        if (value == null || value.isNull()) return true;
        if (array ? !value.isArray() : !value.isObject()) return false;
        return value.toString().getBytes(StandardCharsets.UTF_8).length <= 64 * 1024;
    }

    private ObjectNode object(JsonNode value) {
        return value != null && value.isObject() ? (ObjectNode) value : objectMapper.createObjectNode();
    }

    private JsonNode nullableObject(JsonNode value) {
        return value != null && value.isObject() ? value : null;
    }

    private ArrayNode array(JsonNode value) {
        return value != null && value.isArray() ? (ArrayNode) value : objectMapper.createArrayNode();
    }

    private int value(Integer value) {
        return value == null ? 0 : value;
    }

    private String trim(String value, int max) {
        if (!StringUtils.hasText(value)) return null;
        String normalized = value.trim();
        return normalized.length() <= max ? normalized : normalized.substring(0, max);
    }

    private boolean validRuleTree(JsonNode root) {
        int[] leaves = {0};
        return validRuleNode(root, 0, leaves) && leaves[0] > 0 && leaves[0] <= 100;
    }

    private boolean validRuleNode(JsonNode node, int depth, int[] leaves) {
        if (node == null || !node.isObject() || depth > 8 || !jsonShape(node, false)) return false;
        if (node.has("rules")) {
            if (!validObject(node, Set.of("mode", "required", "threshold", "rules", "weight"))) return false;
            String mode = node.path("mode").asText();
            JsonNode rules = node.get("rules");
            if (!RULE_MODES.contains(mode) || rules == null || !rules.isArray()
                    || rules.isEmpty() || rules.size() > 100) return false;
            if ("NOT".equals(mode) && rules.size() != 1) return false;
            if ("N_OF_M".equals(mode)) {
                JsonNode required = node.get("required");
                if (required == null || !required.isIntegralNumber()
                        || required.asInt() < 1 || required.asInt() > rules.size()) return false;
            } else if (node.has("required")) {
                return false;
            }
            if ("WEIGHTED_SCORE".equals(mode)) {
                JsonNode threshold = node.get("threshold");
                if (!positiveFiniteNumber(threshold, 1_000_000)) return false;
            } else if (node.has("threshold")) {
                return false;
            }
            for (JsonNode child : rules) {
                if ("WEIGHTED_SCORE".equals(mode)
                        && !positiveFiniteNumber(child == null ? null : child.get("weight"), 1_000_000)) return false;
                if (!validRuleNode(child, depth + 1, leaves)) return false;
            }
            return true;
        }
        if (!validObject(node, Set.of("field", "op", "value", "weight", "label"))) return false;
        String field = node.path("field").asText();
        String op = node.path("op").asText();
        if (!RULE_FIELDS.contains(field) || !RULE_OPERATORS.contains(op)) return false;
        if (node.has("weight") && !positiveFiniteNumber(node.get("weight"), 1_000_000)) return false;
        if (!validText(node.path("label").asText(null), 160)) return false;
        if (!validRuleValue(field, op, node.get("value"))) return false;
        leaves[0]++;
        return leaves[0] <= 100;
    }

    private boolean validRuleValue(String field, String op, JsonNode value) {
        if (value == null || value.isNull()) return false;
        if (NUMERIC_RULE_FIELDS.contains(field)) {
            if ("contains".equals(op)) return false;
            if ("between".equals(op)) {
                return value.isArray() && value.size() == 2 && finiteNumber(value.get(0), 1_000_000_000_000D)
                        && finiteNumber(value.get(1), 1_000_000_000_000D)
                        && value.get(0).asDouble() <= value.get(1).asDouble();
            }
            if ("in".equals(op) || "notIn".equals(op)) return validNumberArray(value);
            return finiteNumber(value, 1_000_000_000_000D);
        }
        if (BOOLEAN_RULE_FIELDS.contains(field)) {
            if ("in".equals(op) || "notIn".equals(op)) return validBooleanArray(value);
            return ("=".equals(op) || "!=".equals(op)) && value.isBoolean();
        }
        if (STRING_RULE_FIELDS.contains(field)) {
            if (!(Set.of("=", "!=", "in", "notIn", "contains").contains(op))) return false;
            if ("status".equals(field)) return validEnumRuleValue(op, value, Set.copyOf(STATUSES));
            if ("channel".equals(field)) return validEnumRuleValue(op, value, CHANNELS);
            if ("in".equals(op) || "notIn".equals(op)) return validStringArray(value);
            return value.isTextual() && validText(value.asText(), 256);
        }
        return false;
    }

    private boolean validScope(JsonNode scope) {
        if (!validOptionalObject(scope, SCOPE_FIELDS)) return false;
        if (scope == null || scope.isNull()) return true;
        return validOptionalEnumArray(scope.get("channels"), CHANNELS, 64)
                && validOptionalStringArray(scope.get("inviteCodes"), 100, 96)
                && validOptionalStringArray(scope.get("cohortIds"), 100, 96);
    }

    private boolean validSafeguards(JsonNode safeguards) {
        if (!validOptionalObject(safeguards, SAFEGUARD_FIELDS)) return false;
        if (safeguards == null || safeguards.isNull()) return true;
        return validOptionalNonNegativeInteger(safeguards.get("maxDailyRecommendations"), 100_000_000)
                && validOptionalNonNegativeInteger(safeguards.get("maxDailyHits"), 100_000_000)
                && validOptionalNonNegativeInteger(safeguards.get("requireFreshReportMinutes"), 525_600)
                && validOptionalNonNegativeInteger(safeguards.get("minDecisionIntervalHours"), 87_600);
    }

    private boolean validRollout(JsonNode rollout) {
        if (!validOptionalObject(rollout, ROLLOUT_FIELDS)) return false;
        if (rollout == null || rollout.isNull()) return true;
        JsonNode percent = rollout.get("percent");
        if (percent != null && (!percent.isIntegralNumber() || percent.asInt() < 0 || percent.asInt() > 100)) return false;
        if (!validOptionalStringArray(rollout.get("cohortIds"), 100, 96)) return false;
        JsonNode startedAt = rollout.get("startedAt");
        JsonNode endsAt = rollout.get("endsAt");
        if (!validOptionalPositiveLong(startedAt) || !validOptionalPositiveLong(endsAt)) return false;
        return startedAt == null || endsAt == null || startedAt.asLong() < endsAt.asLong();
    }

    private boolean validHealthConfig(JsonNode health) {
        if (!validOptionalObject(health, HEALTH_FIELDS)) return false;
        if (health == null || health.isNull()) return true;
        for (String field : HEALTH_FIELDS) {
            JsonNode value = health.get(field);
            if (value != null && (!finiteNumber(value, 100) || value.asDouble() < 0)) return false;
        }
        return true;
    }

    private boolean validObject(JsonNode node, Set<String> allowedFields) {
        if (node == null || !node.isObject() || !jsonShape(node, false)) return false;
        var fields = node.fieldNames();
        while (fields.hasNext()) if (!allowedFields.contains(fields.next())) return false;
        return true;
    }

    private boolean validOptionalObject(JsonNode node, Set<String> allowedFields) {
        return node == null || node.isNull() || validObject(node, allowedFields);
    }

    private boolean validOptionalNonNegativeInteger(JsonNode value, long max) {
        return value == null || value.isNull()
                || value.isIntegralNumber() && value.asLong() >= 0 && value.asLong() <= max;
    }

    private boolean validOptionalPositiveLong(JsonNode value) {
        return value == null || value.isNull() || value.isIntegralNumber() && value.asLong() > 0;
    }

    private boolean validOptionalStringArray(JsonNode value, int maxItems, int maxLength) {
        if (value == null || value.isNull()) return true;
        if (!value.isArray() || value.size() > maxItems) return false;
        for (JsonNode item : value) if (!item.isTextual() || !validText(item.asText(), maxLength)) return false;
        return true;
    }

    private boolean validStringArray(JsonNode value) {
        return validStringArray(value, 100, 256);
    }

    private boolean validStringArray(JsonNode value, int maxItems, int maxLength) {
        if (value == null || !value.isArray() || value.isEmpty() || value.size() > maxItems) return false;
        for (JsonNode item : value) if (!item.isTextual() || !validText(item.asText(), maxLength)) return false;
        return true;
    }

    private boolean validOptionalEnumArray(JsonNode value, Set<String> allowed, int maxItems) {
        if (value == null || value.isNull()) return true;
        if (!value.isArray() || value.size() > maxItems) return false;
        for (JsonNode item : value) if (!item.isTextual() || !allowed.contains(item.asText())) return false;
        return true;
    }

    private boolean validEnumRuleValue(String op, JsonNode value, Set<String> allowed) {
        if (!(Set.of("=", "!=", "in", "notIn").contains(op))) return false;
        if ("in".equals(op) || "notIn".equals(op)) {
            if (value == null || !value.isArray() || value.isEmpty() || value.size() > allowed.size()) return false;
            for (JsonNode item : value) if (!item.isTextual() || !allowed.contains(item.asText())) return false;
            return true;
        }
        return value != null && value.isTextual() && allowed.contains(value.asText());
    }

    private boolean validNumberArray(JsonNode value) {
        if (value == null || !value.isArray() || value.isEmpty() || value.size() > 100) return false;
        for (JsonNode item : value) if (!finiteNumber(item, 1_000_000_000_000D)) return false;
        return true;
    }

    private boolean validBooleanArray(JsonNode value) {
        if (value == null || !value.isArray() || value.isEmpty() || value.size() > 2) return false;
        for (JsonNode item : value) if (!item.isBoolean()) return false;
        return true;
    }

    private boolean positiveFiniteNumber(JsonNode value, double max) {
        return finiteNumber(value, max) && value.asDouble() > 0;
    }

    private boolean finiteNumber(JsonNode value, double maxAbs) {
        return value != null && value.isNumber() && Double.isFinite(value.asDouble())
                && Math.abs(value.asDouble()) <= maxAbs;
    }

    private boolean inScope(JsonNode scope, JanusDeviceView device) {
        if (scope == null || scope.isMissingNode() || scope.isNull()) return true;
        return matches(scope.path("channels"), device.channel())
                && matches(scope.path("inviteCodes"), device.inviteCode())
                && matches(scope.path("cohortIds"), device.cohortId());
    }

    private boolean isApplicable(JanusStrategyView strategy, JanusDeviceView device, long now) {
        if (!inScope(strategy.scope(), device)) return false;
        JsonNode rollout = strategy.rollout();
        if (rollout != null && !rollout.isMissingNode() && !rollout.isNull()) {
            long startedAt = rollout.path("startedAt").asLong(0);
            long endsAt = rollout.path("endsAt").asLong(0);
            if (startedAt > 0 && now < startedAt) return false;
            if (endsAt > 0 && now > endsAt) return false;
            if (!matches(rollout.path("cohortIds"), device.cohortId())) return false;
            int percent = Math.max(0, Math.min(100, rollout.path("percent").asInt(100)));
            if (percent == 0 || (percent < 100 && rolloutBucket(device.sid()) >= percent)) return false;
        }
        JsonNode safeguards = strategy.safeguards();
        if (safeguards == null || safeguards.isMissingNode() || safeguards.isNull()) return true;
        int freshMinutes = safeguards.path("requireFreshReportMinutes").asInt(0);
        if (freshMinutes > 0 && (device.lastSeenAt() == null
                || now - device.lastSeenAt() > freshMinutes * 60_000L)) return false;
        int intervalHours = safeguards.path("minDecisionIntervalHours").asInt(0);
        if (intervalHours > 0) {
            long lastDecisionAt = decisionAt(device);
            if (lastDecisionAt > 0 && now - lastDecisionAt < intervalHours * 3_600_000L) return false;
        }
        return true;
    }

    private int dryRunCap(JanusStrategyView strategy) {
        String action = actionType(strategy);
        JsonNode safeguards = strategy.safeguards();
        if (safeguards == null || safeguards.isMissingNode() || safeguards.isNull()) return Integer.MAX_VALUE;
        if ("RECOMMEND".equals(action)) {
            return positiveCap(safeguards.path("maxDailyRecommendations"));
        }
        if (action.startsWith("REVERSAL_")) {
            return positiveCap(safeguards.path("maxDailyHits"));
        }
        return Integer.MAX_VALUE;
    }

    private int positiveCap(JsonNode value) {
        if (value == null || !value.isNumber()) return Integer.MAX_VALUE;
        return Math.max(0, value.asInt());
    }

    private int rolloutBucket(String sid) {
        long value = 0;
        String key = sid == null ? "" : sid;
        for (int i = 0; i < key.length(); i++) value = (value * 31 + key.charAt(i)) & 0xffff_ffffL;
        return (int) (value % 100);
    }

    private long decisionAt(JanusDeviceView device) {
        long direct = device.latestDecision() == null ? 0 : device.latestDecision().path("decidedAt").asLong(0);
        if (direct > 0) return direct;
        JsonNode session = device.latestSession();
        if (session != null) {
            long nested = session.path("lastDecision").path("decidedAt").asLong(0);
            if (nested > 0) return nested;
        }
        return device.manualOverride() == null ? 0 : device.manualOverride().path("createdAt").asLong(0);
    }

    private boolean matches(JsonNode values, String actual) {
        if (!values.isArray() || values.isEmpty()) return true;
        if (actual == null) return false;
        for (JsonNode value : values) if (actual.equals(value.asText())) return true;
        return false;
    }

    private String actionType(JanusStrategyView strategy) {
        return strategy.action().path("type").asText("BENIGN");
    }

    private Map<String, Object> healthData(List<JanusDeviceView> devices, long filtered, long activated) {
        long total = devices.size();
        long stale = devices.stream().filter(d -> "STALE".equals(d.status())).count();
        long errors = devices.stream().filter(d -> "ERROR".equals(d.status())).count();
        double filterRate = total == 0 ? 0 : filtered * 100.0 / total;
        double activationRate = total == 0 ? 0 : activated * 100.0 / total;
        String level = errors > 0 || filterRate > 30 ? "RISK" : stale > Math.max(2, total / 10) ? "WARNING" : "HEALTHY";
        return Map.of(
                "level", level,
                "indicators", List.of(
                        Map.of("key", "deviceCount", "label", "设备数", "value", total, "level", "HEALTHY", "note", "来自 nx_janus_device"),
                        Map.of("key", "filterRate", "label", "环境过滤率", "value", Math.round(filterRate * 100) / 100.0, "level", filterRate > 30 ? "RISK" : "HEALTHY", "note", "%"),
                        Map.of("key", "activationRate", "label", "激活率", "value", Math.round(activationRate * 100) / 100.0, "level", "HEALTHY", "note", "%"),
                        Map.of("key", "stale", "label", "长时间未上报", "value", stale, "level", stale > 0 ? "WARNING" : "HEALTHY", "note", "台"),
                        Map.of("key", "errors", "label", "异常设备", "value", errors, "level", errors > 0 ? "RISK" : "HEALTHY", "note", "台")),
                "reasons", errors > 0 ? List.of("存在状态异常设备") : List.of(),
                "suggestions", errors > 0 ? List.of("优先排查异常设备最近上报和命令确认") : List.of("继续观察策略命中与激活趋势"));
    }

    private List<Map<String, Object>> funnelData(List<JanusDeviceView> devices, Map<String, Object> summary) {
        long total = ((Number) summary.getOrDefault("totalDevices", devices.size())).longValue();
        long active = ((Number) summary.getOrDefault("activeDevices", 0)).longValue();
        long envPass = devices.stream().filter(device -> !"ENV_FILTERED".equals(device.status())).count();
        long mature = devices.stream().filter(device -> device.recommendationScore() != null
                && device.recommendationScore() >= 60).count();
        long recommended = devices.stream().filter(device -> "RECOMMENDED".equals(device.status())).count();
        long hit = devices.stream().filter(device -> "HIT".equals(device.status())).count();
        long activated = devices.stream().filter(device -> "ACTIVATED".equals(device.status())).count();
        return List.of(funnelRow("总设备", total, total), funnelRow("在线活跃", active, total),
                funnelRow("环境通过", envPass, total), funnelRow("成熟达标", mature, total),
                funnelRow("建议下发", recommended, total), funnelRow("已命中", hit, total),
                funnelRow("已激活", activated, total));
    }

    private Map<String, Object> funnelRow(String label, long count, long total) {
        double rate = total == 0 ? 0 : Math.round(count * 10_000.0 / total) / 100.0;
        return Map.of("label", label, "count", count, "rate", rate);
    }

    private List<Map<String, Object>> auditData(String targetType, String q, int limit) {
        List<String> types = StringUtils.hasText(targetType)
                ? List.of("strategy".equalsIgnoreCase(targetType) ? "JANUS_STRATEGY" : "JANUS_DEVICE")
                : List.of("JANUS_DEVICE", "JANUS_STRATEGY", "JANUS_EXPORT");
        List<AuditLogRecord> records = new ArrayList<>();
        for (String type : types) {
            AuditLogQueryRequest query = new AuditLogQueryRequest();
            query.setResourceType(type);
            query.setLimit(limit);
            records.addAll(auditLogService.list(query));
        }
        return records.stream().sorted(Comparator.comparing(AuditLogRecord::getCreatedAt,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .filter(record -> !StringUtils.hasText(q) || contains(record, q))
                .limit(limit)
                .map(this::auditView).toList();
    }

    private boolean contains(AuditLogRecord record, String q) {
        String needle = q.toLowerCase(Locale.ROOT);
        return List.of(record.getAction(), record.getResourceId(), record.getActorUsername(), record.getDetailJson())
                .stream().filter(StringUtils::hasText).anyMatch(value -> value.toLowerCase(Locale.ROOT).contains(needle));
    }

    private Map<String, Object> auditView(AuditLogRecord record) {
        JsonNode detail = parse(record.getDetailJson());
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("auditId", String.valueOf(record.getId()));
        String actor = StringUtils.hasText(record.getActorUsername())
                ? record.getActorUsername()
                : record.getActorId() == null ? "system" : String.valueOf(record.getActorId());
        view.put("actorId", actor);
        view.put("action", record.getAction());
        view.put("targetType", "JANUS_STRATEGY".equals(record.getResourceType()) ? "strategy" : "JANUS_DEVICE".equals(record.getResourceType()) ? "device" : "config");
        view.put("targetId", record.getResourceId());
        view.put("beforeSnapshot", detail.path("before"));
        view.put("afterSnapshot", detail.path("after"));
        view.put("reasonCategory", detail.path("reasonCategory").asText(""));
        view.put("reasonText", detail.path("reason").asText(""));
        view.put("sourceContext", StringUtils.hasText(record.getTraceId())
                ? Map.of("requestId", record.getTraceId()) : Map.of());
        view.put("createdAt", epoch(record.getCreatedAt()));
        view.put("requestId", record.getTraceId());
        return view;
    }

    private CommandGuard commandGuard(String key, String type, String target, String requestHash) {
        if (!StringUtils.hasText(key)) return new CommandGuard(false, ApiResult.fail(422, OpsErrorCode.IDEMPOTENCY_KEY_REQUIRED.name()));
        String normalized = key.trim();
        if (normalized.length() > 128) return new CommandGuard(false, ApiResult.fail(422, "IDEMPOTENCY_KEY_INVALID"));
        String actor = currentActor();
        if (repository.reserveCommand(normalized, type, target, requestHash, actor)) {
            return new CommandGuard(false, null);
        }
        Optional<Map<String, Object>> existing = repository.findCommand(normalized);
        if (existing.isEmpty()) return new CommandGuard(false, ApiResult.fail(409, "IDEMPOTENCY_RESERVATION_FAILED"));
        Map<String, Object> command = existing.get();
        boolean same = type.equals(String.valueOf(command.get("commandType")))
                && target.equals(String.valueOf(command.get("targetId")))
                && requestHash.equals(String.valueOf(command.get("requestHash")))
                && actor.equals(String.valueOf(command.get("actorId")));
        if (!same) return new CommandGuard(false, ApiResult.fail(409, "IDEMPOTENCY_CONFLICT"));
        if ("PROCESSING".equals(String.valueOf(command.get("state")))) {
            return new CommandGuard(false, ApiResult.fail(409, "IDEMPOTENCY_IN_PROGRESS"));
        }
        return new CommandGuard(true, null);
    }

    private void requiredAudit(String action, String resourceType, String resourceId, String reason, Object detail) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("reason", compactText(reason, 500));
        payload.put("before", Map.of());
        payload.put("after", Map.of());
        if (detail instanceof Map<?, ?> detailMap) {
            detailMap.forEach((key, value) -> {
                String name = String.valueOf(key);
                if (!forbiddenAuditKey(name)) payload.put(name, compactAuditValue(value, 0));
            });
        } else {
            payload.put("detail", compactAuditValue(detail, 0));
        }
        Map<String, Object> safePayload = fitAuditBudget(payload);
        auditLogService.recordRequired(AuditLogWriteRequest.builder()
                .action(action).resourceType(resourceType).resourceId(resourceId)
                .actorType("ADMIN").actorUsername(currentActor()).method("ADMIN_COMMAND")
                .path("/api/admin/janus").result("SUCCESS").riskLevel("HIGH")
                .detail(safePayload).build());
    }

    private Map<String, Object> fitAuditBudget(Map<String, Object> payload) {
        if (serializedLength(payload) <= 3_800) return payload;
        Map<String, Object> reduced = new LinkedHashMap<>();
        reduced.put("reason", payload.getOrDefault("reason", ""));
        reduced.put("before", reducedAuditSnapshot(payload.get("before"), false));
        reduced.put("after", reducedAuditSnapshot(payload.get("after"), false));
        copyAuditScalar(payload, reduced, "reasonCategory", 120);
        copyAuditScalar(payload, reduced, "targetVersion", 40);
        copyAuditScalar(payload, reduced, "idempotencyKey", 128);
        if (serializedLength(reduced) <= 3_800) return reduced;
        reduced.put("before", reducedAuditSnapshot(payload.get("before"), true));
        reduced.put("after", reducedAuditSnapshot(payload.get("after"), true));
        reduced.remove("idempotencyKey");
        if (serializedLength(reduced) <= 3_800) return reduced;
        Map<String, Object> emergency = new LinkedHashMap<>();
        emergency.put("reason", budgetText(String.valueOf(payload.getOrDefault("reason", "")), 1_800));
        emergency.put("before", identityAuditSnapshot(payload.get("before")));
        emergency.put("after", identityAuditSnapshot(payload.get("after")));
        Object reasonCategory = payload.get("reasonCategory");
        if (reasonCategory != null) emergency.put("reasonCategory", budgetText(String.valueOf(reasonCategory), 240));
        if (serializedLength(emergency) <= 3_800) return emergency;
        emergency.put("reason", budgetText(String.valueOf(payload.getOrDefault("reason", "")), 900));
        emergency.remove("reasonCategory");
        return emergency;
    }

    private Map<String, Object> identityAuditSnapshot(Object snapshot) {
        if (!(snapshot instanceof Map<?, ?> source) || source.isEmpty()) return Map.of();
        Map<String, Object> identity = new LinkedHashMap<>();
        for (String key : List.of("strategyId", "sid", "deviceId", "name", "status", "version")) {
            Object value = source.get(key);
            if (value == null) continue;
            identity.put(key, value instanceof Number || value instanceof Boolean
                    ? value : budgetText(String.valueOf(value), 96));
        }
        return identity;
    }

    private String budgetText(String value, int maxSerializedLength) {
        if (value == null || value.isEmpty()) return "";
        StringBuilder result = new StringBuilder();
        var codePoints = value.codePoints().iterator();
        while (codePoints.hasNext()) {
            int codePoint = codePoints.nextInt();
            int previousLength = result.length();
            result.appendCodePoint(codePoint);
            if (serializedLength(result.toString()) > maxSerializedLength) {
                result.setLength(previousLength);
                break;
            }
        }
        return result.toString();
    }

    private Map<String, Object> reducedAuditSnapshot(Object snapshot, boolean minimal) {
        if (!(snapshot instanceof Map<?, ?> source) || source.isEmpty()) return Map.of();
        Map<String, Object> reduced = new LinkedHashMap<>();
        for (String key : List.of("strategyId", "sid", "deviceId", "name", "status", "version")) {
            copyAuditScalar(source, reduced, key, minimal ? 32 : 96);
        }
        for (String key : List.of("priority", "owner", "lockVersion", "desiredStatus", "commandState",
                "statusSource", "activated", "remoteUrlKey", "hitStrategy", "hitStrategyVersion",
                "lastOperatorId", "lastOperationReason")) {
            copyAuditScalar(source, reduced, key, minimal ? 32 : 96);
        }
        int nestedTextLimit = minimal ? 24 : 64;
        copyAuditMap(source, reduced, "ruleTreeSummary",
                Set.of("mode", "ruleCount", "groupCount", "maxDepth"), nestedTextLimit);
        copyAuditMap(source, reduced, "action", Set.of("type", "remoteUrlKey"), nestedTextLimit);
        copyAuditMap(source, reduced, "scope",
                Set.of("channelsCount", "inviteCodesCount", "cohortIdsCount"), nestedTextLimit);
        copyAuditMap(source, reduced, "safeguards", SAFEGUARD_FIELDS, nestedTextLimit);
        copyAuditMap(source, reduced, "rollout",
                Set.of("percent", "cohortIdsCount", "startedAt", "endsAt"), nestedTextLimit);
        return reduced;
    }

    private void copyAuditMap(Map<?, ?> source, Map<String, Object> target, String key, Set<String> fields,
                              int maxTextLength) {
        Object nested = source.get(key);
        if (!(nested instanceof Map<?, ?> nestedMap)) return;
        Map<String, Object> compact = new LinkedHashMap<>();
        for (String field : fields) copyAuditScalar(nestedMap, compact, field, maxTextLength);
        if (!compact.isEmpty()) target.put(key, compact);
    }

    private void copyAuditScalar(Map<?, ?> source, Map<String, Object> target, String key, int maxTextLength) {
        Object value = source.get(key);
        if (value == null || value instanceof Map<?, ?> || value instanceof Iterable<?>) return;
        target.put(key, value instanceof Number || value instanceof Boolean
                ? value : compactText(String.valueOf(value), maxTextLength));
    }

    private int serializedLength(Object value) {
        try {
            return objectMapper.writeValueAsString(value).length();
        } catch (JsonProcessingException ex) {
            return Integer.MAX_VALUE;
        }
    }

    private Object compactAuditValue(Object value, int depth) {
        if (value == null) return null;
        if (value instanceof JanusStrategyView strategy) return compactStrategyAudit(strategy);
        if (value instanceof JanusDeviceView device) return compactDeviceAudit(device);
        if (value instanceof JsonNode node) return compactJson(node, depth);
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> compact = new LinkedHashMap<>();
            int count = 0;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = String.valueOf(entry.getKey());
                if (forbiddenAuditKey(key) || count++ >= 16) continue;
                compact.put(key, depth >= 3 ? compactText(String.valueOf(entry.getValue()), 160)
                        : compactAuditValue(entry.getValue(), depth + 1));
            }
            return compact;
        }
        if (value instanceof Iterable<?> iterable) {
            List<Object> compact = new ArrayList<>();
            int count = 0;
            for (Object item : iterable) {
                if (count++ >= 8) break;
                compact.add(depth >= 3 ? compactText(String.valueOf(item), 160)
                        : compactAuditValue(item, depth + 1));
            }
            return compact;
        }
        if (value instanceof Number || value instanceof Boolean) return value;
        return compactText(String.valueOf(value), 500);
    }

    private Map<String, Object> compactStrategyAudit(JanusStrategyView strategy) {
        Map<String, Object> compact = new LinkedHashMap<>();
        compact.put("strategyId", compactText(strategy.strategyId(), 100));
        compact.put("name", compactText(strategy.name(), 160));
        compact.put("status", compactText(strategy.status(), 32));
        compact.put("version", strategy.version());
        compact.put("priority", strategy.priority());
        compact.put("owner", compactText(strategy.owner(), 120));
        compact.put("scope", compactFields(strategy.scope(), SCOPE_FIELDS));
        compact.put("ruleTreeSummary", ruleTreeSummary(strategy.ruleTree()));
        compact.put("action", compactFields(strategy.action(), Set.of("type", "remoteUrlKey")));
        compact.put("safeguards", compactFields(strategy.safeguards(), SAFEGUARD_FIELDS));
        compact.put("rollout", compactFields(strategy.rollout(), ROLLOUT_FIELDS));
        compact.put("lockVersion", strategy.lockVersion());
        return compact;
    }

    private Map<String, Object> compactDeviceAudit(JanusDeviceView device) {
        Map<String, Object> compact = new LinkedHashMap<>();
        compact.put("sid", compactText(device.sid(), 100));
        compact.put("deviceId", compactText(device.deviceId(), 100));
        compact.put("status", compactText(device.status(), 40));
        compact.put("desiredStatus", compactText(device.desiredStatus(), 40));
        compact.put("commandState", compactText(device.commandState(), 40));
        compact.put("statusSource", compactText(device.statusSource(), 40));
        compact.put("activated", device.activated());
        compact.put("remoteUrlKey", compactText(device.remoteUrlKey(), 100));
        compact.put("hitStrategy", compactText(device.hitStrategy(), 100));
        compact.put("hitStrategyVersion", device.hitStrategyVersion());
        compact.put("lastOperatorId", compactText(device.lastOperatorId(), 100));
        compact.put("lastOperationReason", compactText(device.lastOperationReason(), 240));
        compact.put("version", device.version());
        return compact;
    }

    private Map<String, Object> compactFields(JsonNode node, Set<String> allowedFields) {
        Map<String, Object> compact = new LinkedHashMap<>();
        if (node == null || !node.isObject()) return compact;
        for (String field : allowedFields) {
            JsonNode value = node.get(field);
            if (value == null || value.isNull()) continue;
            if (value.isArray()) {
                List<Object> sample = new ArrayList<>();
                for (int i = 0; i < Math.min(value.size(), 2); i++) {
                    JsonNode item = value.get(i);
                    sample.add(item.isTextual() ? compactText(item.asText(), 32) : compactJson(item, 2));
                }
                compact.put(field, sample);
                compact.put(field + "Count", value.size());
            } else {
                compact.put(field, value.isTextual() ? compactText(value.asText(), 96) : compactJson(value, 1));
            }
        }
        return compact;
    }

    private Map<String, Object> ruleTreeSummary(JsonNode ruleTree) {
        Map<String, Object> summary = new LinkedHashMap<>();
        if (ruleTree == null || !ruleTree.isObject()) {
            summary.put("ruleCount", 0);
            summary.put("groupCount", 0);
            summary.put("maxDepth", 0);
            return summary;
        }
        if (ruleTree.hasNonNull("mode")) summary.put("mode", compactText(ruleTree.path("mode").asText(), 40));
        int[] metrics = new int[3];
        summarizeRules(ruleTree, 1, metrics);
        summary.put("ruleCount", metrics[0]);
        summary.put("groupCount", metrics[1]);
        summary.put("maxDepth", metrics[2]);
        return summary;
    }

    private void summarizeRules(JsonNode node, int depth, int[] metrics) {
        if (node == null || !node.isObject()) return;
        JsonNode rules = node.get("rules");
        if (rules != null && rules.isArray()) {
            metrics[1]++;
            metrics[2] = Math.max(metrics[2], depth);
            for (JsonNode child : rules) {
                if (child.has("rules")) summarizeRules(child, depth + 1, metrics);
                else metrics[0]++;
            }
        }
    }

    private Object compactJson(JsonNode node, int depth) {
        if (node == null || node.isNull()) return null;
        if (node.isTextual()) return compactText(node.asText(), 240);
        if (node.isBoolean()) return node.booleanValue();
        if (node.isNumber()) return node.numberValue();
        if (node.isArray()) {
            List<Object> values = new ArrayList<>();
            int limit = Math.min(node.size(), 8);
            for (int i = 0; i < limit; i++) values.add(compactJson(node.get(i), depth + 1));
            return values;
        }
        if (node.isObject() && depth < 3) {
            Map<String, Object> values = new LinkedHashMap<>();
            var fields = node.fields();
            int count = 0;
            while (fields.hasNext() && count < 16) {
                var field = fields.next();
                if (forbiddenAuditKey(field.getKey())) continue;
                values.put(field.getKey(), compactJson(field.getValue(), depth + 1));
                count++;
            }
            return values;
        }
        return compactText(node.toString(), 240);
    }

    private boolean forbiddenAuditKey(String key) {
        if (key == null) return false;
        String normalized = key.replace("_", "").toLowerCase(Locale.ROOT);
        return Set.of("versions", "sourcecontext", "hash", "confighash").contains(normalized);
    }

    private String compactText(String value, int maxLength) {
        if (value == null) return "";
        String normalized = value.strip();
        return normalized.length() <= maxLength ? normalized : normalized.substring(0, maxLength);
    }

    private JanusRole currentRole() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) return JanusRole.VIEWER;
        String actor = authentication.getName();
        if ("superadmin".equalsIgnoreCase(actor)) return JanusRole.ADMIN;
        if (authentication.getDetails() instanceof Map<?, ?> details
                && "superadmin".equalsIgnoreCase(String.valueOf(details.get("username")))) return JanusRole.ADMIN;
        List<String> authorities = authentication.getAuthorities().stream().map(GrantedAuthority::getAuthority).toList();
        if (authorities.stream().anyMatch(a -> a.equals("ROLE_SUPER_ADMIN") || a.equals("risk_k6_admin"))) return JanusRole.ADMIN;
        if (authorities.contains("risk_k6_senior")) return JanusRole.SENIOR_OPERATOR;
        if (authorities.contains("risk_k6_write")) return JanusRole.OPERATOR;
        return authorities.contains("risk_k6_read") ? JanusRole.VIEWER : JanusRole.VIEWER;
    }

    private boolean canManageStrategies() {
        JanusRole role = currentRole();
        return role == JanusRole.SENIOR_OPERATOR || role == JanusRole.ADMIN;
    }

    private String currentActor() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null) return "system";
        if (authentication.getDetails() instanceof Map<?, ?> details) {
            Object username = details.get("username");
            if (username != null && StringUtils.hasText(String.valueOf(username))) return String.valueOf(username);
        }
        return !StringUtils.hasText(authentication.getName()) ? "system" : authentication.getName();
    }

    private ObjectNode snapshot(JanusStrategyView strategy) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("name", strategy.name());
        node.put("description", strategy.description());
        node.put("priority", strategy.priority());
        node.put("owner", strategy.owner());
        node.set("scope", strategy.scope());
        node.set("ruleTree", strategy.ruleTree());
        node.set("action", strategy.action());
        node.set("safeguards", strategy.safeguards());
        node.set("rollout", strategy.rollout());
        node.set("healthConfig", strategy.healthConfig());
        node.put("templateKey", strategy.templateKey());
        return node;
    }

    private String hash(Object value) {
        try {
            byte[] data = objectMapper.writeValueAsBytes(value);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (JsonProcessingException | NoSuchAlgorithmException ex) {
            throw new IllegalStateException("JANUS_HASH_FAILED", ex);
        }
    }

    private String deterministicStrategyId(String idempotencyKey) {
        String normalized = StringUtils.hasText(idempotencyKey) ? idempotencyKey.trim() : "missing";
        return "K6-" + hash(normalized).substring(0, 12).toUpperCase(Locale.ROOT);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> commandPayload(String idempotencyKey) {
        Object value = repository.findCommand(idempotencyKey.trim()).map(command -> command.get("payloadJson")).orElse(null);
        JsonNode node = parse(value == null ? null : String.valueOf(value));
        if (!node.isObject()) return Map.of();
        return objectMapper.convertValue(node, LinkedHashMap.class);
    }

    private <T> Optional<T> commandResult(String idempotencyKey, Class<T> resultType) {
        Object value = repository.findCommand(idempotencyKey.trim()).map(command -> command.get("payloadJson")).orElse(null);
        if (value == null) return Optional.empty();
        try {
            return Optional.of(objectMapper.readValue(String.valueOf(value), resultType));
        } catch (JsonProcessingException ignored) {
            return Optional.empty();
        }
    }

    @SuppressWarnings("unchecked")
    private Optional<Map<String, Object>> commandResultMap(String idempotencyKey) {
        Object value = repository.findCommand(idempotencyKey.trim()).map(command -> command.get("payloadJson")).orElse(null);
        if (value == null) return Optional.empty();
        try {
            return Optional.of(objectMapper.readValue(String.valueOf(value), LinkedHashMap.class));
        } catch (JsonProcessingException ignored) {
            return Optional.empty();
        }
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value == null ? Map.of() : value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("JANUS_JSON_FAILED", ex);
        }
    }

    private JsonNode parse(String value) {
        if (!StringUtils.hasText(value)) return objectMapper.createObjectNode();
        try {
            return objectMapper.readTree(value);
        } catch (JsonProcessingException ex) {
            return objectMapper.createObjectNode();
        }
    }

    private static String first(String first, String second) {
        return StringUtils.hasText(first) ? first : second;
    }

    private static String textFilter(Object value) {
        return value == null || !StringUtils.hasText(String.valueOf(value)) || "all".equalsIgnoreCase(String.valueOf(value))
                ? null : String.valueOf(value).trim();
    }

    private static long epoch(LocalDateTime time) {
        return time == null ? 0 : time.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }

    @SuppressWarnings("unchecked")
    private static <T> ApiResult<T> cast(ApiResult<?> result) {
        return (ApiResult<T>) result;
    }

    private record CommandGuard(boolean duplicate, ApiResult<?> failure) {}
}
