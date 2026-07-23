package ffdd.opsconsole.emergency.application;


import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.audit.AuditLogQueryRequest;
import ffdd.opsconsole.shared.audit.AuditLogRecord;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.common.api.OpsErrorCode;
import ffdd.opsconsole.common.boundary.ApplicationService;
import ffdd.opsconsole.emergency.domain.EmergencyControlRepository;
import ffdd.opsconsole.emergency.domain.KillSwitchState;
import ffdd.opsconsole.emergency.dto.AutoTriggerConfirmationRequest;
import ffdd.opsconsole.emergency.dto.EmergencyConfigUpdateRequest;
import ffdd.opsconsole.emergency.dto.EmergencyDisableRequest;
import ffdd.opsconsole.emergency.dto.KillSwitchToggleRequest;
import ffdd.opsconsole.shared.seed.OpsReadTimeSeedPolicy;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import ffdd.opsconsole.shared.outbox.EventOutboxService;
import ffdd.opsconsole.shared.security.AdminActorResolver;
import ffdd.opsconsole.treasury.facade.TreasuryCoverageFacade;
import ffdd.opsconsole.treasury.facade.TreasuryCoverageSnapshot;
import ffdd.opsconsole.treasury.facade.TreasuryEmergencySignalFacade;
import ffdd.opsconsole.treasury.facade.TreasuryEmergencySignalSnapshot;
import ffdd.opsconsole.treasury.application.TreasuryEmergencySignalFacadeAdapter;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.HexFormat;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.util.StringUtils;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;

@ApplicationService
@RequiredArgsConstructor
public class OpsKillSwitchService {
    private static final ObjectMapper AUDIT_JSON_READER = new ObjectMapper();
    private static final List<String> ACTIVE_GATES = List.of("withdraw", "staking", "genesis", "exchange", "trial");
    private static final Set<String> RETIRED_GATES = Set.of("premium", "nexv2", "nex-v2", "points");
    private static final Set<String> TRIGGER_BASES = Set.of("监管点名", "挤兑风险", "安全事件", "其他");
    private static final Set<String> AUTO_CONFIRM_DECISIONS = Set.of("keep_disabled", "recommend_restore");
    private static final String GROUP_KILL_SWITCH = "admin_killswitch";
    private static final String GROUP_EMERGENCY = "admin_emergency";
    private static final String GROUP_AUTORULE = "admin_emergency_autorule";
    private static final String TAMPER_ALERT_THRESHOLD_CONFIG_KEY = "emergency.tamper.alert.threshold";
    private static final DateTimeFormatter CHANGE_TIME = DateTimeFormatter.ofPattern("MM-dd HH:mm");

    private static final List<GateSeed> GATE_SEEDS = List.of(
            new GateSeed("withdraw", "提现闸", "提现申请、审核与链上广播", "控制所有提现资金出口", "提现出口一键止血", true, true, "immediate", ""),
            new GateSeed("staking", "质押闸", "新质押与高息档恢复", "控制新增质押和未来利息负债", "恢复会增加未来利息负债", true, true, "delayed", ""),
            new GateSeed("genesis", "Genesis 闸", "创世节点购买与二级市场", "控制节点购买、分红和交易流", "恢复会放大节点分红和交易流", true, true, "delayed", ""),
            new GateSeed("exchange", "兑换闸", "NEX 与 USDT 兑换", "控制兑换资金出口", "兑换出口一键止血", true, true, "immediate", ""),
            new GateSeed("trial", "试用闸", "试用权益派发与续期", "控制不直接出金的试用权益", "不直接出钱但影响增长权益", false, false, "none", ""));

    private static final List<EmergencySlaSeed> EMERGENCY_SLA_SEEDS = List.of(
            new EmergencySlaSeed("autoConfirmMins", "自动关停补录时限", "R1/R2 自动关停后，值班人员须在此时限内补录处置理由", "30", "分钟", "number", true),
            new EmergencySlaSeed("recoverGate", "恢复业务的备付金门槛", "备付金覆盖率达到这条线,才允许恢复会往外付钱的业务", "", "%", "number", false));

    private static final List<AutoRuleSeed> AUTO_RULE_SEEDS = List.of(
            new AutoRuleSeed("withdrawSurge", "提现激增 / 挤兑", "资金安全 · R1", "surge",
                    List.of("24h 提现申请额 ÷ 真实储备 ≥ ", "40%", "(B5 挤兑红线)→ 自动熔断 ", "提现", ""),
                    "触发阈值", "40", "number", "%", "automatic", false, "同 B5 挤兑红线", "阈值权威归 B5(bankrunRed,默认 40%),J1 引用不另持"),
            new AutoRuleSeed("maturityGap", "对账缺口", "资金安全 · R2", "gap",
                    List.of("钱包余额与最新账本余额差额 > ", "50,000 USDT", " → 自动熔断 ", "兑换", "(停止 NEX↔USDT 流出,待对账恢复)"),
                    "触发阈值", "50000", "number", "USDT", "automatic", true, "", ""),
            new AutoRuleSeed("tamperCluster", "篡改告警激增", "风控 · R3", "shield",
                    List.of("单账户超 ", "告警阈值", " 或全域环比突增 → ", "仅自动告警 · 人工研判后手动熔断", "(J3 不持处置权)"),
                    "触发阈值", "10", "number", "次 / 24h", "advisory", false, "同 J3 告警阈值", "阈值与 J3 告警阈值配置同源,调整在 J3 页头完成"),
            new AutoRuleSeed("regulatoryDirective", "监管指令", "合规 · R4", "clock",
                    List.of("监管点名 / 法务事件(事由必填)→ ", "人工经应急快速通道发起", "(机器不替监管判定)", "", ""),
                    "触发方式", "人工 · 应急快速通道", "text", "", "manual", false, "", ""));

    private final EmergencyControlRepository emergencyRepository;
    private final TreasuryCoverageFacade coverageFacade;
    private final TreasuryEmergencySignalFacade emergencySignalFacade;
    private final AuditLogService auditLogService;
    private final EventOutboxService outboxService;
    private final AdminIdempotencyService idempotencyService;
    private final OpsReadTimeSeedPolicy readTimeSeedPolicy;
    @Transactional
    public ApiResult<Map<String, Object>> matrix() {
        ensureSeedData();
        return matrix(coverageFacade.snapshot());
    }

    public ApiResult<Map<String, Object>> alerts() {
        List<Map<String, Object>> gates = activeGateKeys().stream().map(key -> {
            Map<String, Object> gate = gateView(key);
            return Map.<String, Object>of(
                    "key", gate.get("key"),
                    "name", gate.get("name"),
                    "enabled", gate.get("enabled"),
                    "emergency", gate.get("emergency"),
                    "lastChange", gate.get("lastChange"));
        }).toList();
        List<Map<String, Object>> confirmations = autoConfirmationRows().stream()
                .map(row -> Map.<String, Object>of(
                        "key", row.get("key"),
                        "name", row.get("name"),
                        "overdue", row.get("overdue")))
                .toList();
        return ApiResult.ok(new LinkedHashMap<>(Map.of(
                "activeGateCount", gates.size(),
                "activeGates", gates,
                "autoConfirmations", confirmations,
                "generatedAt", LocalDateTime.now().toString())));
    }

    private ApiResult<Map<String, Object>> matrix(TreasuryCoverageSnapshot coverage) {
        List<Map<String, Object>> gates = activeGateKeys().stream().map(this::gateView).toList();
        long live = gates.stream().filter(gate -> Boolean.TRUE.equals(gate.get("enabled"))).count();
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("domain", "J1");
        response.put("activeGateCount", gates.size());
        response.put("activeGates", gates);
        response.put("retiredGates", retiredGates());
        response.put("coverage", Map.of(
                "coverageRatio", coverage.coverageRatio(),
                "redlinePct", coverage.redlinePct(),
                "yellowLinePct", coverage.redlinePct().add(BigDecimal.valueOf(25)),
                "recoveryAllowed", coverage.coverageRatio().compareTo(coverage.redlinePct()) >= 0));
        response.put("stats", Map.of(
                "liveGateCount", live,
                "killedGateCount", gates.size() - live,
                "emergencyGateCount", gates.stream().filter(gate -> Boolean.TRUE.equals(gate.get("emergency"))).count(),
                "coverageBlockedCount", auditLogService.countByActionAndResourceType(
                        "J1_COVERAGE_RESTORE_BLOCKED", "KILL_SWITCH")));
        response.put("emergencySla", emergencySlaRows(coverage));
        response.put("autoRules", autoRuleRows());
        response.put("autoConfirmations", autoConfirmationRows());
        response.put("executionModel", "single confirm-with-reason plus broadcast and A2 audit");
        response.put("sources", List.of("nx_emergency_control_setting:killswitch.*", "nx_emergency_control_setting:ops.J.emergency.*", "nx_emergency_control_setting:emergency.autorule.*", "B1 treasury coverage facade"));
        return ApiResult.ok(response);
    }

