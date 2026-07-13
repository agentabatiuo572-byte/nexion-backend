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
    private static final Set<String> COMMAND_ACTIONS = Set.of("REVERSAL_IMMEDIATE", "REVERSAL_SESSION_EDGE",
            "ENV_FILTER", "MANUAL_HOLD", "BLOCK");

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
        boolean overrideExpired = repository.expireDeviceOverride(userId, sid, System.currentTimeMillis());
        JanusDeviceView prior = repository.findDevice(sid).orElse(null);
        ReportEvaluation evaluation = evaluateReport(sid, telemetry, prior);
        JanusDeviceReportRequest authoritative = authoritativeReport(telemetry, evaluation);
        int elapsedMs = (int) Math.min(Integer.MAX_VALUE, (System.nanoTime() - startedAt) / 1_000_000L);
        boolean accepted = repository.insertEvaluation(sid, authoritative.reportId(), sessionId(authoritative),
                evaluation.strategyId(), evaluation.strategyVersion(), json(reportInput(authoritative)),
                json(evaluation.ruleResults()), evaluation.action(), evaluation.status(), null, elapsedMs,
                "janus-server-v1");
        if (!accepted) {
            if (evaluation.quotaReserved()) {
                repository.releaseDailyEvaluation(evaluation.strategyId(), evaluation.action());
            }
            if (overrideExpired) {
                repository.upsertDeviceReport(userId, sid, authoritative);
            }
            return repository.findDevice(sid).map(ApiResult::ok)
                    .orElseGet(() -> ApiResult.fail(409, "JANUS_REPORT_REPLAY_CONFLICT"));
        }
        repository.upsertDeviceReport(userId, sid, authoritative);
        JanusDeviceView persisted = repository.findDevice(sid).orElse(null);
        if (persisted == null) return ApiResult.fail(500, "JANUS_REPORT_NOT_PERSISTED");
        boolean recovery = prior != null && "strategy".equals(prior.statusSource())
                && (!evaluation.status().equals(prior.status())
                || !java.util.Objects.equals(evaluation.remoteUrlKey(), prior.remoteUrlKey()));
        if (COMMAND_ACTIONS.contains(evaluation.action()) || recovery) {
            ObjectNode command = objectMapper.createObjectNode();
            command.put("source", "strategy");
            command.put("reportId", authoritative.reportId());
            command.put("action", evaluation.action());
            if (evaluation.strategyId() != null) command.put("strategyId", evaluation.strategyId());
            if (repository.publishStrategyCommand(sid, persisted.version(), evaluation.status(),
                    evaluation.remoteUrlKey(), command.toString())) {
                outboxService.publish("JANUS_DEVICE", sid, "JANUS_STRATEGY_COMMAND_PUBLISHED", command);
                persisted = repository.findDevice(sid).orElse(persisted);
            }
        }
        return ApiResult.ok(persisted);
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
                || request.revision() <= 0 || request.success() == null) {
            return ApiResult.fail(422, "JANUS_ACK_INVALID");
        }
        String applied = request.appliedStatus() == null ? null : request.appliedStatus().trim().toUpperCase(Locale.ROOT);
        if (Boolean.TRUE.equals(request.success()) && !STATUSES.contains(applied)) {
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
        if (updated) repository.updateDeviceCommandRecord(sid, state);
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
        requiredAudit("K6_STRATEGY_CREATED", "JANUS_STRATEGY", strategyId, request.reason(), Map.of("after", created));
        return ApiResult.ok(created);
    }

    @Transactional
    public ApiResult<JanusStrategyView> updateStrategy(String strategyId, String idempotencyKey,
                                                       JanusStrategyUpsertRequest request) {
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
        JanusStrategyView before = repository.findStrategy(strategyId).orElse(null);
        if (before == null) return ApiResult.fail(404, "JANUS_STRATEGY_NOT_FOUND");
        if (request == null) return ApiResult.fail(422, "STRATEGY_ACTION_REQUIRED");
        String reason = request == null ? null : first(request.reason(), request.note());
        if (!StringUtils.hasText(reason) || reason.trim().length() < 4) return ApiResult.fail(422, "REASON_REQUIRED");
        if (request.expectedVersion() == null) return ApiResult.fail(422, "EXPECTED_VERSION_REQUIRED");
        String normalized = action == null ? "" : action.toLowerCase(Locale.ROOT);
        if (!List.of("publish", "pause", "archive").contains(normalized)) {
            return ApiResult.fail(422, "STRATEGY_ACTION_INVALID");
        }
        if ("publish".equals(normalized) && currentRole() != JanusRole.ADMIN) {
            return ApiResult.fail(403, "ROLE_FORBIDDEN");
        }
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
        JanusStrategyView before = repository.findStrategy(strategyId).orElse(null);
        if (before == null) return ApiResult.fail(404, "JANUS_STRATEGY_NOT_FOUND");
        if (currentRole() != JanusRole.ADMIN) return ApiResult.fail(403, "ROLE_FORBIDDEN");
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
        if (!repository.deleteStrategy(strategyId, request.expectedVersion())) {
            repository.releaseCommandReservation(idempotencyKey.trim());
            return ApiResult.fail(409, "STRATEGY_DELETE_FORBIDDEN");
        }
        repository.completeCommand(idempotencyKey.trim(), "ACKED", json(request));
        requiredAudit("K6_STRATEGY_DELETED", "JANUS_STRATEGY", strategyId, reason, Map.of("before", before));
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
        if (!StringUtils.hasText(request.name()) || request.name().trim().length() < 2) return "STRATEGY_NAME_REQUIRED";
        if (!StringUtils.hasText(request.owner())) return "STRATEGY_OWNER_REQUIRED";
        if (request.priority() == null || request.priority() < 0 || request.priority() > 1000) return "STRATEGY_PRIORITY_INVALID";
        if (request.ruleTree() == null || !request.ruleTree().has("rules") || !request.ruleTree().path("rules").isArray()) return "INVALID_RULE_TREE";
        if (!validRuleFields(request.ruleTree())) return "INVALID_RULE_FIELD";
        if (request.action() == null || !ACTIONS.contains(request.action().path("type").asText())) return "STRATEGY_ACTION_INVALID";
        String actionType = request.action().path("type").asText();
        boolean needsRemote = actionType.startsWith("REVERSAL_");
        if (needsRemote && !StringUtils.hasText(request.action().path("remoteUrlKey").asText())) return "REMOTE_TARGET_REQUIRED";
        String remoteUrlKey = request.action().path("remoteUrlKey").asText();
        if (StringUtils.hasText(remoteUrlKey)
                && (!remoteUrlKey.equals(remoteUrlKey.trim()) || !REMOTE_TARGETS.contains(remoteUrlKey))) {
            return "REMOTE_TARGET_INVALID";
        }
        if (!StringUtils.hasText(request.reason()) || request.reason().trim().length() < 4) return "REASON_REQUIRED";
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
                first(request.platform(), "Android"), trim(request.model(), 128), trim(request.osName(), 128),
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
        return validMetrics(maturity, Map.of(
                "appOpenCount", 1_000_000, "sessionCount", 1_000_000,
                "foregroundDurationSeconds", 31_536_000, "repeatStreakDays", 3_650))
                && validFlags(maturity, List.of("benchmarkViewed", "optimizeDone", "marketViewed", "walletViewed"))
                && validMetrics(environment, Map.of("automationSignalCount", 1_000_000))
                && validFlags(environment, List.of("isHeadless", "fpBlocklistHit", "screenAnomaly",
                "timezoneMismatch", "languageMismatch"));
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

    private boolean validRuleFields(JsonNode node) {
        if (node == null) return false;
        if (node.has("rules")) {
            for (JsonNode child : node.path("rules")) if (!validRuleFields(child)) return false;
            return true;
        }
        return RULE_FIELDS.contains(node.path("field").asText());
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
        view.put("actorId", first(record.getActorUsername(), String.valueOf(record.getActorId())));
        view.put("action", record.getAction());
        view.put("targetType", "JANUS_STRATEGY".equals(record.getResourceType()) ? "strategy" : "JANUS_DEVICE".equals(record.getResourceType()) ? "device" : "config");
        view.put("targetId", record.getResourceId());
        view.put("beforeSnapshot", detail.path("before"));
        view.put("afterSnapshot", detail.path("after"));
        view.put("reasonCategory", detail.path("reasonCategory").asText(""));
        view.put("reasonText", detail.path("reason").asText(""));
        view.put("sourceContext", detail);
        view.put("createdAt", epoch(record.getCreatedAt()));
        view.put("requestId", record.getTraceId());
        return view;
    }

    private CommandGuard commandGuard(String key, String type, String target, String requestHash) {
        if (!StringUtils.hasText(key)) return new CommandGuard(false, ApiResult.fail(422, OpsErrorCode.IDEMPOTENCY_KEY_REQUIRED.name()));
        if (repository.reserveCommand(key.trim(), type, target, requestHash, currentActor())) {
            return new CommandGuard(false, null);
        }
        Optional<Map<String, Object>> existing = repository.findCommand(key.trim());
        if (existing.isEmpty()) return new CommandGuard(false, ApiResult.fail(409, "IDEMPOTENCY_RESERVATION_FAILED"));
        Map<String, Object> command = existing.get();
        boolean same = type.equals(String.valueOf(command.get("commandType")))
                && target.equals(String.valueOf(command.get("targetId")))
                && requestHash.equals(String.valueOf(command.get("requestHash")));
        return same ? new CommandGuard(true, null) : new CommandGuard(false, ApiResult.fail(409, "IDEMPOTENCY_CONFLICT"));
    }

    private void requiredAudit(String action, String resourceType, String resourceId, String reason, Object detail) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("reason", reason);
        if (detail instanceof Map<?, ?> detailMap) {
            detailMap.forEach((key, value) -> payload.put(String.valueOf(key), value));
        } else {
            payload.put("detail", detail);
        }
        auditLogService.recordRequired(AuditLogWriteRequest.builder()
                .action(action).resourceType(resourceType).resourceId(resourceId)
                .actorType("ADMIN").actorUsername(currentActor()).method("ADMIN_COMMAND")
                .path("/api/admin/janus").result("SUCCESS").riskLevel("HIGH")
                .detail(payload).build());
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
        if (authorities.contains("risk_k6_write")) return JanusRole.SENIOR_OPERATOR;
        return authorities.contains("risk_k6_read") ? JanusRole.VIEWER : JanusRole.VIEWER;
    }

    private String currentActor() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication == null || !StringUtils.hasText(authentication.getName()) ? "system" : authentication.getName();
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
