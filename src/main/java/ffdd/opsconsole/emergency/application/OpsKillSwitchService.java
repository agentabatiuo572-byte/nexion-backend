package ffdd.opsconsole.emergency.application;


import lombok.RequiredArgsConstructor;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.common.api.OpsErrorCode;
import ffdd.opsconsole.common.boundary.ApplicationService;
import ffdd.opsconsole.emergency.dto.EmergencyConfigUpdateRequest;
import ffdd.opsconsole.emergency.dto.EmergencyDisableRequest;
import ffdd.opsconsole.emergency.dto.KillSwitchToggleRequest;
import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import ffdd.opsconsole.treasury.facade.TreasuryCoverageFacade;
import ffdd.opsconsole.treasury.facade.TreasuryCoverageSnapshot;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.util.StringUtils;

@ApplicationService
@RequiredArgsConstructor
public class OpsKillSwitchService {
    private static final List<String> ACTIVE_GATES = List.of("withdraw", "staking", "genesis", "exchange", "trial");
    private static final Set<String> RETIRED_GATES = Set.of("premium", "nexv2", "nex-v2", "points");
    private static final String GROUP_KILL_SWITCH = "admin_killswitch";
    private static final String GROUP_EMERGENCY = "admin_emergency";
    private static final String GROUP_AUTORULE = "admin_emergency_autorule";
    private static final DateTimeFormatter CHANGE_TIME = DateTimeFormatter.ofPattern("MM-dd HH:mm");

    private static final List<GateSeed> GATE_SEEDS = List.of(
            new GateSeed("withdraw", "提现闸", "withdraw", "提现申请 / 放行 / 链上广播", "提现出口一键止血", true, true, "immediate", "2d 前 · risk@nexion / super@nexion", "idle"),
            new GateSeed("staking", "质押闸", "staking", "Staking 新锁仓 / 高息档恢复", "恢复会增加未来利息负债", true, true, "delayed", "6d 前 · ops@nexion / super@nexion", "idle"),
            new GateSeed("genesis", "Genesis 闸", "genesis", "创世节点购买 / 二级市场", "恢复会放大节点分红和交易流", true, true, "delayed", "3h 前 · ops@nexion / super@nexion", "idle"),
            new GateSeed("exchange", "兑换闸", "exchange", "NEX ↔ USDT 兑换", "兑换出口一键止血", true, true, "immediate", "1d 前 · exchange@nexion / super@nexion", "idle"),
            new GateSeed("trial", "试用闸", "trial", "试用权益派发 / 续期", "不直接出钱但影响增长权益", false, false, "none", "5d 前 · growth@nexion / super@nexion", "idle"));

    private static final List<EmergencySlaSeed> EMERGENCY_SLA_SEEDS = List.of(
            new EmergencySlaSeed("confirmSlaMins", "执行门槛响应时限", "超过这个时间未处理,系统自动逐级往上呼叫", "15", "分钟", "number", true),
            new EmergencySlaSeed("escalateMaxMins", "升级呼叫总时限", "超过仍无人处理就关闭这张工单 · 避免无限挂起", "60", "分钟", "number", true),
            new EmergencySlaSeed("escalateMaxRounds", "最多往上呼叫几轮", "逐级升级呼叫的最大轮次", "4", "轮", "number", true),
            new EmergencySlaSeed("recoverGate", "恢复业务的备付金门槛", "备付金覆盖率达到这条线,才允许恢复会往外付钱的业务", "", "%", "number", false));