    @Transactional
    public ApiResult<Map<String, Object>> toggle(String key, String idempotencyKey, KillSwitchToggleRequest request) {
        ApiResult<Map<String, Object>> guard = requireCommand(idempotencyKey, request == null ? null : request.reason());
        if (guard != null) {
            return guard;
        }
        String normalizedKey = normalizeGate(key);
        if (isRetired(normalizedKey)) {
            return retiredFeature();
        }
        boolean enable = parseEnabled(request.enabled());
        if (!enable && !StringUtils.hasText(request.triggerBasis())) {
            return validationFailed("TRIGGER_BASIS_REQUIRED");
        }
        if (!enable && !validTriggerBasis(request.triggerBasis())) {
            return validationFailed("TRIGGER_BASIS_INVALID");
        }
        if (!enable && requiresDispositionPlan(normalizedKey) && !StringUtils.hasText(request.dispositionPlan())) {
            return validationFailed("DISPOSITION_PLAN_REQUIRED");
        }
        String operator = authenticatedOperator(request.operator());
        return idempotent(
                "J1_TOGGLE:" + normalizedKey,
                idempotencyKey,
                requestHash(normalizedKey, String.valueOf(enable), request.reason(), operator,
                        request.triggerBasis(), request.dispositionPlan()),
                () -> toggleOnce(normalizedKey, enable, idempotencyKey, request, operator));
    }

    private ApiResult<Map<String, Object>> toggleOnce(
            String normalizedKey,
            boolean enable,
            String idempotencyKey,
            KillSwitchToggleRequest request,
            String operator) {
        boolean before = gateEnabled(normalizedKey);
        GateSeed seed = gateSeed(normalizedKey);
        if (autoConfirmationPending(normalizedKey)) {
            auditRejected("J1_KILLSWITCH_TOGGLE_BLOCKED", normalizedKey, operator, Map.of(
                    "before", before,
                    "requested", enable,
                    "reason", request.reason().trim(),
                    "rejection", "AUTO_CONFIRMATION_REQUIRED",
                    "idempotencyKey", idempotencyKey.trim()));
            return ApiResult.fail(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus(), "AUTO_CONFIRMATION_REQUIRED");
        }
        if (before == enable) {
            auditRejected("J1_KILLSWITCH_TOGGLE_BLOCKED", normalizedKey, operator, Map.of(
                    "before", before,
                    "requested", enable,
                    "reason", request.reason().trim(),
                    "rejection", "KILL_SWITCH_STATE_UNCHANGED",
                    "idempotencyKey", idempotencyKey.trim()));
            return ApiResult.fail(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus(), "KILL_SWITCH_STATE_UNCHANGED");
        }
        TreasuryCoverageSnapshot restoreCoverage = null;
        if (enable && !before && seed.coveragePrecheckRequired()) {
            restoreCoverage = coverageFacade.snapshot();
            if (restoreCoverage.coverageRatio().compareTo(restoreCoverage.redlinePct()) < 0) {
                auditCoverageBlocked(normalizedKey, request, operator, idempotencyKey, restoreCoverage);
                return coverageRedline();
            }
        }
        boolean changed = enable
                ? emergencyRepository.restoreKillSwitchIfNoPending(
                        configKey(normalizedKey), autoConfirmationKey(normalizedKey, "pending"), operator)
                : emergencyRepository.disableKillSwitchIfEnabled(
                        configKey(normalizedKey), legacyConfigKey(normalizedKey), operator);
        if (!changed) {
            String rejection = autoConfirmationPending(normalizedKey)
                    ? "AUTO_CONFIRMATION_REQUIRED"
                    : gateEnabled(normalizedKey) == enable
                            ? "KILL_SWITCH_STATE_UNCHANGED"
                            : enable ? "KILL_SWITCH_RECOVERY_CONFLICT" : "KILL_SWITCH_DISABLE_CONFLICT";
            auditRejected("J1_KILLSWITCH_TOGGLE_BLOCKED", normalizedKey, operator, Map.of(
                    "before", before,
                    "requested", enable,
                    "reason", request.reason().trim(),
                    "rejection", rejection,
                    "idempotencyKey", idempotencyKey.trim()));
            return ApiResult.fail(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus(), rejection);
        }
        writeEmergencyFlag(normalizedKey, false, operator);
        writeLastChange(normalizedKey, operator, "人工切换");
        Map<String, Object> auditDetail = new LinkedHashMap<>();
        auditDetail.put("switchKey", normalizedKey);
        auditDetail.put("before", before);
        auditDetail.put("after", enable);
        auditDetail.put("reason", request.reason().trim());
        auditDetail.put("triggerBasis", StringUtils.hasText(request.triggerBasis()) ? request.triggerBasis().trim() : "恢复");
        if (StringUtils.hasText(request.dispositionPlan())) {
            auditDetail.put("dispositionPlan", request.dispositionPlan().trim());
        }
        if (restoreCoverage != null) {
            auditDetail.put("coverageRatio", restoreCoverage.coverageRatio());
            auditDetail.put("redlinePct", restoreCoverage.redlinePct());
        }
        auditDetail.put("idempotencyKey", idempotencyKey.trim());
        auditDetail.put("trigger", "manual");
        auditDetail.put("broadcast", true);
        auditDetail.put("broadcastEventId", broadcastGateChange(
                normalizedKey, before, enable, "manual", operator, request.reason().trim()));
        audit("J1_KILLSWITCH_TOGGLED", normalizedKey, operator, auditDetail);
        Map<String, Object> response = (restoreCoverage == null ? matrix() : matrix(restoreCoverage)).getData();
        response.put("updated", Map.of("key", normalizedKey, "before", before, "after", enable));
        return ApiResult.ok(response);
    }

    @Transactional
    public ApiResult<Map<String, Object>> emergencyDisable(String idempotencyKey, EmergencyDisableRequest request) {
        ApiResult<Map<String, Object>> guard = requireCommand(idempotencyKey, request == null ? null : request.reason());
        if (guard != null) {
            return guard;
        }
        if (request.keys() == null || request.keys().isEmpty()) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "KILL_SWITCH_KEYS_REQUIRED");
        }
        List<String> keys = request.keys().stream()
                .map(this::normalizeGate)
                .distinct()
                .toList();
        if (!StringUtils.hasText(request.triggerBasis())) {
            return validationFailed("TRIGGER_BASIS_REQUIRED");
        }
        if (!validTriggerBasis(request.triggerBasis())) {
            return validationFailed("TRIGGER_BASIS_INVALID");
        }
        if (!StringUtils.hasText(request.regulatoryContext())) {
            return validationFailed("REGULATORY_CONTEXT_REQUIRED");
        }
        if (keys.stream().anyMatch(this::requiresDispositionPlan)
                && !StringUtils.hasText(request.dispositionPlan())) {
            return validationFailed("DISPOSITION_PLAN_REQUIRED");
        }
        for (String key : keys) {
            if (isRetired(key)) {
                return retiredFeature();
            }
        }
        String operator = authenticatedOperator(request.operator());
        return idempotent(
                "J1_BATCH_DISABLE",
                idempotencyKey,
                requestHash(String.join(",", keys), request.reason(), operator, request.triggerBasis(),
                        request.regulatoryContext(), request.dispositionPlan()),
                () -> emergencyDisableOnce(keys, idempotencyKey, request, operator));
    }

    private ApiResult<Map<String, Object>> emergencyDisableOnce(
            List<String> keys,
            String idempotencyKey,
            EmergencyDisableRequest request,
            String operator) {
        List<String> conflicts = keys.stream()
                .filter(key -> !gateEnabled(key) || autoConfirmationPending(key))
                .toList();
        if (!conflicts.isEmpty()) {
            auditRejected("J1_EMERGENCY_KILLSWITCH_BLOCKED", "batch", operator, Map.of(
                    "requestedKeys", keys,
                    "conflictKeys", conflicts,
                    "reason", request.reason().trim(),
                    "rejection", "KILL_SWITCH_BATCH_CONFLICT",
                    "idempotencyKey", idempotencyKey.trim()));
            return ApiResult.fail(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus(), "KILL_SWITCH_BATCH_CONFLICT");
        }
        List<Map<String, Object>> changed = new ArrayList<>();
        for (String key : keys) {
            boolean before = gateEnabled(key);
            if (!emergencyRepository.disableKillSwitchIfEnabled(
                    configKey(key), legacyConfigKey(key), operator)) {
                if (org.springframework.transaction.support.TransactionSynchronizationManager
                        .isActualTransactionActive()) {
                    org.springframework.transaction.interceptor.TransactionAspectSupport
                            .currentTransactionStatus().setRollbackOnly();
                }
                return ApiResult.fail(
                        OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus(),
                        "KILL_SWITCH_BATCH_CONFLICT");
            }
            writeEmergencyFlag(key, true, operator);
            writeLastChange(key, operator, "批量应急关停");
            String broadcastEventId = broadcastGateChange(
                    key, before, false, "manual", operator, request.reason().trim());
            Map<String, Object> changedGate = new LinkedHashMap<>();
            changedGate.put("key", key);
            changedGate.put("before", before);
            changedGate.put("after", false);
            changedGate.put("broadcastEventId", broadcastEventId);
            changed.add(changedGate);

            Map<String, Object> gateAudit = new LinkedHashMap<>();
            gateAudit.put("switchKey", key);
            gateAudit.put("batchKeys", keys);
            gateAudit.put("before", before);
            gateAudit.put("after", false);
            gateAudit.put("reason", request.reason().trim());
            gateAudit.put("triggerBasis", request.triggerBasis().trim());
            gateAudit.put("regulatoryContext", request.regulatoryContext().trim());
            if (StringUtils.hasText(request.dispositionPlan())) {
                gateAudit.put("dispositionPlan", request.dispositionPlan().trim());
            }
            gateAudit.put("idempotencyKey", idempotencyKey.trim());
            gateAudit.put("trigger", "manual");
            gateAudit.put("operationMode", "batch");
            gateAudit.put("broadcast", true);
            gateAudit.put("broadcastEventId", broadcastEventId);
            audit("J1_EMERGENCY_KILLSWITCH_TRIGGERED", key, operator, gateAudit);
        }
        Map<String, Object> response = matrix().getData();
        response.put("updated", changed);
        return ApiResult.ok(response);
    }

    @Transactional
    public ApiResult<Map<String, Object>> restoreFromLinkedDomain(
            String key,
            String operator,
            String reason,
            String sourceDomain) {
        String normalizedKey = normalizeGate(key);
        GateSeed seed = gateSeed(normalizedKey);
        if (autoConfirmationPending(normalizedKey)) {
            return ApiResult.fail(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus(), "AUTO_CONFIRMATION_REQUIRED");
        }
        if (gateEnabled(normalizedKey)) {
            return ApiResult.fail(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus(), "KILL_SWITCH_STATE_UNCHANGED");
        }
        TreasuryCoverageSnapshot coverage = coverageFacade.snapshot();
        if (seed.coveragePrecheckRequired()
                && coverage.coverageRatio().compareTo(coverage.redlinePct()) < 0) {
            auditRejected("J1_COVERAGE_RESTORE_BLOCKED", normalizedKey, authenticatedOperator(operator), Map.of(
                    "switchKey", normalizedKey,
                    "coverageRatio", coverage.coverageRatio(),
                    "redlinePct", coverage.redlinePct(),
                    "reason", reason == null ? "" : reason.trim(),
                    "sourceDomain", sourceDomain == null ? "" : sourceDomain,
                    "trigger", "linked-domain"));
            return coverageRedline();
        }
        String actor = authenticatedOperator(operator);
        if (!emergencyRepository.restoreKillSwitchIfNoPending(
                configKey(normalizedKey), autoConfirmationKey(normalizedKey, "pending"), actor)) {
            if (autoConfirmationPending(normalizedKey)) {
                return ApiResult.fail(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus(), "AUTO_CONFIRMATION_REQUIRED");
            }
            return ApiResult.fail(
                    OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus(),
                    gateEnabled(normalizedKey)
                            ? "KILL_SWITCH_STATE_UNCHANGED"
                            : "KILL_SWITCH_RECOVERY_CONFLICT");
        }
        writeEmergencyFlag(normalizedKey, false, actor);
        writeLastChange(normalizedKey, actor, "下游联动恢复");
        return matrix(coverage);
    }

    /**
     * Single write boundary for downstream surfaces that are projections of a J1 gate.
     * The caller owns its domain validation; J1 owns the authenticated actor, deduplication,
     * atomic gate transition and audit trail.
     */
    @Transactional
    public ApiResult<Map<String, Object>> changeFromLinkedDomain(
            String key,
            boolean enable,
            String operator,
            String reason,
            String sourceDomain,
            String idempotencyKey) {
        ApiResult<Map<String, Object>> guard = requireCommand(idempotencyKey, reason);
        if (guard != null) {
            return guard;
        }
        String normalizedKey = normalizeGate(key);
        gateSeed(normalizedKey);
        String actor = authenticatedOperator(operator);
        String source = StringUtils.hasText(sourceDomain) ? sourceDomain.trim().toUpperCase(Locale.ROOT) : "LINKED";
        return idempotent(
                "J1_LINKED_GATE:" + source + ":" + normalizedKey,
                idempotencyKey,
                requestHash(normalizedKey, String.valueOf(enable), reason.trim(), actor, source),
                () -> changeFromLinkedDomainOnce(normalizedKey, enable, actor, reason.trim(), source, idempotencyKey));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ApiResult<Map<String, Object>> changeFromJ4Execution(
            String key,
            String operator,
            String reason,
            String executionId) {
        String normalizedExecutionId = normalizeConfigValue(executionId, "J4_EXECUTION_ID_REQUIRED");
        return changeFromLinkedDomain(
                key,
                false,
                operator,
                reason,
                "J4",
                "J4:" + normalizedExecutionId + ":" + normalizeGate(key));
    }

    @Transactional
    public ApiResult<Map<String, Object>> restoreFromJ4Execution(
            String key,
            String operator,
            String reason,
            String executionId,
            String expectedOwnershipToken) {
        String normalizedKey = normalizeGate(key);
        String actor = authenticatedOperator(operator);
        String normalizedExecutionId = normalizeConfigValue(executionId, "J4_EXECUTION_ID_REQUIRED");
        if (!StringUtils.hasText(expectedOwnershipToken)) {
            return ApiResult.fail(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus(), "J4_GATE_OWNERSHIP_MISSING");
        }
        gateSeed(normalizedKey);
        String claim = expectedOwnershipToken.trim() + " · 回滚中 " + normalizedExecutionId;
        if (!emergencyRepository.compareAndSetSetting(
                lastChangeConfigKey(normalizedKey), expectedOwnershipToken.trim(), claim, actor)) {
            return ApiResult.fail(409, "J4_GATE_OWNERSHIP_LOST");
        }
        ApiResult<Map<String, Object>> restored = restoreFromLinkedDomain(normalizedKey, actor, reason, "J4");
        if (restored.getCode() != 0) {
            throw new BizException(restored.getCode(), restored.getMessage());
        }
        writeLastChange(normalizedKey, actor, "J4 回滚 " + normalizedExecutionId);
        return restored;
    }

    private ApiResult<Map<String, Object>> changeFromLinkedDomainOnce(
            String normalizedKey,
            boolean enable,
            String actor,
            String reason,
            String source,
            String idempotencyKey) {
        boolean before = gateEnabled(normalizedKey);
        if (autoConfirmationPending(normalizedKey)) {
            return ApiResult.fail(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus(), "AUTO_CONFIRMATION_REQUIRED");
        }
        if (before == enable) {
            return ApiResult.fail(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus(), "KILL_SWITCH_STATE_UNCHANGED");
        }
        if (enable) {
            ApiResult<Map<String, Object>> recovery = restoreFromLinkedDomain(
                    normalizedKey, actor, reason, source);
            if (recovery.getCode() != 0) {
                return recovery;
            }
        } else {
            if (!emergencyRepository.disableKillSwitchIfEnabled(
                    configKey(normalizedKey), legacyConfigKey(normalizedKey), actor)) {
                return ApiResult.fail(
                        OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus(),
                        gateEnabled(normalizedKey)
                                ? "KILL_SWITCH_DISABLE_CONFLICT"
                                : "KILL_SWITCH_STATE_UNCHANGED");
            }
            writeEmergencyFlag(normalizedKey, true, actor);
            // The canonical ownership marker is written below for both directions.
        }
        String ownershipToken = "J4".equals(source) && idempotencyKey.startsWith("J4:")
                ? writeOwnershipToken(normalizedKey, actor, idempotencyKey)
                : writeLastChange(normalizedKey, actor, "下游联动 " + source + " " + idempotencyKey.trim());
        Map<String, Object> linkedAudit = new LinkedHashMap<>();
        linkedAudit.put("switchKey", normalizedKey);
        linkedAudit.put("before", before);
        linkedAudit.put("after", enable);
        linkedAudit.put("reason", reason);
        linkedAudit.put("sourceDomain", source);
        linkedAudit.put("idempotencyKey", idempotencyKey.trim());
        linkedAudit.put("trigger", "linked-domain");
        linkedAudit.put("broadcast", true);
        linkedAudit.put("broadcastEventId", broadcastGateChange(
                normalizedKey, before, enable, "linked-domain", actor, reason));
        audit("J1_LINKED_DOMAIN_GATE_CHANGED", normalizedKey, actor, linkedAudit);
        ApiResult<Map<String, Object>> response = matrix();
        response.getData().put("updated", Map.of(
                "key", normalizedKey,
                "before", before,
                "after", enable,
                "sourceDomain", source,
                "ownershipToken", ownershipToken));
        return response;
    }

    private String writeOwnershipToken(String key, String operator, String idempotencyKey) {
        String[] parts = idempotencyKey.split(":", 3);
        String executionId = parts.length > 1 ? parts[1] : "";
        String token = "J4_OWNERSHIP:" + executionId + ":" + key;
        emergencyRepository.upsertSetting(
                lastChangeConfigKey(key), token, "STRING", GROUP_KILL_SWITCH,
                "J4 execution ownership marker", operator);
        return token;
    }

    @Transactional
    public ApiResult<Map<String, Object>> confirmAutoTrigger(
            String key,
            String idempotencyKey,
            AutoTriggerConfirmationRequest request) {
        ApiResult<Map<String, Object>> guard = requireCommand(
                idempotencyKey, request == null ? null : request.reason());
        if (guard != null) {
            return guard;
        }
        String normalizedKey = normalizeGate(key);
        String incidentId;
        try {
            incidentId = normalizeConfigValue(
                    request.incidentId(), "AUTO_CONFIRMATION_INCIDENT_ID_REQUIRED");
        } catch (IllegalArgumentException ex) {
            return validationFailed(ex.getMessage());
        }
        String decision = request.decision() == null
                ? ""
                : request.decision().trim().toLowerCase(Locale.ROOT);
        if (!AUTO_CONFIRM_DECISIONS.contains(decision)) {
            return validationFailed("AUTO_CONFIRMATION_DECISION_INVALID");
        }
        String operator = authenticatedOperator(request.operator());
        return idempotent(
                "J1_AUTO_CONFIRM:" + normalizedKey,
                idempotencyKey,
                requestHash(normalizedKey, incidentId, decision, request.reason(), operator),
                () -> confirmAutoTriggerOnce(
                        normalizedKey, incidentId, decision, idempotencyKey, request, operator));
    }

    private ApiResult<Map<String, Object>> confirmAutoTriggerOnce(
            String normalizedKey,
            String expectedIncidentId,
            String decision,
            String idempotencyKey,
            AutoTriggerConfirmationRequest request,
            String operator) {
        String incidentSettingKey = autoConfirmationKey(normalizedKey, "incidentId");
        String currentIncidentId = activeValue(incidentSettingKey).orElse("");
        if (!expectedIncidentId.equals(currentIncidentId)) {
            return ApiResult.fail(
                    OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus(),
                    "AUTO_CONFIRMATION_INCIDENT_MISMATCH");
        }
        if (!emergencyRepository.completeAutoConfirmation(
                autoConfirmationKey(normalizedKey, "pending"),
                incidentSettingKey,
                expectedIncidentId,
                operator)) {
            String incidentAfterConflict = activeValue(incidentSettingKey).orElse("");
            return ApiResult.fail(
                    OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus(),
                    expectedIncidentId.equals(incidentAfterConflict)
                            ? "AUTO_CONFIRMATION_ALREADY_COMPLETED"
                            : "AUTO_CONFIRMATION_INCIDENT_MISMATCH");
        }
        LocalDateTime confirmedAt = LocalDateTime.now();
        writeAutoConfirmationField(normalizedKey, "decision", decision, "STRING", operator);
        writeAutoConfirmationField(normalizedKey, "confirmedAt", confirmedAt.toString(), "DATETIME", operator);
        writeAutoConfirmationField(normalizedKey, "confirmedBy", operator, "STRING", operator);
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("switchKey", normalizedKey);
        detail.put("decision", decision);
        detail.put("reason", request.reason().trim());
        detail.put("incidentId", expectedIncidentId);
        detail.put("ruleId", activeValue(autoConfirmationKey(normalizedKey, "ruleId")).orElse(""));
        detail.put("triggeredAt", activeValue(autoConfirmationKey(normalizedKey, "triggeredAt")).orElse(""));
        detail.put("confirmationDueAt", activeValue(autoConfirmationKey(normalizedKey, "dueAt")).orElse(""));
        detail.put("confirmedAt", confirmedAt.toString());
        detail.put("idempotencyKey", idempotencyKey.trim());
        detail.put("trigger", "auto");
        audit("J1_AUTO_KILLSWITCH_CONFIRMED", normalizedKey, operator, detail);
        Map<String, Object> response = matrix().getData();
        response.put("updated", Map.of("key", normalizedKey, "decision", decision, "confirmedAt", confirmedAt));
        return ApiResult.ok(response);
    }

    @Transactional
    public ApiResult<Map<String, Object>> updateEmergencySla(String paramKey, String idempotencyKey, EmergencyConfigUpdateRequest request) {
        ApiResult<Map<String, Object>> guard = requireCommand(idempotencyKey, request == null ? null : request.reason());
        if (guard != null) {
            return guard;
        }
        EmergencySlaSeed seed = emergencySlaSeed(paramKey);
        if (!seed.editable()) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "EMERGENCY_SLA_READONLY");
        }
        String value;
        try {
            value = normalizeSlaValue(seed, request.value());
        } catch (IllegalArgumentException ex) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), ex.getMessage());
        }
        String operator = authenticatedOperator(request.operator());
        return idempotent(
                "J1_EMERGENCY_SLA:" + seed.id(),
                idempotencyKey,
                requestHash(seed.id(), value, request.reason(), operator),
                () -> updateEmergencySlaOnce(seed, value, idempotencyKey, request, operator));
    }

    private ApiResult<Map<String, Object>> updateEmergencySlaOnce(
            EmergencySlaSeed seed, String value, String idempotencyKey,
            EmergencyConfigUpdateRequest request, String operator) {
        String before = activeValue(emergencySlaConfigKey(seed.id())).orElse(seed.defaultValue());
        emergencyRepository.upsertSetting(emergencySlaConfigKey(seed.id()), value, "NUMBER", GROUP_EMERGENCY, "J1 emergency SLA parameter", operator);
        audit("J1_EMERGENCY_SLA_CHANGED", seed.id(), operator, Map.of(
                "paramKey", seed.id(),
                "before", before,
                "after", value,
                "reason", request.reason().trim(),
                "trigger", "manual",
                "idempotencyKey", idempotencyKey.trim()));
        Map<String, Object> response = matrix().getData();
        response.put("updated", Map.of("paramKey", seed.id(), "value", value));
        return ApiResult.ok(response);
    }

    @Transactional
    public ApiResult<Map<String, Object>> updateAutoRule(String ruleId, String idempotencyKey, EmergencyConfigUpdateRequest request) {
        ApiResult<Map<String, Object>> guard = requireCommand(idempotencyKey, request == null ? null : request.reason());
        if (guard != null) {
            return guard;
        }
        AutoRuleSeed seed = autoRuleSeed(ruleId);
        if (!seed.adjustable()) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "AUTO_RULE_READONLY");
        }
        String value;
        try {
            value = normalizeAutoRuleValue(seed, request.value());
        } catch (IllegalArgumentException ex) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), ex.getMessage());
        }
        String operator = authenticatedOperator(request.operator());
        return idempotent(
                "J1_AUTO_RULE:" + seed.id(),
                idempotencyKey,
                requestHash(seed.id(), value, request.reason(), operator),
                () -> updateAutoRuleOnce(seed, value, idempotencyKey, request, operator));
    }

    private ApiResult<Map<String, Object>> updateAutoRuleOnce(
            AutoRuleSeed seed, String value, String idempotencyKey,
            EmergencyConfigUpdateRequest request, String operator) {
        String before = activeValue(autoRuleConfigKey(seed.id())).orElse(seed.threshold());
        emergencyRepository.upsertSetting(autoRuleConfigKey(seed.id()), value, seed.valueType().toUpperCase(Locale.ROOT), GROUP_AUTORULE, "J1 auto trigger rule threshold", operator);
        audit("J1_AUTO_RULE_CHANGED", seed.id(), operator, Map.of(
                "ruleId", seed.id(),
                "before", before,
                "after", value,
                "reason", request.reason().trim(),
                "trigger", "manual",
                "idempotencyKey", idempotencyKey.trim()));
        Map<String, Object> response = matrix().getData();
        response.put("updated", Map.of("ruleId", seed.id(), "value", value));
        return ApiResult.ok(response);
    }

    private Map<String, Object> gateView(String key) {
        GateSeed seed = gateSeed(key);
        boolean enabled = gateEnabled(key);
        Map<String, Object> gate = new LinkedHashMap<>();
        gate.put("key", key);
        gate.put("name", seed.name());
        gate.put("cap", seed.cap());
        gate.put("desc", seed.description());
        gate.put("enabled", enabled);
        gate.put("on", enabled);
        gate.put("status", enabled ? "enabled" : "disabled");
        gate.put("configKey", configKey(key));
        gate.put("recoveryPrecheck", seed.coveragePrecheckRequired() ? "B1_COVERAGE_REDLINE" : "NONE");
        gate.put("coveragePrecheckRequired", seed.coveragePrecheckRequired());
        gate.put("coverageImpactCategory", seed.coverageImpactCategory());
        gate.put("amplifies", seed.amplifies());
        gate.put("ownerDomain", "J1");
        gate.put("lastChange", activeValue(lastChangeConfigKey(key))
                .orElse(""));
        gate.put("emergency", Boolean.parseBoolean(activeValue(emergencyFlagConfigKey(key)).orElse("false")));
        return gate;
    }

    private List<String> activeGateKeys() {
        return ACTIVE_GATES;
    }

    private List<Map<String, Object>> retiredGates() {
        List<Map<String, Object>> retired = new ArrayList<>();
        retired.add(retired("premium", "Premium subscription is sunset"));
        retired.add(retired("nexv2", "NEX v2 vault is sunset; only historical maturity remains"));
        retired.add(retired("points", "Points system is sunset and replaced by NEX reward rules"));
        return retired;
    }

    private Map<String, Object> retired(String key, String reason) {
        Map<String, Object> gate = new LinkedHashMap<>();
        gate.put("key", key);
        gate.put("status", "SUNSET_HISTORY_ONLY");
        gate.put("toggleAllowed", false);
        gate.put("reason", reason);
        return gate;
    }

    private ApiResult<Map<String, Object>> requireCommand(String idempotencyKey, String reason) {
        if (!StringUtils.hasText(idempotencyKey)) {
            return ApiResult.fail(OpsErrorCode.IDEMPOTENCY_KEY_REQUIRED.httpStatus(), OpsErrorCode.IDEMPOTENCY_KEY_REQUIRED.name());
        }
        if (!StringUtils.hasText(reason)) {
            return ApiResult.fail(OpsErrorCode.REASON_REQUIRED.httpStatus(), OpsErrorCode.REASON_REQUIRED.name());
        }
        int reasonLength = reason.trim().length();
        if (reasonLength < 8 || reasonLength > 200) {
            return ApiResult.fail(OpsErrorCode.REASON_REQUIRED.httpStatus(), "REASON_LENGTH_INVALID");
        }
        return null;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private ApiResult<Map<String, Object>> idempotent(
            String scope,
            String idempotencyKey,
            String requestHash,
            Supplier<ApiResult<Map<String, Object>>> action) {
        return (ApiResult<Map<String, Object>>) (ApiResult) idempotencyService.execute(
                scope, idempotencyKey, requestHash, ApiResult.class, (Supplier) action);
    }

    private String requestHash(String... parts) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String part : parts) {
                digest.update((part == null ? "" : part).getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (RuntimeException | java.security.NoSuchAlgorithmException ex) {
            throw new IllegalStateException("J1_REQUEST_HASH_FAILED", ex);
        }
    }

    private String authenticatedOperator(String fallback) {
        String resolved = AdminActorResolver.resolve(fallback);
        return StringUtils.hasText(resolved) ? resolved.trim() : "system";
    }

    private boolean validTriggerBasis(String triggerBasis) {
        return StringUtils.hasText(triggerBasis) && TRIGGER_BASES.contains(triggerBasis.trim());
    }

    private String normalizeGate(String key) {
        String normalized = key == null ? "" : key.trim().toLowerCase(Locale.ROOT);
        if (isRetired(normalized)) {
            return normalized;
        }
        if (!ACTIVE_GATES.contains(normalized)) {
            throw new IllegalArgumentException("Unsupported J1 kill switch key");
        }
        return normalized;
    }

    private boolean isRetired(String key) {
        return RETIRED_GATES.contains(key);
    }

    private ApiResult<Map<String, Object>> retiredFeature() {
        return ApiResult.fail(OpsErrorCode.RETIRED_FEATURE.httpStatus(), OpsErrorCode.RETIRED_FEATURE.name());
    }

    private ApiResult<Map<String, Object>> coverageRedline() {
        return ApiResult.fail(OpsErrorCode.COVERAGE_BELOW_REDLINE.httpStatus(), OpsErrorCode.COVERAGE_BELOW_REDLINE.name());
    }

    private boolean parseEnabled(String enabled) {
        String normalized = enabled == null ? "" : enabled.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "true", "enabled", "enable", "on", "1" -> true;
            case "false", "disabled", "disable", "off", "0" -> false;
            default -> throw new IllegalArgumentException("enabled must be true/false or enabled/disabled");
        };
    }

    private boolean gateEnabled(String key) {
        return KillSwitchState.enabled(activeValue(configKey(key)), activeValue(legacyConfigKey(key)));
    }

    private void writeGate(String key, boolean enabled, String operator) {
        emergencyRepository.upsertSetting(configKey(key), enabled ? "enabled" : "disabled", "STRING", GROUP_KILL_SWITCH, "J1 active kill switch", operator);
    }

    private void writeEmergencyFlag(String key, boolean emergency, String operator) {
        emergencyRepository.upsertSetting(emergencyFlagConfigKey(key), String.valueOf(emergency), "BOOLEAN", GROUP_KILL_SWITCH, "J1 emergency kill switch marker", operator);
    }

    private String writeLastChange(String key, String operator, String trigger) {
        String actor = StringUtils.hasText(operator) ? operator.trim() : "system";
        String source = StringUtils.hasText(trigger) ? trigger.trim() : "状态切换";
        String value = CHANGE_TIME.format(LocalDateTime.now()) + " · " + actor + " · " + source;
        emergencyRepository.upsertSetting(
                lastChangeConfigKey(key),
                value,
                "STRING",
                GROUP_KILL_SWITCH,
                "J1 kill switch latest change",
                actor);
        return value;
    }

    private void ensureSeedData() {
        if (readTimeBusinessSeedsDisabled()) {
            return;
        }
        if (!readTimeSeedPolicy.enabled()) {
            return;
        }
        for (GateSeed seed : GATE_SEEDS) {
            ensureConfig(configKey(seed.key()), "enabled", "STRING", GROUP_KILL_SWITCH, "J1 active kill switch default");
            ensureConfig(emergencyFlagConfigKey(seed.key()), "false", "BOOLEAN", GROUP_KILL_SWITCH, "J1 emergency kill switch marker");
            ensureConfig(lastChangeConfigKey(seed.key()), seed.lastChange(), "STRING", GROUP_KILL_SWITCH, "J1 kill switch latest change");
        }
        for (EmergencySlaSeed seed : EMERGENCY_SLA_SEEDS) {
            if (seed.editable()) {
                ensureConfig(emergencySlaConfigKey(seed.id()), seed.defaultValue(), seed.valueType().toUpperCase(Locale.ROOT), GROUP_EMERGENCY, "J1 emergency SLA parameter");
            }
        }
        for (AutoRuleSeed seed : AUTO_RULE_SEEDS) {
            if (StringUtils.hasText(seed.threshold())) {
                ensureConfig(autoRuleConfigKey(seed.id()), seed.threshold(), "STRING", GROUP_AUTORULE, "J1 auto trigger rule threshold");
            }
        }
    }

    private boolean readTimeBusinessSeedsDisabled() {
        return true;
    }

    private void ensureConfig(String key, String value, String valueType, String group, String remark) {
        if (!readTimeSeedPolicy.enabled()) {
            return;
        }
        if (activeValue(key).isEmpty()) {
            emergencyRepository.upsertSetting(key, value, valueType, group, remark, "system");
        }
    }

    private Optional<String> activeValue(String configKey) {
        return emergencyRepository.settingValue(configKey);
    }

    private List<Map<String, Object>> emergencySlaRows(TreasuryCoverageSnapshot coverage) {
        return EMERGENCY_SLA_SEEDS.stream()
                .map(seed -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", seed.id());
            row.put("k", seed.name());
            row.put("d", seed.description());
            row.put("unit", seed.unit());
            row.put("kind", seed.valueType().toLowerCase(Locale.ROOT));
            row.put("editable", seed.editable());
            row.put("v", "recoverGate".equals(seed.id())
                    ? coverage.redlinePct().stripTrailingZeros().toPlainString()
                    : activeValue(emergencySlaConfigKey(seed.id()))
                    .filter(StringUtils::hasText)
                    .orElse(seed.defaultValue()));
            row.put("source", "recoverGate".equals(seed.id()) ? "B1 treasury coverage" : emergencySlaConfigKey(seed.id()));
            return row;
        }).toList();
    }

    private List<Map<String, Object>> autoRuleRows() {
        // 四条自动触发规则是固定目录,不应因 DB 未配阈值而消失;DB 有值则覆盖,否则落回 seed 自带阈值。
        return AUTO_RULE_SEEDS.stream()
                .map(seed -> {
            String threshold = switch (seed.id()) {
                case "withdrawSurge" -> emergencySignalFacade.bankRunRedlinePct()
                        .stripTrailingZeros().toPlainString();
                case "tamperCluster" -> activeValue(TAMPER_ALERT_THRESHOLD_CONFIG_KEY)
                        .filter(StringUtils::hasText)
                        .orElse(seed.threshold());
                default -> activeValue(autoRuleConfigKey(seed.id()))
                        .filter(StringUtils::hasText)
                        .orElse(seed.threshold());
            };
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", seed.id());
            row.put("nm", seed.name());
            row.put("tag", seed.tag());
            row.put("icon", seed.icon());
            row.put("cond", "withdrawSurge".equals(seed.id())
                    ? List.of("24h 提现申请额 ÷ 真实储备 ≥ ", threshold + "%", "(B5 挤兑红线)→ 自动熔断 ", "提现", "")
                    : seed.condition());
            row.put("thrK", seed.thresholdKey());
            row.put("thr", threshold);
            row.put("kind", seed.valueType());
            row.put("unit", seed.unit());
            row.put("mode", seed.mode());
            row.put("adjustable", seed.adjustable());
            row.put("refNote", seed.refNote());
            row.put("refTitle", seed.refTitle());
            row.put("configKey", switch (seed.id()) {
                case "withdrawSurge" -> TreasuryEmergencySignalFacadeAdapter.BANK_RUN_REDLINE_CONFIG_KEY;
                case "tamperCluster" -> TAMPER_ALERT_THRESHOLD_CONFIG_KEY;
                default -> autoRuleConfigKey(seed.id());
            });
            return row;
        }).toList();
    }

    private GateSeed gateSeed(String key) {
        return GATE_SEEDS.stream()
                .filter(seed -> seed.key().equals(key))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unsupported J1 kill switch key"));
    }

    private EmergencySlaSeed emergencySlaSeed(String paramKey) {
        String normalized = paramKey == null ? "" : paramKey.trim();
        return EMERGENCY_SLA_SEEDS.stream()
                .filter(seed -> seed.id().equals(normalized))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("EMERGENCY_SLA_PARAM_NOT_FOUND"));
    }

    private AutoRuleSeed autoRuleSeed(String ruleId) {
        String normalized = ruleId == null ? "" : ruleId.trim();
        return AUTO_RULE_SEEDS.stream()
                .filter(seed -> seed.id().equals(normalized))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("AUTO_RULE_NOT_FOUND"));
    }

    private String normalizeConfigValue(String value, String errorCode) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException(errorCode);
        }
        return value.trim();
    }

    private ApiResult<Map<String, Object>> validationFailed(String message) {
        return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), message);
    }

    private boolean requiresDispositionPlan(String key) {
        return "staking".equals(key) || "genesis".equals(key);
    }

    private String normalizeSlaValue(EmergencySlaSeed seed, String rawValue) {
        String value = normalizeConfigValue(rawValue, "EMERGENCY_SLA_VALUE_REQUIRED");
        int parsed;
        try {
            parsed = Integer.parseInt(value);
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("EMERGENCY_SLA_VALUE_MUST_BE_INTEGER");
        }
        int min = "autoConfirmMins".equals(seed.id()) ? 10 : 1;
        int max = "autoConfirmMins".equals(seed.id()) ? 120 : 60;
        if (parsed < min || parsed > max) {
            throw new IllegalArgumentException("EMERGENCY_SLA_VALUE_OUT_OF_RANGE");
        }
        return String.valueOf(parsed);
    }

    private String normalizeAutoRuleValue(AutoRuleSeed seed, String rawValue) {
        String value = normalizeConfigValue(rawValue, "AUTO_RULE_VALUE_REQUIRED");
        if (!"maturityGap".equals(seed.id())) {
            return value;
        }
        try {
            BigDecimal parsed = new BigDecimal(value);
            if (parsed.compareTo(BigDecimal.ZERO) <= 0 || parsed.compareTo(new BigDecimal("1000000000")) > 0) {
                throw new NumberFormatException("out of range");
            }
            return parsed.stripTrailingZeros().toPlainString();
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("AUTO_RULE_THRESHOLD_MUST_BE_POSITIVE_USDT");
        }
    }

    BigDecimal maturityGapThresholdUsdt() {
        String configured = activeValue(autoRuleConfigKey("maturityGap")).orElse("50000");
        try {
            String normalized = configured.trim().replace(",", "").replace("$", "").toUpperCase(Locale.ROOT);
            BigDecimal multiplier = BigDecimal.ONE;
            if (normalized.endsWith("K")) {
                normalized = normalized.substring(0, normalized.length() - 1);
                multiplier = new BigDecimal("1000");
            } else if (normalized.endsWith("M")) {
                normalized = normalized.substring(0, normalized.length() - 1);
                multiplier = new BigDecimal("1000000");
            }
            BigDecimal value = new BigDecimal(normalized).multiply(multiplier);
            return value.compareTo(BigDecimal.ZERO) > 0 ? value : new BigDecimal("50000");
        } catch (RuntimeException ex) {
            return new BigDecimal("50000");
        }
    }

    /** Restore missing pending work items for historical automatic kills. */
    @Transactional
    public int repairLegacyLastChangeLabels() {
        int repaired = 0;
        for (String key : ACTIVE_GATES) {
            if (emergencyRepository.repairLegacyLastChange(
                    lastChangeConfigKey(key), "system:j1-consistency-repair")) {
                repaired++;
            }
        }
        return repaired;
    }

    /** Restore missing pending work items for historical automatic kills. */
    @Transactional
    public int repairAutoConfirmationOrphans() {
        int repaired = 0;
        for (String key : ACTIVE_GATES) {
            if (!isAutoConfirmationOrphan(key)) {
                continue;
            }
            String operator = "system:j1-auto-repair";
            if (!emergencyRepository.claimMissingAutoConfirmation(
                    autoConfirmationKey(key, "pending"), configKey(key), emergencyFlagConfigKey(key),
                    lastChangeConfigKey(key), operator)) {
                continue;
            }
            AutoTriggerEvidence evidence = latestAutoTriggerEvidence(key).orElseGet(() -> currentAutoTriggerEvidence(key));
            writeAutoConfirmationField(key, "incidentId", evidence.incidentId(), "STRING", operator);
            writeAutoConfirmationField(key, "ruleId", evidence.ruleId(), "STRING", operator);
            writeAutoConfirmationField(key, "signalValue", evidence.signalValue(), "DECIMAL", operator);
            writeAutoConfirmationField(key, "threshold", evidence.threshold(), "DECIMAL", operator);
            writeAutoConfirmationField(key, "triggeredAt", evidence.triggeredAt().toString(), "DATETIME", operator);
            writeAutoConfirmationField(key, "dueAt", evidence.dueAt().toString(), "DATETIME", operator);
            writeAutoConfirmationField(key, "lastReminderAt", "", "DATETIME", operator);
            audit("J1_AUTO_CONFIRMATION_REPAIRED", key, operator, Map.of(
                    "switchKey", key,
                    "incidentId", evidence.incidentId(),
                    "ruleId", evidence.ruleId(),
                    "sourceAuditId", evidence.sourceAuditId(),
                    "trigger", "consistency-repair",
                    "reason", "自动关停状态存在但待补录记录缺失，按原始审计恢复失败关闭状态"));
            repaired++;
        }
        return repaired;
    }

    private Optional<AutoTriggerEvidence> latestAutoTriggerEvidence(String key) {
        AuditLogQueryRequest query = new AuditLogQueryRequest();
        query.setAction("J1_AUTO_KILLSWITCH_TRIGGERED");
        query.setResourceType("KILL_SWITCH");
        query.setResourceId(key);
        query.setResult("SUCCESS");
        query.setLimit(1);
        List<AuditLogRecord> records = auditLogService.list(query);
        if (records == null || records.isEmpty()) {
            return Optional.empty();
        }
        AuditLogRecord record = records.get(0);
        try {
            Map<String, Object> detail = AUDIT_JSON_READER.readValue(
                    record.getDetailJson(), new TypeReference<Map<String, Object>>() {});
            LocalDateTime triggeredAt = record.getCreatedAt() == null ? LocalDateTime.now() : record.getCreatedAt();
            LocalDateTime dueAt = parseDateTime(text(detail.get("confirmationDueAt")));
            if (dueAt == null) {
                dueAt = triggeredAt.plusMinutes(autoConfirmMins());
            }
            return Optional.of(new AutoTriggerEvidence(
                    textOr(detail.get("incidentId"), "J1-AUTO-REPAIR-" + UUID.randomUUID()),
                    textOr(detail.get("ruleId"), inferredAutoRule(key)),
                    decimalText(detail.get("signalValue"), "0"),
                    decimalText(detail.get("threshold"), inferredAutoThreshold(key)),
                    triggeredAt,
                    dueAt,
                    record.getId() == null ? "" : String.valueOf(record.getId())));
        } catch (RuntimeException | java.io.IOException ignored) {
            return Optional.empty();
        }
    }

    private AutoTriggerEvidence currentAutoTriggerEvidence(String key) {
        LocalDateTime now = LocalDateTime.now();
        String signal = "0";
        try {
            TreasuryEmergencySignalSnapshot signals = emergencySignalFacade.snapshot();
            if (signals != null) {
                BigDecimal value = "withdraw".equals(key)
                        ? signals.bankRunRatioPct()
                        : signals.reconciliationGapUsdt();
                if (value != null) {
                    signal = value.stripTrailingZeros().toPlainString();
                }
            }
        } catch (RuntimeException ignored) {
            // Consistency repair must fail closed even when a signal source is temporarily unavailable.
        }
        return new AutoTriggerEvidence(
                "J1-AUTO-REPAIR-" + UUID.randomUUID(), inferredAutoRule(key), signal,
                inferredAutoThreshold(key), now, now, "");
    }

    private String inferredAutoRule(String key) {
        return "withdraw".equals(key) ? "withdrawSurge" : "maturityGap";
    }

    private String inferredAutoThreshold(String key) {
        BigDecimal value;
        try {
            value = "withdraw".equals(key) ? emergencySignalFacade.bankRunRedlinePct() : maturityGapThresholdUsdt();
        } catch (RuntimeException ignored) {
            value = "withdraw".equals(key) ? new BigDecimal("40") : new BigDecimal("50000");
        }
        if (value == null) {
            value = "withdraw".equals(key) ? new BigDecimal("40") : new BigDecimal("50000");
        }
        return value.stripTrailingZeros().toPlainString();
    }

    private String decimalText(Object value, String fallback) {
        try {
            return new BigDecimal(textOr(value, fallback)).stripTrailingZeros().toPlainString();
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private String textOr(Object value, String fallback) {
        String resolved = text(value);
        return StringUtils.hasText(resolved) ? resolved : fallback;
    }

    @Transactional
    public boolean autoDisable(String key, String ruleId, BigDecimal signalValue, BigDecimal threshold) {
        String normalizedKey = normalizeGate(key);
        gateSeed(normalizedKey);
        String operator = "system:j1-auto-trigger";
        if (!emergencyRepository.disableKillSwitchIfEnabled(
                configKey(normalizedKey), legacyConfigKey(normalizedKey), operator)) {
            return false;
        }
        writeEmergencyFlag(normalizedKey, true, operator);
        writeLastChange(normalizedKey, operator, "自动触发 " + autoRuleSeed(ruleId).name());
        int autoConfirmMins = autoConfirmMins();
        LocalDateTime triggeredAt = LocalDateTime.now();
        LocalDateTime confirmationDueAt = triggeredAt.plusMinutes(autoConfirmMins);
        String incidentId = "J1-AUTO-" + UUID.randomUUID();
        writeAutoConfirmationField(normalizedKey, "pending", "true", "BOOLEAN", operator);
        writeAutoConfirmationField(normalizedKey, "incidentId", incidentId, "STRING", operator);
        writeAutoConfirmationField(normalizedKey, "ruleId", ruleId, "STRING", operator);
        writeAutoConfirmationField(normalizedKey, "signalValue", signalValue.toPlainString(), "DECIMAL", operator);
        writeAutoConfirmationField(normalizedKey, "threshold", threshold.toPlainString(), "DECIMAL", operator);
        writeAutoConfirmationField(normalizedKey, "triggeredAt", triggeredAt.toString(), "DATETIME", operator);
        writeAutoConfirmationField(normalizedKey, "dueAt", confirmationDueAt.toString(), "DATETIME", operator);
        writeAutoConfirmationField(normalizedKey, "lastReminderAt", "", "DATETIME", operator);
        // Once the pending incident exists, reassert the disabled postcondition. A
        // concurrent restore that slipped in before the pending marker is now closed,
        // and all later restores are rejected by restoreKillSwitchIfNoPending.
        emergencyRepository.disableKillSwitchIfEnabled(
                configKey(normalizedKey), legacyConfigKey(normalizedKey), operator);
        Map<String, Object> auditDetail = new LinkedHashMap<>();
        auditDetail.put("switchKey", normalizedKey);
        auditDetail.put("incidentId", incidentId);
        auditDetail.put("ruleId", ruleId);
        auditDetail.put("signalValue", signalValue);
        auditDetail.put("threshold", threshold);
        auditDetail.put("before", true);
        auditDetail.put("after", false);
        auditDetail.put("pendingAutoConfirmation", true);
        auditDetail.put("autoConfirmMins", autoConfirmMins);
        auditDetail.put("confirmationDueAt", confirmationDueAt.toString());
        auditDetail.put("trigger", "auto");
        auditDetail.put("broadcast", true);
        auditDetail.put("broadcastEventId", broadcastGateChange(
                normalizedKey, true, false, "auto", operator,
                ruleId + ":" + signalValue.toPlainString() + "/" + threshold.toPlainString()));
        audit("J1_AUTO_KILLSWITCH_TRIGGERED", normalizedKey, operator, auditDetail);
        return true;
    }

    @Transactional
    public int remindOverdueAutoConfirmations() {
        LocalDateTime now = LocalDateTime.now();
        int reminded = 0;
        for (Map<String, Object> row : autoConfirmationRows(now)) {
            if (!Boolean.TRUE.equals(row.get("overdue"))) {
                continue;
            }
            String key = String.valueOf(row.get("key"));
            String reminderKey = autoConfirmationKey(key, "lastReminderAt");
            String expected = activeValue(reminderKey).orElse("");
            LocalDateTime previous = parseDateTime(expected);
            if (previous != null && previous.isAfter(now.minusMinutes(10))) {
                continue;
            }
            if (!emergencyRepository.compareAndSetSetting(reminderKey, expected, now.toString(), "system:j1-auto-reminder")) {
                continue;
            }
            audit("J1_AUTO_CONFIRMATION_OVERDUE_REMINDER", key, "system:j1-auto-reminder", Map.of(
                    "switchKey", key,
                    "incidentId", row.get("incidentId"),
                    "ruleId", row.get("ruleId"),
                    "confirmationDueAt", row.get("dueAt"),
                    "trigger", "auto",
                    "remindedAt", now.toString()));
            reminded++;
        }
        return reminded;
    }

    private List<Map<String, Object>> autoConfirmationRows() {
        return autoConfirmationRows(LocalDateTime.now());
    }

    private List<Map<String, Object>> autoConfirmationRows(LocalDateTime now) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (String key : ACTIVE_GATES) {
            if (!autoConfirmationPending(key)) {
                continue;
            }
            String dueAtValue = activeValue(autoConfirmationKey(key, "dueAt")).orElse("");
            LocalDateTime dueAt = parseDateTime(dueAtValue);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("key", key);
            row.put("name", gateSeed(key).name());
            row.put("incidentId", activeValue(autoConfirmationKey(key, "incidentId")).orElse(""));
            row.put("ruleId", activeValue(autoConfirmationKey(key, "ruleId")).orElse(""));
            row.put("signalValue", activeValue(autoConfirmationKey(key, "signalValue")).orElse(""));
            row.put("threshold", activeValue(autoConfirmationKey(key, "threshold")).orElse(""));
            row.put("triggeredAt", activeValue(autoConfirmationKey(key, "triggeredAt")).orElse(""));
            row.put("dueAt", dueAtValue);
            row.put("overdue", dueAt == null || !dueAt.isAfter(now));
            rows.add(row);
        }
        return rows;
    }

    private boolean autoConfirmationPending(String key) {
        Optional<String> pending = activeValue(autoConfirmationKey(key, "pending"));
        return pending.map(Boolean::parseBoolean).orElseGet(() -> isAutoConfirmationOrphan(key));
    }

    private boolean isAutoConfirmationOrphan(String key) {
        return activeValue(autoConfirmationKey(key, "pending")).isEmpty()
                && !gateEnabled(key)
                && Boolean.parseBoolean(activeValue(emergencyFlagConfigKey(key)).orElse("false"))
                && activeValue(lastChangeConfigKey(key))
                .filter(value -> value.contains("system:j1-auto-trigger"))
                .isPresent();
    }

    private LocalDateTime parseDateTime(String value) {
        try {
            return StringUtils.hasText(value) ? LocalDateTime.parse(value) : null;
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private void writeAutoConfirmationField(
            String key, String field, String value, String valueType, String operator) {
        emergencyRepository.upsertSetting(
                autoConfirmationKey(key, field), value, valueType, GROUP_EMERGENCY,
                "J1 automatic kill switch confirmation", operator);
    }

    private int autoConfirmMins() {
        try {
            int configured = Integer.parseInt(activeValue(emergencySlaConfigKey("autoConfirmMins")).orElse("30"));
            return configured >= 10 && configured <= 120 ? configured : 30;
        } catch (RuntimeException ex) {
            return 30;
        }
    }

    private String configKey(String key) {
        return "killswitch." + key;
    }

    private String legacyConfigKey(String key) {
        if ("staking".equals(key) || "genesis".equals(key)) {
            return "J.killswitch." + key;
        }
        return "emergency.killswitch." + key;
    }

    private String emergencyFlagConfigKey(String key) {
        return "emergency.killswitch." + key + ".emergency";
    }

    private String lastChangeConfigKey(String key) {
        return "emergency.killswitch." + key + ".lastChange";
    }

    private String autoConfirmationKey(String key, String field) {
        return "emergency.killswitch." + key + ".auto-confirm." + field;
    }

    private String emergencySlaConfigKey(String paramKey) {
        return "ops.J.emergency." + paramKey;
    }

    private String autoRuleConfigKey(String ruleId) {
        return "emergency.autorule." + ruleId;
    }

    private String broadcastGateChange(
            String switchKey,
            boolean before,
            boolean after,
            String trigger,
            String operator,
            String reason) {
        return outboxService.publish("KILL_SWITCH", switchKey, "J1_KILLSWITCH_CHANGED", Map.of(
                "switchKey", switchKey,
                "before", before,
                "after", after,
                "trigger", trigger,
                "operator", operator,
                "reason", reason,
                "occurredAt", LocalDateTime.now().toString()));
    }

    private void audit(String action, String switchKey, String operator, Map<String, Object> detail) {
        auditLogService.recordRequired(AuditLogWriteRequest.builder()
                .action(action)
                .resourceType("KILL_SWITCH")
                .resourceId(switchKey)
                .bizNo(switchKey)
                .actorType("ADMIN")
                .actorUsername(StringUtils.hasText(operator) ? operator.trim() : "system")
                .result("SUCCESS")
                .riskLevel("CRITICAL")
                .detail(detail)
                .build());
    }

    private void auditRejected(String action, String switchKey, String operator, Map<String, Object> detail) {
        auditLogService.recordRequired(AuditLogWriteRequest.builder()
                .action(action)
                .resourceType("KILL_SWITCH")
                .resourceId(switchKey)
                .bizNo(switchKey)
                .actorType("ADMIN")
                .actorUsername(StringUtils.hasText(operator) ? operator.trim() : "system")
                .result("BLOCKED")
                .riskLevel("CRITICAL")
                .detail(detail)
                .build());
    }

    private void auditCoverageBlocked(String switchKey, KillSwitchToggleRequest request, String operator, String idempotencyKey,
                                      TreasuryCoverageSnapshot coverage) {
        auditLogService.recordRequired(AuditLogWriteRequest.builder()
                .action("J1_COVERAGE_RESTORE_BLOCKED")
                .resourceType("KILL_SWITCH")
                .resourceId(switchKey)
                .bizNo(switchKey)
                .actorType("ADMIN")
                .actorUsername(operator)
                .result("BLOCKED")
                .riskLevel("CRITICAL")
                .detail(Map.of(
                        "switchKey", switchKey,
                        "coverageRatio", coverage.coverageRatio(),
                        "redlinePct", coverage.redlinePct(),
                        "reason", request.reason().trim(),
                        "trigger", "manual",
                        "idempotencyKey", idempotencyKey.trim()))
                .build());
    }

    private record AutoTriggerEvidence(
            String incidentId,
            String ruleId,
            String signalValue,
            String threshold,
            LocalDateTime triggeredAt,
            LocalDateTime dueAt,
            String sourceAuditId) {}

    private record GateSeed(String key, String name, String cap, String description, String recoveryNote,
                            boolean coveragePrecheckRequired, boolean amplifies, String coverageImpactCategory,
                            String lastChange) {
    }

    private record EmergencySlaSeed(String id, String name, String description, String defaultValue,
                                    String unit, String valueType, boolean editable) {
    }

    private record AutoRuleSeed(String id, String name, String tag, String icon, List<String> condition,
                                String thresholdKey, String threshold, String valueType, String unit, String mode,
                                boolean adjustable, String refNote, String refTitle) {
    }
}