    private static final List<AutoRuleSeed> AUTO_RULE_SEEDS = List.of(
            new AutoRuleSeed("withdrawSurge", "提现激增 / 挤兑", "资金安全 · R1", "surge",
                    List.of("24h 提现申请额 ÷ 真实储备 > ", "40%", "(B5 挤兑红线)→ 自动熔断 ", "提现(+可选 兑换)", " · 30min 内值班补填理由"),
                    "触发阈值", "40%", false, "同 B5 挤兑红线", "阈值权威归 B5(bankrunRed,默认 40%),J1 引用不另持"),
            new AutoRuleSeed("maturityGap", "对账缺口", "资金安全 · R2", "gap",
                    List.of("充值对账 / 账本借贷不平缺口 > ", "$50K", " → 自动熔断 ", "兑换", "(停 NEX↔USDT 流出 · 待对账平)· 30min 补填理由"),
                    "触发阈值", "$50K", true, "", ""),
            new AutoRuleSeed("tamperCluster", "篡改告警激增", "风控 · R3", "shield",
                    List.of("单账户超 ", "告警阈值", " 或全域环比突增 → ", "仅自动告警 · 人工研判后手动熔断", "(J3 不持处置权)"),
                    "触发阈值", "10 次 / 24h", false, "同 J3 告警阈值", "阈值与 J3 告警阈值配置同源,调整在 J3 页头完成"),
            new AutoRuleSeed("regulatoryDirective", "监管指令", "合规 · R4", "clock",
                    List.of("监管点名 / 法务事件(事由必填)→ ", "人工经应急快速通道发起", "(机器不替监管判定)", "", ""),
                    "触发方式", "人工 · 应急快速通道", false, "", ""));

    private final PlatformConfigFacade configFacade;
    private final TreasuryCoverageFacade coverageFacade;
    private final AuditLogService auditLogService;

    public ApiResult<Map<String, Object>> matrix() {
        ensureSeedData();
        TreasuryCoverageSnapshot coverage = coverageFacade.snapshot();
        List<Map<String, Object>> gates = ACTIVE_GATES.stream().map(this::gateView).toList();
        long live = gates.stream().filter(gate -> Boolean.TRUE.equals(gate.get("enabled"))).count();
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("domain", "J1");
        response.put("activeGateCount", ACTIVE_GATES.size());
        response.put("activeGates", gates);
        response.put("retiredGates", retiredGates());
        response.put("coverage", Map.of(
                "coverageRatio", coverage.coverageRatio(),
                "redlinePct", coverage.redlinePct(),
                "yellowLinePct", coverage.redlinePct().add(BigDecimal.valueOf(25)),
                "recoveryAllowed", coverage.coverageRatio().compareTo(coverage.redlinePct()) >= 0));
        response.put("stats", Map.of(
                "liveGateCount", live,
                "killedGateCount", ACTIVE_GATES.size() - live,
                "emergencyProposalCount", gates.stream().filter(gate -> Boolean.TRUE.equals(gate.get("emergency"))).count(),
                "coverageBlockedCount", 0));
        response.put("emergencySla", emergencySlaRows(coverage));
        response.put("autoRules", autoRuleRows());
        response.put("executionModel", "single confirm-with-reason plus broadcast and A2 audit");
        response.put("sources", List.of("nx_config_item:killswitch.*", "nx_config_item:ops.J.emergency.*", "nx_config_item:emergency.autorule.*", "B1 treasury coverage facade"));
        return ApiResult.ok(response);
    }

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
        boolean before = gateEnabled(normalizedKey);
        GateSeed seed = gateSeed(normalizedKey);
        if (enable && !before && seed.coveragePrecheckRequired() && coverageBelowRedline()) {
            return coverageRedline();
        }
        writeGate(normalizedKey, enable);
        writeEmergencyFlag(normalizedKey, false);
        writeLastChange(normalizedKey, request.operator());
        audit("J1_KILLSWITCH_TOGGLED", normalizedKey, request.operator(), Map.of(
                "switchKey", normalizedKey,
                "before", before,
                "after", enable,
                "reason", request.reason().trim(),
                "idempotencyKey", idempotencyKey.trim(),
                "broadcast", true));
        Map<String, Object> response = matrix().getData();
        response.put("updated", Map.of("key", normalizedKey, "before", before, "after", enable));
        return ApiResult.ok(response);
    }

    public ApiResult<Map<String, Object>> emergencyDisable(String idempotencyKey, EmergencyDisableRequest request) {
        ApiResult<Map<String, Object>> guard = requireCommand(idempotencyKey, request == null ? null : request.reason());
        if (guard != null) {
            return guard;
        }
        if (request.keys() == null || request.keys().isEmpty()) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "KILL_SWITCH_KEYS_REQUIRED");
        }
        List<Map<String, Object>> changed = new ArrayList<>();
        for (String rawKey : request.keys()) {
            String key = normalizeGate(rawKey);
            if (isRetired(key)) {
                return retiredFeature();
            }
            boolean before = gateEnabled(key);
            writeGate(key, false);
            writeEmergencyFlag(key, true);
            writeLastChange(key, request.operator());
            changed.add(Map.of("key", key, "before", before, "after", false));
        }
        audit("J1_EMERGENCY_KILLSWITCH_TRIGGERED", "batch", request.operator(), Map.of(
                "changed", changed,
                "reason", request.reason().trim(),
                "idempotencyKey", idempotencyKey.trim(),
                "broadcast", true));
        Map<String, Object> response = matrix().getData();
        response.put("updated", changed);
        return ApiResult.ok(response);
    }

    public ApiResult<Map<String, Object>> updateEmergencySla(String paramKey, String idempotencyKey, EmergencyConfigUpdateRequest request) {
        ApiResult<Map<String, Object>> guard = requireCommand(idempotencyKey, request == null ? null : request.reason());
        if (guard != null) {
            return guard;
        }
        EmergencySlaSeed seed = emergencySlaSeed(paramKey);
        if (!seed.editable()) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "EMERGENCY_SLA_READONLY");
        }
        String value = normalizeConfigValue(request.value(), "EMERGENCY_SLA_VALUE_REQUIRED");
        configFacade.upsertAdminValue(emergencySlaConfigKey(seed.id()), value, "NUMBER", GROUP_EMERGENCY, "J1 emergency SLA parameter");
        audit("J1_EMERGENCY_SLA_CHANGED", seed.id(), request.operator(), Map.of(
                "paramKey", seed.id(),
                "value", value,
                "reason", request.reason().trim(),
                "idempotencyKey", idempotencyKey.trim()));
        Map<String, Object> response = matrix().getData();
        response.put("updated", Map.of("paramKey", seed.id(), "value", value));
        return ApiResult.ok(response);
    }

    public ApiResult<Map<String, Object>> updateAutoRule(String ruleId, String idempotencyKey, EmergencyConfigUpdateRequest request) {
        ApiResult<Map<String, Object>> guard = requireCommand(idempotencyKey, request == null ? null : request.reason());
        if (guard != null) {
            return guard;
        }
        AutoRuleSeed seed = autoRuleSeed(ruleId);
        if (!seed.adjustable()) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "AUTO_RULE_READONLY");
        }
        String value = normalizeConfigValue(request.value(), "AUTO_RULE_VALUE_REQUIRED");
        configFacade.upsertAdminValue(autoRuleConfigKey(seed.id()), value, "STRING", GROUP_AUTORULE, "J1 auto trigger rule threshold");
        audit("J1_AUTO_RULE_CHANGED", seed.id(), request.operator(), Map.of(
                "ruleId", seed.id(),
                "value", value,
                "reason", request.reason().trim(),
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
        gate.put("recoveryPrecheck", "B1_COVERAGE_REDLINE");
        gate.put("coveragePrecheckRequired", seed.coveragePrecheckRequired());
        gate.put("coverageImpactCategory", seed.coverageImpactCategory());
        gate.put("amplifies", seed.amplifies());
        gate.put("ownerDomain", "J1");
        gate.put("lastChange", activeValue(lastChangeConfigKey(key)).orElse(seed.lastChange()));
        gate.put("proposalStatus", seed.proposalStatus());
        gate.put("emergency", Boolean.parseBoolean(activeValue(emergencyFlagConfigKey(key)).orElse("false")));
        return gate;
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
        return null;
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

    private boolean coverageBelowRedline() {
        TreasuryCoverageSnapshot coverage = coverageFacade.snapshot();
        return coverage.coverageRatio().compareTo(coverage.redlinePct()) < 0;
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
        Optional<String> value = configFacade.activeValue(configKey(key))
                .or(() -> configFacade.activeValue(legacyConfigKey(key)));
        return value.map(raw -> {
                    String normalized = raw.trim().toLowerCase(Locale.ROOT);
                    return "enabled".equals(normalized) || "enable".equals(normalized) || "on".equals(normalized) || "true".equals(normalized) || "1".equals(normalized);
                })
                .orElse(true);
    }

    private void writeGate(String key, boolean enabled) {
        configFacade.upsertAdminValue(configKey(key), enabled ? "enabled" : "disabled", "STRING", GROUP_KILL_SWITCH, "J1 active kill switch");
    }

    private void writeEmergencyFlag(String key, boolean emergency) {
        configFacade.upsertAdminValue(emergencyFlagConfigKey(key), String.valueOf(emergency), "BOOLEAN", GROUP_KILL_SWITCH, "J1 emergency kill switch marker");
    }

    private void writeLastChange(String key, String operator) {
        String actor = StringUtils.hasText(operator) ? operator.trim() : "system";
        configFacade.upsertAdminValue(lastChangeConfigKey(key), "刚刚 · " + actor + " / 执行门槛", "STRING", GROUP_KILL_SWITCH, "J1 kill switch latest change");
    }

    private void ensureSeedData() {
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

    private void ensureConfig(String key, String value, String valueType, String group, String remark) {
        if (activeValue(key).isEmpty()) {
            configFacade.upsertAdminValue(key, value, valueType, group, remark);
        }
    }

    private Optional<String> activeValue(String configKey) {
        return configFacade.activeValue(configKey);
    }

    private List<Map<String, Object>> emergencySlaRows(TreasuryCoverageSnapshot coverage) {
        return EMERGENCY_SLA_SEEDS.stream().map(seed -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", seed.id());
            row.put("k", seed.name());
            row.put("d", seed.description());
            row.put("unit", seed.unit());
            row.put("kind", seed.valueType().toLowerCase(Locale.ROOT));
            row.put("editable", seed.editable());
            row.put("v", "recoverGate".equals(seed.id())
                    ? coverage.redlinePct().stripTrailingZeros().toPlainString()
                    : activeValue(emergencySlaConfigKey(seed.id())).orElse(seed.defaultValue()));
            row.put("source", "recoverGate".equals(seed.id()) ? "B1 treasury coverage" : emergencySlaConfigKey(seed.id()));
            return row;
        }).toList();
    }

    private List<Map<String, Object>> autoRuleRows() {
        return AUTO_RULE_SEEDS.stream().map(seed -> {
            String threshold = "tamperCluster".equals(seed.id())
                    ? activeValue("emergency.tamper.alert.threshold").map(value -> value + " 次 / 24h").orElse(activeValue(autoRuleConfigKey(seed.id())).orElse(seed.threshold()))
                    : activeValue(autoRuleConfigKey(seed.id())).orElse(seed.threshold());
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", seed.id());
            row.put("nm", seed.name());
            row.put("tag", seed.tag());
            row.put("icon", seed.icon());
            row.put("cond", seed.condition());
            row.put("thrK", seed.thresholdKey());
            row.put("thr", threshold);
            row.put("adjustable", seed.adjustable());
            row.put("refNote", seed.refNote());
            row.put("refTitle", seed.refTitle());
            row.put("configKey", autoRuleConfigKey(seed.id()));
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

    private String configKey(String key) {
        return "killswitch." + key;
    }

    private String legacyConfigKey(String key) {
        return "emergency.killswitch." + key;
    }

    private String emergencyFlagConfigKey(String key) {
        return "emergency.killswitch." + key + ".emergency";
    }

    private String lastChangeConfigKey(String key) {
        return "emergency.killswitch." + key + ".lastChange";
    }

    private String emergencySlaConfigKey(String paramKey) {
        return "ops.J.emergency." + paramKey;
    }

    private String autoRuleConfigKey(String ruleId) {
        return "emergency.autorule." + ruleId;
    }

    private void audit(String action, String switchKey, String operator, Map<String, Object> detail) {
        auditLogService.record(AuditLogWriteRequest.builder()
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

    private record GateSeed(String key, String name, String cap, String description, String recoveryNote,
                            boolean coveragePrecheckRequired, boolean amplifies, String coverageImpactCategory,
                            String lastChange, String proposalStatus) {
    }

    private record EmergencySlaSeed(String id, String name, String description, String defaultValue,
                                    String unit, String valueType, boolean editable) {
    }

    private record AutoRuleSeed(String id, String name, String tag, String icon, List<String> condition,
                                String thresholdKey, String threshold, boolean adjustable, String refNote,
                                String refTitle) {
    }
}
