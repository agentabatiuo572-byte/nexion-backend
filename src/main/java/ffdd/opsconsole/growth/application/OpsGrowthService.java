package ffdd.opsconsole.growth.application;


import lombok.RequiredArgsConstructor;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.common.api.OpsErrorCode;
import ffdd.opsconsole.common.boundary.ApplicationService;
import ffdd.opsconsole.growth.dto.GrowthConfigUpdateRequest;
import ffdd.opsconsole.growth.dto.GrowthEarnMilestoneUpdateRequest;
import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import ffdd.opsconsole.treasury.facade.TreasuryCoverageFacade;
import ffdd.opsconsole.treasury.facade.TreasuryCoverageSnapshot;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.util.StringUtils;

@ApplicationService
@RequiredArgsConstructor
public class OpsGrowthService {
    private static final String PHASE_CONFIG_KEY = "platform.phase.config";
    private static final String CURRENT_MONTH_KEY = "growth.phase.current_month";
    private static final String CURRENT_PHASE_KEY = "growth.phase.current";
    private static final String MONTH_DIAL_PREFIX = "growth.phase.month.";
    private static final String CONTROL_PREFIX = "growth.phase.control.";
    private static final String OVERRIDE_PREFIX = "growth.phase.override.";
    private static final String CHECKIN_REWARD_KEY = "growth.checkin.reward_nex";
    private static final String CHECKIN_STREAK_BONUS_KEY = "growth.checkin.streak_bonus_nex";
    private static final String CHECKIN_LUCKY_MULTIPLIER_KEY = "growth.checkin.lucky_multiplier_max";
    private static final String CHECKIN_LUCKY_15_PCT_KEY = "growth.checkin.lucky_1_5_pct";
    private static final String CHECKIN_LUCKY_2_PCT_KEY = "growth.checkin.lucky_2_pct";
    private static final String CHECKIN_BROKEN_HOURS_KEY = "growth.checkin.broken_hours";
    private static final String CHECKIN_REVIVE_CARDS_KEY = "growth.checkin.revive_cards";
    private static final String CHECKIN_STREAK_MS_PREFIX = "growth.checkin.streak_milestone.";
    private static final String CHECKIN_POWER_UP_PREFIX = "growth.checkin.power_up.";
    private static final String EARN_MS_PREFIX = "growth.earn_milestone.";
    private static final String EARN_TICK_INTERVAL_KEY = "growth.earn_milestone.tick_interval_seconds";
    private static final String WITHDRAW_MIN_BALANCE_KEY = "growth.withdraw_nex_gate.min_balance_nex";
    private static final String WITHDRAW_HOLD_DAYS_KEY = "growth.withdraw_nex_gate.hold_days";
    private static final String WITHDRAW_MIN_BALANCE_MIRROR_KEY = "withdrawal.nex_gate.min_balance_nex";
    private static final String WITHDRAW_HOLD_DAYS_MIRROR_KEY = "withdrawal.nex_gate.hold_days";
    private static final String TRIAL_PARAM_PREFIX = "growth.trial.param.";
    private static final String TRIAL_SESSION_PREFIX = "growth.trial.session.";
    private static final String TRIAL_AUTO_PUSH_KILLED_KEY = "growth.trial.auto_push_killed";
    private static final String QUEST_PREFIX = "growth.quest.";
    private static final String EVENT_PREFIX = "growth.event.";
    private static final String WHEEL_PREFIX = "growth.wheel.";
    private static final Set<String> RETIRED_KEYS = Set.of(
            "withdrawPointsRatio",
            "pointsExchangeRate",
            "premiumUnlock",
            "premiumTrial",
            "nexV2Unlock",
            "nexV2LockReward");
    private static final List<String> PHASE_DIAL_KEYS = List.of(
            "inviteRewardMultiplier",
            "questRewardMultiplier",
            "trialOffsetCapUsdt",
            "deviceReleasePacingPct",
            "commissionTighteningPct",
            "campaignRewardNex",
            "withdrawNexMinBalance",
            "withdrawNexHoldDays");
    private static final Set<String> PHASE_CONTROL_KEYS = Set.of("schedule", "pin", "override");
    private static final List<String> TRIAL_PARAM_KEYS = List.of(
            "days",
            "price",
            "shadow",
            "offsetCap",
            "disc",
            "hq",
            "failRate",
            "trialCooldown",
            "push",
            "autoCharge");
    private static final Set<String> TRIAL_TERMINAL_STATES = Set.of("cancelled", "redeemed");
    private static final Set<String> EVENT_STATES = Set.of("upcoming", "ongoing", "ended");
    private static final Set<String> WHEEL_GUARD_KEYS = Set.of("budget", "cap", "kill");

    private final PlatformConfigFacade configFacade;
    private final TreasuryCoverageFacade coverageFacade;
    private final AuditLogService auditLogService;
    private final ObjectMapper objectMapper;

    public ApiResult<Map<String, Object>> phases() {
        int currentMonth = currentMonth();
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("domain", "H1");
        response.put("currentMonth", currentMonth);
        response.put("currentPhase", configFacade.activeValue(CURRENT_PHASE_KEY).orElse(phaseForMonth(currentMonth)));
        response.put("dialCount", 8);
        response.put("phaseConfig", phaseConfig());
        response.put("activeDials", activeDials());
        response.put("monthlyDials", monthlyDials(currentMonth));
        response.put("controls", phaseControls());
        response.put("overrides", phaseOverrides());
        response.put("attribution", phaseAttribution());
        response.put("coverage", coverage());
        response.put("withdrawNexGate", withdrawGate().getData());
        response.put("retiredDials", List.copyOf(RETIRED_KEYS));
        response.put("sunsetExclusions", List.of("Premium", "NEX v2", "Points"));
        response.put("sources", List.of("nx_config_item:" + PHASE_CONFIG_KEY, "nx_config_item:growth.*"));
        return ApiResult.ok(response);
    }

    public ApiResult<Map<String, Object>> phaseSandboxPreview() {
        int currentMonth = currentMonth();
        int nextMonth = Math.min(12, currentMonth + 1);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("domain", "H1");
        response.put("mode", "READ_ONLY_SANDBOX");
        response.put("currentMonth", currentMonth);
        response.put("currentPhase", configFacade.activeValue(CURRENT_PHASE_KEY).orElse(phaseForMonth(currentMonth)));
        response.put("coverage", coverage());
        response.put("withdrawNexGate", withdrawGate().getData());
        response.put("nextMonthDials", monthlyDials(currentMonth).get(nextMonth - 1));
        response.put("impactMatrix", List.of(
                impact("D5", "提现 NEX 闸镜像", "withdrawNexMinBalance / withdrawNexHoldDays 变更会同步 D5 参数口径"),
                impact("B1", "兑付覆盖率红线", "放松提现、奖励或活动流出方向时低于红线返回 422"),
                impact("F3", "佣金与团队结算", "commissionTighteningPct 影响双轨结算出账速度"),
                impact("E2", "设备发布节奏", "deviceReleasePacingPct 影响 Gen/SKU 供给节奏"),
                impact("J1", "Kill Switch", "红线或事故期恢复会付钱业务前必须先看 J1 闸门")));
        response.put("retiredDials", List.copyOf(RETIRED_KEYS));
        response.put("writes", false);
        response.put("sources", List.of("nx_config_item:growth.*", "treasury.coverage.snapshot", "withdraw_nex_gate"));
        return ApiResult.ok(response);
    }

    public ApiResult<Map<String, Object>> updatePhaseDial(
            String idempotencyKey,
            String dialKey,
            GrowthConfigUpdateRequest request) {
        ApiResult<Map<String, Object>> guard = requireCommand(idempotencyKey, request == null ? null : request.reason());
        if (guard != null) {
            return guard;
        }
        String normalizedKey = normalizePhaseDialKey(dialKey);
        if (RETIRED_KEYS.contains(normalizedKey)) {
            return retiredFeature();
        }
        String configKey = phaseDialConfigKey(normalizedKey);
        BigDecimal oldValue = configDecimal(configKey, defaultPhaseDialValue(normalizedKey));
        BigDecimal newValue = normalizePhaseDialValue(normalizedKey, request.value());
        if (amplifiesPhasePayout(normalizedKey, oldValue, newValue) && coverageBelowRedline()) {
            return coverageRedline();
        }
        writeActivePhaseDial(normalizedKey, newValue);
        audit("H1_PHASE_DIAL_CHANGED", "GROWTH_PHASE_DIAL", configKey, request.operator(), Map.of(
                "key", normalizedKey,
                "oldValue", oldValue,
                "newValue", newValue,
                "reason", request.reason().trim(),
                "idempotencyKey", idempotencyKey.trim()));
        return phases();
    }

    public ApiResult<Map<String, Object>> updatePhaseMonthDial(
            String idempotencyKey,
            int month,
            String dialKey,
            GrowthConfigUpdateRequest request) {
        ApiResult<Map<String, Object>> guard = requireCommand(idempotencyKey, request == null ? null : request.reason());
        if (guard != null) {
            return guard;
        }
        if (month < 1 || month > 12) {
            return validation("MONTH_OUT_OF_RANGE");
        }
        String normalizedKey = normalizePhaseDialKey(dialKey);
        if (RETIRED_KEYS.contains(normalizedKey)) {
            return retiredFeature();
        }
        String configKey = monthDialConfigKey(month, normalizedKey);
        BigDecimal oldValue = configDecimal(configKey, defaultPhaseMonthDialValue(month, normalizedKey));
        BigDecimal newValue = normalizePhaseDialValue(normalizedKey, request.value());
        if (amplifiesPhasePayout(normalizedKey, oldValue, newValue) && coverageBelowRedline()) {
            return coverageRedline();
        }
        configFacade.upsertAdminValue(configKey, newValue.toPlainString(), "NUMBER", "growth", "H1 monthly phase dial");
        boolean currentMonth = month == currentMonth();
        if (currentMonth) {
            writeActivePhaseDial(normalizedKey, newValue);
        }
        audit("H1_MONTH_DIAL_CHANGED", "GROWTH_PHASE_MONTH_DIAL", configKey, request.operator(), Map.of(
                "month", month,
                "key", normalizedKey,
                "oldValue", oldValue,
                "newValue", newValue,
                "currentMonth", currentMonth,
                "reason", request.reason().trim(),
                "idempotencyKey", idempotencyKey.trim()));
        return phases();
    }

    public ApiResult<Map<String, Object>> updatePhaseControl(
            String idempotencyKey,
            String controlKey,
            GrowthConfigUpdateRequest request) {
        ApiResult<Map<String, Object>> guard = requireCommand(idempotencyKey, request == null ? null : request.reason());
        if (guard != null) {
            return guard;
        }
        String normalizedKey = requireText(controlKey, "Phase control key is required");
        if (!PHASE_CONTROL_KEYS.contains(normalizedKey)) {
            return validation("PHASE_CONTROL_KEY_INVALID");
        }
        if (!StringUtils.hasText(request.value())) {
            return validation("VALUE_REQUIRED");
        }
        String value = request.value().trim();
        String configKey = CONTROL_PREFIX + normalizedKey;
        configFacade.upsertAdminValue(configKey, value, "STRING", "growth", "H1 phase control");
        audit("H1_PHASE_CONTROL_CHANGED", "GROWTH_PHASE_CONTROL", configKey, request.operator(), Map.of(
                "key", normalizedKey,
                "value", value,
                "reason", request.reason().trim(),
                "idempotencyKey", idempotencyKey.trim()));
        return phases();
    }

    public ApiResult<Map<String, Object>> updatePhaseOverride(
            String idempotencyKey,
            String overrideId,
            GrowthConfigUpdateRequest request) {
        ApiResult<Map<String, Object>> guard = requireCommand(idempotencyKey, request == null ? null : request.reason());
        if (guard != null) {
            return guard;
        }
        String normalizedId = requireText(overrideId, "Phase override id is required");
        if (!normalizedId.matches("[A-Za-z0-9_-]+")) {
            return validation("PHASE_OVERRIDE_ID_INVALID");
        }
        Boolean disabled = parseBooleanValue(request.value());
        if (disabled == null) {
            return validation("BOOLEAN_VALUE_INVALID");
        }
        String configKey = OVERRIDE_PREFIX + normalizedId + ".disabled";
        configFacade.upsertAdminValue(configKey, disabled.toString(), "BOOLEAN", "growth", "H1 phase override state");
        audit("H1_PHASE_OVERRIDE_CHANGED", "GROWTH_PHASE_OVERRIDE", configKey, request.operator(), Map.of(
                "overrideId", normalizedId,
                "disabled", disabled,
                "reason", request.reason().trim(),
                "idempotencyKey", idempotencyKey.trim()));
        return phases();
    }

    public ApiResult<Map<String, Object>> trials() {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("domain", "H2");
        response.put("stats", trialStats());
        response.put("modelA", trialModelA());
        response.put("params", trialParams());
        response.put("gates", trialGates());
        response.put("states", trialStates());
        response.put("sessions", trialSessions());
        response.put("autoPushKilled", trialAutoPushKilled());
        response.put("serverOnlyFields", List.of("failRate"));
        response.put("coverage", coverage());
        response.put("sources", List.of(
                "nx_config_item:" + TRIAL_PARAM_PREFIX + "*",
                "nx_config_item:" + TRIAL_SESSION_PREFIX + "*",
                "nx_config_item:" + TRIAL_AUTO_PUSH_KILLED_KEY));
        return ApiResult.ok(response);
    }

    public ApiResult<Map<String, Object>> updateTrialParam(
            String idempotencyKey,
            String paramKey,
            GrowthConfigUpdateRequest request) {
        ApiResult<Map<String, Object>> guard = requireCommand(idempotencyKey, request == null ? null : request.reason());
        if (guard != null) {
            return guard;
        }
        String key = normalizeTrialParamKey(paramKey);
        String value;
        try {
            value = normalizeTrialParamValue(key, request.value());
        } catch (IllegalArgumentException ex) {
            return validation(ex.getMessage());
        }
        String configKey = trialParamConfigKey(key);
        boolean serverOnly = "failRate".equals(key);
        configFacade.upsertAdminValue(configKey, value, serverOnly ? "NUMBER" : "STRING", "growth", "H2 trial parameter");
        audit("H2_TRIAL_PARAM_CHANGED", "TRIAL_PARAM", configKey, request.operator(), Map.of(
                "key", key,
                "newValue", serverOnly ? "MASKED_SERVER_ONLY" : value,
                "serverOnly", serverOnly,
                "reason", request.reason().trim(),
                "idempotencyKey", idempotencyKey.trim()));
        Map<String, Object> response = trials().getData();
        response.put("updated", Map.of(
                "key", key,
                "configKey", configKey,
                "value", serverOnly ? "•••(server only)" : value,
                "serverOnly", serverOnly));
        return ApiResult.ok(response);
    }

    public ApiResult<Map<String, Object>> cancelTrialSession(
            String idempotencyKey,
            String sessionId,
            GrowthConfigUpdateRequest request) {
        ApiResult<Map<String, Object>> guard = requireCommand(idempotencyKey, request == null ? null : request.reason());
        if (guard != null) {
            return guard;
        }
        String sid = normalizeTrialSessionId(sessionId);
        String state = trialSessionState(sid);
        if (TRIAL_TERMINAL_STATES.contains(state)) {
            return invalidState("TRIAL_SESSION_ALREADY_TERMINAL");
        }
        String stateKey = trialSessionConfigKey(sid, "state");
        String timeKey = trialSessionConfigKey(sid, "cancelled_at");
        configFacade.upsertAdminValue(stateKey, "cancelled", "STRING", "growth", "H2 trial session state");
        configFacade.upsertAdminValue(timeKey, Instant.now().toString(), "STRING", "growth", "H2 trial session cancelled time");
        audit("H2_TRIAL_SESSION_CANCELLED", "TRIAL_SESSION", sid, request.operator(), Map.of(
                "sessionId", sid,
                "oldState", state,
                "newState", "cancelled",
                "reason", request.reason().trim(),
                "idempotencyKey", idempotencyKey.trim()));
        Map<String, Object> response = trials().getData();
        response.put("updated", Map.of("sessionId", sid, "state", "cancelled"));
        return ApiResult.ok(response);
    }

    public ApiResult<Map<String, Object>> chargeTrialSession(
            String idempotencyKey,
            String sessionId,
            GrowthConfigUpdateRequest request) {
        ApiResult<Map<String, Object>> guard = requireCommand(idempotencyKey, request == null ? null : request.reason());
        if (guard != null) {
            return guard;
        }
        String sid = normalizeTrialSessionId(sessionId);
        String state = trialSessionState(sid);
        if (TRIAL_TERMINAL_STATES.contains(state)) {
            return invalidState("TRIAL_SESSION_ALREADY_TERMINAL");
        }
        String stateKey = trialSessionConfigKey(sid, "state");
        String timeKey = trialSessionConfigKey(sid, "charged_at");
        configFacade.upsertAdminValue(stateKey, "redeemed", "STRING", "growth", "H2 trial session state");
        configFacade.upsertAdminValue(timeKey, Instant.now().toString(), "STRING", "growth", "H2 trial session charged time");
        audit("H2_TRIAL_SESSION_CHARGED", "TRIAL_SESSION", sid, request.operator(), Map.of(
                "sessionId", sid,
                "oldState", state,
                "newState", "redeemed",
                "reason", request.reason().trim(),
                "idempotencyKey", idempotencyKey.trim()));
        Map<String, Object> response = trials().getData();
        response.put("updated", Map.of("sessionId", sid, "state", "redeemed"));
        return ApiResult.ok(response);
    }

    public ApiResult<Map<String, Object>> killTrialAutoPush(
            String idempotencyKey,
            GrowthConfigUpdateRequest request) {
        ApiResult<Map<String, Object>> guard = requireCommand(idempotencyKey, request == null ? null : request.reason());
        if (guard != null) {
            return guard;
        }
        configFacade.upsertAdminValue(TRIAL_AUTO_PUSH_KILLED_KEY, "true", "BOOLEAN", "growth", "H2 auto-push kill switch");
        audit("H2_TRIAL_AUTO_PUSH_KILLED", "TRIAL_AUTO_PUSH", TRIAL_AUTO_PUSH_KILLED_KEY, request.operator(), Map.of(
                "killed", true,
                "reason", request.reason().trim(),
                "idempotencyKey", idempotencyKey.trim()));
        Map<String, Object> response = trials().getData();
        response.put("autoPushKilled", true);
        response.put("updated", Map.of("configKey", TRIAL_AUTO_PUSH_KILLED_KEY, "killed", true));
        return ApiResult.ok(response);
    }

    public ApiResult<Map<String, Object>> questEvents() {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("domain", "H3_H4");
        response.put("rewardAsset", "NEX");
        response.put("h3Stats", questStats());
        response.put("h4Stats", eventStats());
        response.put("dayOneWindow", questConfig("dayOne.windowMs"));
        response.put("dayOneTriReward", questConfig("dayOne.triReward"));
        response.put("dayOneTasks", dayOneTasks());
        response.put("dayOneStates", dayOneStates());
        response.put("weeklyTier1", weeklyTier1());
        response.put("weeklyTier2", weeklyTier2());
        response.put("weeklyChampionBonus", questConfig("weekly.champBonus"));
        response.put("weeklyMultipliers", weeklyMultipliers());
        response.put("monthlyMissions", monthlyMissions());
        response.put("taskMonitor", taskMonitor());
        response.put("phaseMultiplierReadonly", phaseMultiplierReadonly());
        response.put("events", questEventsList());
        response.put("eventStates", eventStateLegend());
        response.put("wheelTiers", wheelTiers());
        response.put("wheelSignature", questConfig("wheel.pool"));
        response.put("wheelEvUsd", wheelEvUsd());
        response.put("wheelGuards", wheelGuards());
        response.put("trackables", trackables());
        response.put("coverage", coverage());
        response.put("pointsSystemStatus", "SUNSET_HISTORY_ONLY");
        response.put("disabledOutputs", List.of("Premium entitlement writes", "NEX v2 lock rewards", "Points ledger writes"));
        response.put("sources", List.of(
                "nx_config_item:" + QUEST_PREFIX + "*",
                "nx_config_item:" + EVENT_PREFIX + "*",
                "nx_config_item:" + WHEEL_PREFIX + "*"));
        return ApiResult.ok(response);
    }

    public ApiResult<Map<String, Object>> updateQuestConfig(
            String idempotencyKey,
            String configKey,
            GrowthConfigUpdateRequest request) {
        ApiResult<Map<String, Object>> guard = requireCommand(idempotencyKey, request == null ? null : request.reason());
        if (guard != null) {
            return guard;
        }
        String normalizedKey;
        try {
            normalizedKey = normalizeQuestConfigKey(configKey);
        } catch (IllegalArgumentException ex) {
            return validation(ex.getMessage());
        }
        String value;
        try {
            value = normalizeQuestValue(normalizedKey, request.value());
        } catch (IllegalArgumentException ex) {
            return validation(ex.getMessage());
        }
        String storageKey = questConfigStorageKey(normalizedKey);
        String oldValue = questConfig(normalizedKey);
        if (amplifiesQuestValue(normalizedKey, oldValue, value) && coverageBelowRedline()) {
            return coverageRedline();
        }
        configFacade.upsertAdminValue(storageKey, value, questValueType(normalizedKey), "growth", "H3/H4 quest and event config");
        audit(questAuditAction(normalizedKey), questResourceType(normalizedKey), storageKey, request.operator(), Map.of(
                "key", normalizedKey,
                "oldValue", oldValue,
                "newValue", value,
                "reason", request.reason().trim(),
                "idempotencyKey", idempotencyKey.trim()));
        Map<String, Object> response = questEvents().getData();
        response.put("updated", Map.of("key", normalizedKey, "configKey", storageKey, "value", value));
        return ApiResult.ok(response);
    }

    public ApiResult<Map<String, Object>> updateQuestEventReward(
            String idempotencyKey,
            String eventId,
            GrowthConfigUpdateRequest request) {
        ApiResult<Map<String, Object>> guard = requireCommand(idempotencyKey, request == null ? null : request.reason());
        if (guard != null) {
            return guard;
        }
        String id = normalizeEventId(eventId);
        String value;
        try {
            value = normalizePlainText(request.value(), 160);
        } catch (IllegalArgumentException ex) {
            return validation(ex.getMessage());
        }
        String configKey = EVENT_PREFIX + id + ".reward";
        String oldValue = eventReward(id);
        if (rewardFlow(value).compareTo(rewardFlow(oldValue)) > 0 && coverageBelowRedline()) {
            return coverageRedline();
        }
        configFacade.upsertAdminValue(configKey, value, "STRING", "growth", "H4 event reward");
        audit("H4_EVENT_REWARD_CHANGED", "GROWTH_EVENT", id, request.operator(), Map.of(
                "eventId", id,
                "oldValue", oldValue,
                "newValue", value,
                "reason", request.reason().trim(),
                "idempotencyKey", idempotencyKey.trim()));
        Map<String, Object> response = questEvents().getData();
        response.put("updated", Map.of("eventId", id, "field", "reward", "value", value));
        return ApiResult.ok(response);
    }

    public ApiResult<Map<String, Object>> updateQuestEventStatus(
            String idempotencyKey,
            String eventId,
            GrowthConfigUpdateRequest request) {
        ApiResult<Map<String, Object>> guard = requireCommand(idempotencyKey, request == null ? null : request.reason());
        if (guard != null) {
            return guard;
        }
        String id = normalizeEventId(eventId);
        String status = requireText(request.value(), "Event status is required").toLowerCase(Locale.ROOT);
        if (!EVENT_STATES.contains(status)) {
            return validation("EVENT_STATUS_INVALID");
        }
        String configKey = EVENT_PREFIX + id + ".status";
        String oldStatus = eventStatus(id);
        configFacade.upsertAdminValue(configKey, status, "STRING", "growth", "H4 event status");
        if ("ended".equals(status) && eventFeatured(id)) {
            configFacade.upsertAdminValue(EVENT_PREFIX + id + ".featured", "false", "BOOLEAN", "growth", "H4 event featured");
        }
        audit("H4_EVENT_STATUS_CHANGED", "GROWTH_EVENT", id, request.operator(), Map.of(
                "eventId", id,
                "oldStatus", oldStatus,
                "newStatus", status,
                "reason", request.reason().trim(),
                "idempotencyKey", idempotencyKey.trim()));
        Map<String, Object> response = questEvents().getData();
        response.put("updated", Map.of("eventId", id, "field", "status", "value", status));
        return ApiResult.ok(response);
    }

    public ApiResult<Map<String, Object>> updateQuestEventFeatured(
            String idempotencyKey,
            String eventId,
            GrowthConfigUpdateRequest request) {
        ApiResult<Map<String, Object>> guard = requireCommand(idempotencyKey, request == null ? null : request.reason());
        if (guard != null) {
            return guard;
        }
        String id = normalizeEventId(eventId);
        Boolean featured = parseBooleanValue(request.value());
        if (featured == null) {
            return validation("BOOLEAN_VALUE_INVALID");
        }
        if (featured && !"ongoing".equals(eventStatus(id))) {
            return validation("EVENT_FEATURED_REQUIRES_ONGOING");
        }
        if (featured) {
            Optional<Map<String, Object>> currentFeatured = questEventsList().stream()
                    .filter(row -> !id.equals(row.get("id")))
                    .filter(row -> Boolean.TRUE.equals(row.get("featured")))
                    .filter(row -> "ongoing".equals(row.get("state")))
                    .findFirst();
            if (currentFeatured.isPresent()) {
                return validation("EVENT_FEATURED_UNIQUE_VIOLATION");
            }
        }
        String configKey = EVENT_PREFIX + id + ".featured";
        configFacade.upsertAdminValue(configKey, featured.toString(), "BOOLEAN", "growth", "H4 event featured");
        audit("H4_EVENT_FEATURED_CHANGED", "GROWTH_EVENT", id, request.operator(), Map.of(
                "eventId", id,
                "featured", featured,
                "reason", request.reason().trim(),
                "idempotencyKey", idempotencyKey.trim()));
        Map<String, Object> response = questEvents().getData();
        response.put("updated", Map.of("eventId", id, "field", "featured", "value", featured));
        return ApiResult.ok(response);
    }

    public ApiResult<Map<String, Object>> checkIn() {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("domain", "H5");
        response.put("rewardAsset", "NEX");
        response.put("baseRewardNex", configDecimal(CHECKIN_REWARD_KEY, new BigDecimal("2")));
        response.put("streakBonusNex", configDecimal(CHECKIN_STREAK_BONUS_KEY, new BigDecimal("5")));
        response.put("luckyMultiplierMax", configDecimal(CHECKIN_LUCKY_MULTIPLIER_KEY, new BigDecimal("2")));
        response.put("stats", checkInStats());
        response.put("rules", checkInRules());
        response.put("streakMilestones", streakMilestones());
        response.put("streakDistribution", streakDistribution());
        response.put("powerUps", powerUps());
        response.put("earnMilestones", earnMilestones());
        response.put("tickInterval", tickInterval());
        response.put("coverage", coverage());
        response.put("pointsSystemStatus", "SUNSET_HISTORY_ONLY");
        response.put("disabledOutputs", List.of("Points ledger writes", "points redemption", "premium trial points"));
        response.put("sources", List.of(
                "nx_config_item:" + CHECKIN_REWARD_KEY,
                "nx_config_item:" + CHECKIN_STREAK_BONUS_KEY,
                "nx_config_item:" + CHECKIN_STREAK_MS_PREFIX + "*",
                "nx_config_item:" + CHECKIN_POWER_UP_PREFIX + "*",
                "nx_config_item:" + EARN_MS_PREFIX + "*"));
        return ApiResult.ok(response);
    }

    public ApiResult<Map<String, Object>> updateCheckIn(String idempotencyKey, GrowthConfigUpdateRequest request) {
        ApiResult<Map<String, Object>> guard = requireCommand(idempotencyKey, request == null ? null : request.reason());
        if (guard != null) {
            return guard;
        }
        String key = normalizeCheckInKey(request.key());
        BigDecimal oldValue = currentCheckInValue(key);
        BigDecimal newValue = normalizeCheckInValue(key, request.value());
        if (newValue.compareTo(oldValue) > 0 && coverageBelowRedline()) {
            return coverageRedline();
        }
        String configKey = checkInConfigKey(key);
        configFacade.upsertAdminValue(configKey, newValue.toPlainString(), "NUMBER", "growth", "H5 check-in NEX reward");
        audit("H5_CHECKIN_NEX_CONFIG_CHANGED", "CHECKIN_NEX_REWARD", configKey, request.operator(), Map.of(
                "key", key,
                "oldValue", oldValue,
                "newValue", newValue,
                "reason", request.reason().trim(),
                "idempotencyKey", idempotencyKey.trim()));
        Map<String, Object> response = checkIn().getData();
        response.put("updated", Map.of("key", key, "configKey", configKey, "oldValue", oldValue, "newValue", newValue));
        return ApiResult.ok(response);
    }

    public ApiResult<Map<String, Object>> updateCheckInRule(
            String idempotencyKey,
            String ruleKey,
            GrowthConfigUpdateRequest request) {
        ApiResult<Map<String, Object>> guard = requireCommand(idempotencyKey, request == null ? null : request.reason());
        if (guard != null) {
            return guard;
        }
        String key = normalizeCheckInRuleKey(ruleKey);
        String value = requireText(request.value(), "value is required");
        if (Set.of("baseline", "bonus7", "p15", "p2", "broken").contains(key)) {
            BigDecimal oldValue = currentCheckInRuleNumber(key);
            BigDecimal newValue = normalizeCheckInRuleNumber(key, value);
            if (("p15".equals(key) || "p2".equals(key)) && luckyProbabilitySum(key, newValue).compareTo(new BigDecimal("100")) > 0) {
                return validation("LUCKY_PROBABILITY_SUM_EXCEEDS_100");
            }
            if (amplifiesCheckInRule(key, oldValue, newValue) && coverageBelowRedline()) {
                return coverageRedline();
            }
            value = newValue.toPlainString();
        }
        String configKey = checkInRuleConfigKey(key);
        configFacade.upsertAdminValue(configKey, value, checkInRuleValueType(key), "growth", "H5 check-in rule");
        audit("H5_CHECKIN_RULE_CHANGED", "CHECKIN_RULE", configKey, request.operator(), Map.of(
                "key", key,
                "value", value,
                "reason", request.reason().trim(),
                "idempotencyKey", idempotencyKey.trim()));
        Map<String, Object> response = checkIn().getData();
        response.put("updated", Map.of("key", key, "configKey", configKey, "value", value));
        return ApiResult.ok(response);
    }

    public ApiResult<Map<String, Object>> updateStreakMilestone(
            String idempotencyKey,
            int milestoneId,
            GrowthConfigUpdateRequest request) {
        ApiResult<Map<String, Object>> guard = requireCommand(idempotencyKey, request == null ? null : request.reason());
        if (guard != null) {
            return guard;
        }
        if (milestoneId < 0 || milestoneId >= defaultStreakMilestones().size()) {
            return validation("STREAK_MILESTONE_ID_INVALID");
        }
        String reward = requireText(request.value(), "reward is required");
        BigDecimal oldFlow = rewardFlow(defaultStreakMilestone(milestoneId).get("reward").toString());
        BigDecimal newFlow = rewardFlow(reward);
        if (newFlow.compareTo(oldFlow) > 0 && coverageBelowRedline()) {
            return coverageRedline();
        }
        String configKey = CHECKIN_STREAK_MS_PREFIX + milestoneId + ".reward";
        configFacade.upsertAdminValue(configKey, reward, "STRING", "growth", "H5 streak milestone reward");
        audit("H5_STREAK_MILESTONE_CHANGED", "STREAK_MILESTONE", configKey, request.operator(), Map.of(
                "milestoneId", milestoneId,
                "reward", reward,
                "reason", request.reason().trim(),
                "idempotencyKey", idempotencyKey.trim()));
        Map<String, Object> response = checkIn().getData();
        response.put("updated", Map.of("milestoneId", milestoneId, "configKey", configKey, "reward", reward));
        return ApiResult.ok(response);
    }

    public ApiResult<Map<String, Object>> updatePowerUp(
            String idempotencyKey,
            int powerUpId,
            GrowthConfigUpdateRequest request) {
        ApiResult<Map<String, Object>> guard = requireCommand(idempotencyKey, request == null ? null : request.reason());
        if (guard != null) {
            return guard;
        }
        if (powerUpId < 0 || powerUpId >= defaultPowerUps().size()) {
            return validation("POWER_UP_ID_INVALID");
        }
        String key = requireText(request.key(), "Power-up key is required");
        if (!Set.of("day", "note").contains(key)) {
            return validation("POWER_UP_KEY_INVALID");
        }
        String value = requireText(request.value(), "value is required");
        if ("day".equals(key)) {
            BigDecimal oldDay = configDecimal(CHECKIN_POWER_UP_PREFIX + powerUpId + ".day",
                    new BigDecimal(defaultPowerUp(powerUpId).get("day").toString()));
            BigDecimal newDay = bounded(parseDecimal(value), BigDecimal.ONE, new BigDecimal("365"));
            if (newDay.compareTo(oldDay) > 0 && coverageBelowRedline()) {
                return coverageRedline();
            }
            value = newDay.setScale(0, RoundingMode.DOWN).toPlainString();
        }
        String configKey = CHECKIN_POWER_UP_PREFIX + powerUpId + "." + key;
        configFacade.upsertAdminValue(configKey, value, "day".equals(key) ? "NUMBER" : "STRING", "growth", "H5 power-up");
        audit("H5_POWER_UP_CHANGED", "POWER_UP", configKey, request.operator(), Map.of(
                "powerUpId", powerUpId,
                "key", key,
                "value", value,
                "reason", request.reason().trim(),
                "idempotencyKey", idempotencyKey.trim()));
        Map<String, Object> response = checkIn().getData();
        response.put("updated", Map.of("powerUpId", powerUpId, "configKey", configKey, "value", value));
        return ApiResult.ok(response);
    }

    public ApiResult<Map<String, Object>> updateEarnMilestone(
            String idempotencyKey,
            String milestoneKey,
            GrowthEarnMilestoneUpdateRequest request) {
        ApiResult<Map<String, Object>> guard = requireCommand(idempotencyKey, request == null ? null : request.reason());
        if (guard != null) {
            return guard;
        }
        String key = normalizeEarnMilestoneKey(milestoneKey);
        BigDecimal oldThreshold = earnMilestoneThreshold(key);
        BigDecimal oldReward = earnMilestoneReward(key);
        BigDecimal newThreshold = bounded(request.thresholdUsd(), BigDecimal.ONE, new BigDecimal("100000000"));
        BigDecimal newReward = bounded(request.rewardNex(), BigDecimal.ZERO, new BigDecimal("100000000"));
        if (!earnMilestonesRemainOrdered(key, newThreshold)) {
            return validation("EARN_MILESTONE_THRESHOLD_ORDER_INVALID");
        }
        if ((newReward.compareTo(oldReward) > 0 || newThreshold.compareTo(oldThreshold) < 0) && coverageBelowRedline()) {
            return coverageRedline();
        }
        String thresholdKey = earnThresholdConfigKey(key);
        String rewardKey = earnRewardConfigKey(key);
        configFacade.upsertAdminValue(thresholdKey, newThreshold.toPlainString(), "NUMBER", "growth", "H6 earn milestone");
        configFacade.upsertAdminValue(rewardKey, newReward.toPlainString(), "NUMBER", "growth", "H6 earn milestone");
        audit("H6_EARN_MILESTONE_CHANGED", "EARN_MILESTONE", key, request.operator(), Map.of(
                "key", key,
                "oldThresholdUsd", oldThreshold,
                "newThresholdUsd", newThreshold,
                "oldRewardNex", oldReward,
                "newRewardNex", newReward,
                "reason", request.reason().trim(),
                "idempotencyKey", idempotencyKey.trim()));
        Map<String, Object> response = checkIn().getData();
        response.put("updated", Map.of("key", key, "thresholdKey", thresholdKey, "rewardKey", rewardKey));
        return ApiResult.ok(response);
    }

    public ApiResult<Map<String, Object>> updateEarnMilestoneTickInterval(
            String idempotencyKey,
            GrowthConfigUpdateRequest request) {
        ApiResult<Map<String, Object>> guard = requireCommand(idempotencyKey, request == null ? null : request.reason());
        if (guard != null) {
            return guard;
        }
        BigDecimal seconds;
        try {
            seconds = bounded(parseDecimal(request.value()), BigDecimal.ONE, new BigDecimal("60"));
        } catch (IllegalArgumentException ex) {
            return validation("TICK_INTERVAL_OUT_OF_RANGE");
        }
        String value = String.valueOf(seconds.setScale(0, RoundingMode.DOWN).intValue());
        configFacade.upsertAdminValue(EARN_TICK_INTERVAL_KEY, value, "NUMBER", "growth", "H6 cascade tick interval");
        audit("H6_TICK_INTERVAL_CHANGED", "EARN_MILESTONE_TICK", EARN_TICK_INTERVAL_KEY, request.operator(), Map.of(
                "seconds", value,
                "reason", request.reason().trim(),
                "idempotencyKey", idempotencyKey.trim()));
        Map<String, Object> response = checkIn().getData();
        response.put("updated", Map.of("configKey", EARN_TICK_INTERVAL_KEY, "seconds", value));
        return ApiResult.ok(response);
    }

    public ApiResult<Map<String, Object>> withdrawGate() {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("domain", "D5_H1");
        response.put("asset", "NEX");
        response.put("minBalanceNex", configDecimal(WITHDRAW_MIN_BALANCE_KEY, new BigDecimal("100")));
        response.put("holdDays", configDecimal(WITHDRAW_HOLD_DAYS_KEY, new BigDecimal("7")).intValue());
        response.put("canonicalOwner", "H1");
        response.put("mirrors", List.of("D5 withdrawal parameters", "D2 withdrawal queue readonly"));
        response.put("retiredReplacement", "withdrawPointsRatio -> withdrawNexGate");
        response.put("sources", List.of("nx_config_item:" + WITHDRAW_MIN_BALANCE_KEY, "nx_config_item:" + WITHDRAW_HOLD_DAYS_KEY));
        return ApiResult.ok(response);
    }

    public ApiResult<Map<String, Object>> updateWithdrawGate(String idempotencyKey, GrowthConfigUpdateRequest request) {
        ApiResult<Map<String, Object>> guard = requireCommand(idempotencyKey, request == null ? null : request.reason());
        if (guard != null) {
            return guard;
        }
        String key = normalizeWithdrawGateKey(request.key());
        BigDecimal oldValue = currentWithdrawGateValue(key);
        BigDecimal newValue = normalizeWithdrawGateValue(key, request.value());
        if (loosensWithdrawGate(key, oldValue, newValue) && coverageBelowRedline()) {
            return coverageRedline();
        }
        String configKey = withdrawGateConfigKey(key);
        String mirrorKey = withdrawGateMirrorKey(key);
        configFacade.upsertAdminValue(configKey, newValue.toPlainString(), "NUMBER", "growth", "H1 withdraw NEX gate");
        configFacade.upsertAdminValue(mirrorKey, newValue.toPlainString(), "NUMBER", "wallet", "D5 mirror of H1 withdraw NEX gate");
        audit("H1_WITHDRAW_NEX_GATE_CHANGED", "WITHDRAW_NEX_GATE", configKey, request.operator(), Map.of(
                "key", key,
                "mirrorKey", mirrorKey,
                "oldValue", oldValue,
                "newValue", newValue,
                "reason", request.reason().trim(),
                "idempotencyKey", idempotencyKey.trim()));
        Map<String, Object> response = withdrawGate().getData();
        response.put("updated", Map.of("key", key, "configKey", configKey, "mirrorKey", mirrorKey, "oldValue", oldValue, "newValue", newValue));
        return ApiResult.ok(response);
    }

    private Map<String, Object> questStats() {
        return row(
                "dayOneRate24h", "71%",
                "dayOneRateGrace", "18%",
                "weeklyDone", "184K",
                "t1Done", "38K",
                "t2Done", "146K",
                "weeklyNex", "2.4M NEX",
                "phaseBonusP3", weeklyMultiplier("P3"),
                "monthlyInflight", 31240);
    }

    private Map<String, Object> eventStats() {
        List<Map<String, Object>> events = questEventsList();
        long ongoing = events.stream().filter(row -> "ongoing".equals(row.get("state"))).count();
        String featured = events.stream()
                .filter(row -> Boolean.TRUE.equals(row.get("featured")))
                .map(row -> row.get("name").toString())
                .findFirst()
                .orElse("--");
        return row(
                "ongoing", ongoing,
                "featuredEv", featured,
                "trackJoin", "12.4K",
                "trackDone", "3.1K",
                "trackClaim", "2.8K",
                "wheelToday", "$642 / " + questConfig("wheel.guards.budget"),
                "geoBlocked", 2);
    }

    private List<Map<String, Object>> dayOneTasks() {
        return defaultDayOneTasks().stream()
                .map(row -> {
                    Map<String, Object> copy = new LinkedHashMap<>(row);
                    int id = Integer.parseInt(copy.get("id").toString());
                    copy.put("reward", questConfig("dayOne.tasks." + id + ".reward"));
                    return copy;
                })
                .toList();
    }

    private List<Map<String, Object>> defaultDayOneTasks() {
        return List.of(
                row("id", 0, "task", "绑卡", "href", "topup?kyc=1", "reward", "50 NEX"),
                row("id", 1, "task", "逛收益页", "href", "/earn", "reward", "30 NEX"),
                row("id", 2, "task", "逛商城", "href", "/store", "reward", "50 NEX"),
                row("id", 3, "task", "看回报率", "href", "/store/roi", "reward", "100 NEX"),
                row("id", 4, "task", "设资料", "href", "/me/profile", "reward", "80 NEX"),
                row("id", 5, "task", "邀请好友", "href", "/team/invite", "reward", "200 NEX + $1"));
    }

    private List<Map<String, Object>> dayOneStates() {
        return List.of(
                row("st", "active", "label", "24h 内 6 项完成领 500", "tone", "ok"),
                row("st", "grace", "label", "72h 内 200", "tone", "warn"),
                row("st", "expired", "label", "0,首页让位", "tone", "dim"));
    }

    private List<Map<String, Object>> weeklyTier1() {
        List<Map<String, Object>> rows = defaultWeeklyTier1();
        for (int i = 0; i < rows.size(); i++) {
            rows.get(i).put("reward", questConfig("weekly.tier1." + i + ".reward"));
        }
        return rows;
    }

    private List<Map<String, Object>> defaultWeeklyTier1() {
        return new ArrayList<>(List.of(
                row("cond", "NEX 持有达标", "reward", "3,000"),
                row("cond", "买 Genesis", "reward", "2,500"),
                row("cond", "加购硬件", "reward", "2,000"),
                row("cond", "换新升级", "reward", "1,800"),
                row("cond", "S1→Pro", "reward", "1,500"),
                row("cond", "收益加速档位", "reward", "800"),
                row("cond", "首购设备", "reward", "1,000 + $10"),
                row("cond", "充值", "reward", "100"),
                row("cond", "兑底质押", "reward", "250")));
    }

    private List<Map<String, Object>> weeklyTier2() {
        List<Map<String, Object>> rows = defaultWeeklyTier2();
        for (int i = 0; i < rows.size(); i++) {
            rows.get(i).put("reward", questConfig("weekly.tier2." + i + ".reward"));
        }
        return rows;
    }

    private List<Map<String, Object>> defaultWeeklyTier2() {
        return new ArrayList<>(List.of(
                row("cond", "邀请好友", "reward", "200 + $2"),
                row("cond", "复投", "reward", "120"),
                row("cond", "小额质押", "reward", "150"),
                row("cond", "兑换", "reward", "80"),
                row("cond", "小充", "reward", "100"),
                row("cond", "逛商城", "reward", "50"),
                row("cond", "跑单 50 次", "reward", "80"),
                row("cond", "看 Genesis", "reward", "60")));
    }

    private List<Map<String, Object>> weeklyMultipliers() {
        return List.of(
                row("p", "P1", "mult", weeklyMultiplier("P1")),
                row("p", "P2", "mult", weeklyMultiplier("P2")),
                row("p", "P3 当前", "mult", weeklyMultiplier("P3")),
                row("p", "P4", "mult", weeklyMultiplier("P4")),
                row("p", "P5", "mult", weeklyMultiplier("P5")),
                row("p", "P6", "mult", weeklyMultiplier("P6")));
    }

    private List<Map<String, Object>> monthlyMissions() {
        return defaultMonthlyMissions().stream()
                .map(row -> {
                    Map<String, Object> copy = new LinkedHashMap<>(row);
                    String id = copy.get("id").toString();
                    copy.put("reward", questConfig("monthly." + id + ".reward"));
                    return copy;
                })
                .toList();
    }

    private List<Map<String, Object>> defaultMonthlyMissions() {
        return List.of(
                row("id", "mc0", "theme", "地基建设者", "age", "0-2 月", "reward", "1,500", "goals", "累计赚 200 · 绑卡 · 邀 1 人"),
                row("id", "mc1", "theme", "网络架构师", "age", "2-4 月", "reward", "2,500", "goals", "累计 1,500 · 直推 3 · 周任务 ×4"),
                row("id", "mc2", "theme", "进阶之路", "age", "4-6 月", "reward", "4,000", "goals", "累计 5,000 · 设备升级 · 加购"),
                row("id", "mc3", "theme", "钻石段位", "age", "6-9 月", "reward", "6,000", "goals", "累计 15,000 · V4 · 团队 GV"),
                row("id", "mc4", "theme", "创始人之约", "age", "9+ 月", "reward", "10,000 + 勋章", "goals", "累计 40,000 · NEX 持有 · Genesis"));
    }

    private List<Map<String, Object>> taskMonitor() {
        return List.of(
                row("label", "首日", "note", "进窗 28,940 · 24h 领 71% · 宽限领 18% · 流失 11%"),
                row("label", "每周", "note", "派发 96K 人 · 一档完成 38K · 二档完成 146K 项"),
                row("label", "月度", "note", "在途 31,240 · 本月可领 4,120 · 已领 3,880"));
    }

    private Map<String, Object> phaseMultiplierReadonly() {
        return row(
                "sourceDomain", "H1",
                "currentPhase", configFacade.activeValue(CURRENT_PHASE_KEY).orElse(phaseForMonth(currentMonth())),
                "value", activeDials().get("questRewardMultiplier"),
                "note", "H1 owns global quest reward multiplier; H3 only consumes it");
    }

    private List<Map<String, Object>> questEventsList() {
        return defaultQuestEvents().stream()
                .map(row -> {
                    Map<String, Object> copy = new LinkedHashMap<>(row);
                    String id = copy.get("id").toString();
                    copy.put("state", eventStatus(id));
                    copy.put("reward", eventReward(id));
                    copy.put("featured", eventFeatured(id));
                    return copy;
                })
                .toList();
    }

    private List<Map<String, Object>> defaultQuestEvents() {
        return List.of(
                row("id", "pro-7d", "name", "Pro 限时升级 7 天", "kind", "discount", "state", "ongoing", "reward", "2,000 NEX", "featured", true, "trackable", true, "condition", "持有 Pro 或以上(设备域)", "geo", "全区"),
                row("id", "ref-5", "name", "邀 5 人得 Pro", "kind", "referral", "state", "ongoing", "reward", "5,000 NEX", "featured", false, "trackable", true, "condition", "直推数 >= 5(团队域)", "geo", "全区"),
                row("id", "spring-wheel", "name", "春日转盘(日重置)", "kind", "wheel", "state", "ongoing", "reward", "见奖池", "featured", false, "trackable", false, "condition", "-", "geo", "全区"),
                row("id", "onboard-7d", "name", "新人 7 日引导", "kind", "onboarding", "state", "ongoing", "reward", "200 NEX", "featured", false, "trackable", true, "condition", "7 天内完成 4 项", "geo", "全区"),
                row("id", "regional-pk", "name", "区域算力 PK", "kind", "regional", "state", "upcoming", "reward", "-", "featured", false, "trackable", false, "condition", "-", "geo", "全区"),
                row("id", "anniv-wheel", "name", "周年转盘", "kind", "wheel", "state", "ended", "reward", "见奖池", "featured", false, "trackable", false, "condition", "-", "geo", "全区"),
                row("id", "nex-div", "name", "NEX 持有者分红", "kind", "holding", "state", "ongoing", "reward", "见奖池", "featured", false, "trackable", true, "condition", "NEX 余额 >= 1,000(代币域)", "geo", "2 国屏蔽"));
    }

    private List<Map<String, Object>> eventStateLegend() {
        return List.of(
                row("state", "upcoming", "label", "预告", "tone", "dim"),
                row("state", "ongoing", "label", "进行中", "tone", "ok"),
                row("state", "ended", "label", "已结束", "tone", "dim"));
    }

    private List<Map<String, Object>> wheelTiers() {
        return List.of(
                row("tier", "安慰奖", "reward", "+5 NEX", "prob", new BigDecimal("38"), "real", false, "kind", "平台内"),
                row("tier", "微 NEX", "reward", "+10 NEX", "prob", new BigDecimal("24"), "real", false, "kind", "平台内"),
                row("tier", "小 NEX", "reward", "+30 NEX", "prob", new BigDecimal("18"), "real", false, "kind", "平台内"),
                row("tier", "中 NEX", "reward", "+50 NEX", "prob", new BigDecimal("11"), "real", false, "kind", "平台内"),
                row("tier", "小额现金", "reward", "$1", "prob", new BigDecimal("5"), "real", true, "kind", "真实流出"),
                row("tier", "购机抵扣券", "reward", "$50 券(只抵购机)", "prob", new BigDecimal("3"), "real", false, "kind", "转化导向"),
                row("tier", "中额现金", "reward", "$20", "prob", new BigDecimal("0.9"), "real", true, "kind", "真实流出"),
                row("tier", "大奖", "reward", "$500", "prob", new BigDecimal("0.1"), "real", true, "kind", "真实流出"));
    }

    private BigDecimal wheelEvUsd() {
        return wheelTiers().stream()
                .filter(row -> Boolean.TRUE.equals(row.get("real")))
                .map(row -> parseMoney(row.get("reward").toString()).multiply(new BigDecimal(row.get("prob").toString()))
                        .divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP))
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP)
                .stripTrailingZeros();
    }

    private List<Map<String, Object>> wheelGuards() {
        return List.of(
                row("key", "budget", "label", "日派彩预算", "value", questConfig("wheel.guards.budget"), "note", "到顶当日只发 NEX/券"),
                row("key", "cap", "label", "单奖日库存", "value", questConfig("wheel.guards.cap"), "note", ""),
                row("key", "kill", "label", "真实奖总开关", "value", questConfig("wheel.guards.kill"), "note", "应急一键停发真钱档"));
    }

    private List<Map<String, Object>> trackables() {
        return List.of(
                row("id", "pro-7d", "name", "Pro 限时升级", "cond", "持有 Pro 或以上(设备域)", "join", "8,420", "done", "1,240", "claim", "1,180", "geo", "全区"),
                row("id", "ref-5", "name", "邀 5 人得 Pro", "cond", "直推数 >= 5(团队域)", "join", "2,180", "done", "312", "claim", "290", "geo", "全区"),
                row("id", "onboard-7d", "name", "新人 7 日引导", "cond", "7 天内完成 4 项", "join", "11,400", "done", "1,890", "claim", "1,640", "geo", "全区"),
                row("id", "nex-div", "name", "NEX 持有者分红", "cond", "NEX 余额 >= 1,000(代币域)", "join", "3,840", "done", "660", "claim", "594", "geo", "2 国屏蔽"));
    }

    private Map<String, Object> trialStats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("activeSessions", 11273);
        stats.put("inTrial", 9840);
        stats.put("inGrace", 1180);
        stats.put("inExtended", 253);
        stats.put("trialBuyRate", "22.4%");
        stats.put("bindCardRate", "61.2%");
        stats.put("earlyBuyRate", "38%");
        stats.put("reviveRate", "9%");
        stats.put("k2Blocked", 17);
        return stats;
    }

    private Map<String, Object> trialModelA() {
        Map<String, Object> model = new LinkedHashMap<>();
        model.put("offsetRule", "min(shadow, offsetCap)");
        model.put("offsetLiability", false);
        model.put("bonusLedgerRule", "shadow above offset cap enters bonus ledger after purchase");
        model.put("serverCanonical", "computeTrialOffset");
        return model;
    }

    private List<Map<String, Object>> trialParams() {
        return defaultTrialParams().stream()
                .map(row -> {
                    String key = row.get("key").toString();
                    Map<String, Object> copy = new LinkedHashMap<>(row);
                    copy.put("cur", trialParamCurrentValue(key, row.get("cur").toString()));
                    return copy;
                })
                .toList();
    }

    private List<Map<String, Object>> defaultTrialParams() {
        return List.of(
                trialParam("days", "试用天数 / 宽限期 / 延长天数", "", "3 / 7 / 3 天", false, "newonly", false),
                trialParam("price", "试用机价", "对应机型 stellarbox-s1(改机型走治理)", "$1,299", true, "newonly", false),
                trialParam("shadow", "每日影子收益", "", "$38.52 + 65 NEX", false, "newonly", false),
                trialParam("offsetCap", "收益抵扣购机款上限", "抵扣是折扣不是负债;超出部分购后才入余额", "$50", false, "live", false),
                trialParam("disc", "提前购买折扣 / 折扣上限", "", "15% / $20", false, "live", false),
                trialParam("hq", "高质量延长触发线", "", "$100", false, "live", false),
                trialParam("failRate", "扣款失败概率", "平台内部参数,不外泄到用户界面", "•••(server only)", true, "live", true),
                trialParam("trialCooldown", "再试用冷却 / 本阶段开放", "开放与否随 H1 阶段调度", "30 天 / 开放", false, "live", false),
                trialParam("push", "auto-push(延迟 / 冷却 / 单会话上限)", "", "1.5s / 24h / 1 次", false, "live", false),
                trialParam("autoCharge", "期末自动扣款", "关掉=停止自动扣款,直接影响资金", "开", true, "live", false));
    }

    private Map<String, Object> trialParam(
            String key,
            String name,
            String sub,
            String current,
            boolean hot,
            String section,
            boolean serverOnly) {
        Map<String, Object> param = new LinkedHashMap<>();
        param.put("key", key);
        param.put("name", name);
        param.put("sub", sub);
        param.put("cur", current);
        param.put("hot", hot);
        param.put("section", section);
        param.put("serverOnly", serverOnly);
        return param;
    }

    private String trialParamCurrentValue(String key, String fallback) {
        if ("failRate".equals(key)) {
            return "•••(server only)";
        }
        return configFacade.activeValue(trialParamConfigKey(key)).filter(StringUtils::hasText).orElse(fallback);
    }

    private List<Map<String, Object>> trialGates() {
        return List.of(
                trialGate("资格统一裁决", "冷却/阶段未开/养号都开不了"),
                trialGate("30 天冷却", "K2 簇命中跨号生效,绕不过"),
                trialGate("K2 循环阻断", "养号信号实时关闭资格"),
                trialGate("扣款幂等", "Idempotency-Key 24h dedup · 重复请求只生效一次"));
    }

    private Map<String, Object> trialGate(String gate, String note) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("gate", gate);
        row.put("note", note);
        return row;
    }

    private List<Map<String, Object>> trialStates() {
        return List.of(
                trialState("idle", "待启动", "dim"),
                trialState("active", "试用中", "ok"),
                trialState("grace", "宽限中", "warn"),
                trialState("extended", "已延长", "warn"),
                trialState("cancelled", "已取消", "dim"),
                trialState("redeemed", "已购买", "ok"),
                trialState("failed", "扣款失败", "bad"));
    }

    private Map<String, Object> trialState(String key, String label, String tone) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("key", key);
        row.put("label", label);
        row.put("tone", tone);
        return row;
    }

    private List<Map<String, Object>> trialSessions() {
        return defaultTrialSessions().stream()
                .map(row -> {
                    String sid = row.get("sid").toString();
                    String state = trialSessionState(sid);
                    Map<String, Object> copy = new LinkedHashMap<>(row);
                    copy.put("state", state);
                    if ("cancelled".equals(state)) {
                        copy.put("shadow", "归零");
                    }
                    configFacade.activeValue(trialSessionConfigKey(sid, "cancelled_at"))
                            .ifPresent(value -> copy.put("cancelledAt", value));
                    configFacade.activeValue(trialSessionConfigKey(sid, "charged_at"))
                            .ifPresent(value -> copy.put("chargedAt", value));
                    return copy;
                })
                .toList();
    }

    private List<Map<String, Object>> defaultTrialSessions() {
        return List.of(
                trialSession("usr_9921", "active", "$115 + 195 NEX", "tok_88a2"),
                trialSession("usr_2231", "grace", "$231 + 390 NEX", "tok_71f0"),
                trialSession("usr_8807", "extended", "$308 + 520 NEX", "tok_8a3f(CL-318)"),
                trialSession("usr_77D4", "cancelled", "归零", "tok_9912"));
    }

    private Map<String, Object> trialSession(String sid, String state, String shadow, String cardToken) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("sid", sid);
        row.put("state", state);
        row.put("shadow", shadow);
        row.put("cardTok", cardToken);
        return row;
    }

    private boolean trialAutoPushKilled() {
        return Boolean.TRUE.equals(parseBooleanValue(configFacade.activeValue(TRIAL_AUTO_PUSH_KILLED_KEY).orElse("false")));
    }

    private String normalizeTrialParamKey(String key) {
        String normalized = requireText(key, "Trial parameter key is required");
        if (!TRIAL_PARAM_KEYS.contains(normalized)) {
            throw new IllegalArgumentException("Unsupported H2 trial parameter");
        }
        return normalized;
    }

    private String normalizeTrialParamValue(String key, String raw) {
        String value = requireText(raw, "VALUE_REQUIRED");
        if (value.length() > 120) {
            throw new IllegalArgumentException("TRIAL_PARAM_VALUE_TOO_LONG");
        }
        if (value.startsWith("{") || value.startsWith("[")) {
            throw new IllegalArgumentException("JSON_VALUE_NOT_ALLOWED");
        }
        if ("failRate".equals(key)) {
            return bounded(parseDecimal(value), BigDecimal.ZERO, new BigDecimal("100")).toPlainString();
        }
        if ("autoCharge".equals(key)) {
            return normalizeTrialAutoCharge(value);
        }
        return value;
    }

    private String normalizeTrialAutoCharge(String value) {
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "开", "on", "true", "1", "enabled" -> "开";
            case "关", "off", "false", "0", "disabled" -> "关";
            default -> throw new IllegalArgumentException("AUTO_CHARGE_VALUE_INVALID");
        };
    }

    private String trialParamConfigKey(String key) {
        return TRIAL_PARAM_PREFIX + normalizeTrialParamKey(key);
    }

    private String normalizeTrialSessionId(String sessionId) {
        String sid = requireText(sessionId, "Trial session id is required");
        if (!sid.matches("[A-Za-z0-9_-]+")) {
            throw new IllegalArgumentException("TRIAL_SESSION_ID_INVALID");
        }
        boolean exists = defaultTrialSessions().stream().anyMatch(row -> sid.equals(row.get("sid")));
        if (!exists) {
            throw new IllegalArgumentException("TRIAL_SESSION_NOT_FOUND");
        }
        return sid;
    }

    private String trialSessionState(String sid) {
        return configFacade.activeValue(trialSessionConfigKey(sid, "state"))
                .filter(StringUtils::hasText)
                .map(value -> value.trim().toLowerCase(Locale.ROOT))
                .orElseGet(() -> defaultTrialSessions().stream()
                        .filter(row -> sid.equals(row.get("sid")))
                        .findFirst()
                        .map(row -> row.get("state").toString())
                        .orElse("idle"));
    }

    private String trialSessionConfigKey(String sid, String field) {
        return TRIAL_SESSION_PREFIX + sid + "." + field;
    }

    private Map<String, Object> checkInStats() {
        BigDecimal lucky15Pct = configDecimal(CHECKIN_LUCKY_15_PCT_KEY, new BigDecimal("15"));
        BigDecimal lucky2Pct = configDecimal(CHECKIN_LUCKY_2_PCT_KEY, new BigDecimal("5"));
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("todaySign", 61420);
        stats.put("signRate", "47.8%");
        stats.put("lucky15Actual", "15.2%");
        stats.put("lucky2Actual", "4.9%");
        stats.put("lucky15Config", lucky15Pct.stripTrailingZeros().toPlainString() + "%");
        stats.put("lucky2Config", lucky2Pct.stripTrailingZeros().toPlainString() + "%");
        stats.put("weekRevive", 1840);
        stats.put("weekMsTrigger", 2214);
        stats.put("weekMsNex", "412K NEX");
        return stats;
    }

    private List<Map<String, Object>> checkInRules() {
        return List.of(
                checkInRule("baseline", "每日基础 NEX", "", formatSignedNex(configDecimal(CHECKIN_REWARD_KEY, new BigDecimal("2"))), false),
                checkInRule("bonus7", "连续 7 天加奖", "", formatSignedNex(configDecimal(CHECKIN_STREAK_BONUS_KEY, new BigDecimal("5"))), false),
                checkInRule("p15", "幸运 1.5× 概率", "两档概率合计 ≤ 100%,超了直接拒",
                        formatPercentNumber(configDecimal(CHECKIN_LUCKY_15_PCT_KEY, new BigDecimal("15"))), true),
                checkInRule("p2", "幸运 2× 概率", "",
                        formatPercentNumber(configDecimal(CHECKIN_LUCKY_2_PCT_KEY, new BigDecimal("5"))), true),
                checkInRule("broken", "断签阈值", "超过没签连胜归零(可用复活卡)",
                        configDecimal(CHECKIN_BROKEN_HOURS_KEY, new BigDecimal("48")).stripTrailingZeros().toPlainString() + " 小时", false),
                checkInRule("saver", "复活卡默认持有 / 恢复上限", "恢复到 min(历史最长连胜, 上限)",
                        configFacade.activeValue(CHECKIN_REVIVE_CARDS_KEY).orElse("1 张 / 30 天"), false));
    }

    private Map<String, Object> checkInRule(String key, String name, String sub, String current, boolean hot) {
        Map<String, Object> rule = new LinkedHashMap<>();
        rule.put("key", key);
        rule.put("name", name);
        rule.put("sub", sub);
        rule.put("cur", current);
        rule.put("hot", hot);
        return rule;
    }

    private List<Map<String, Object>> streakMilestones() {
        return defaultStreakMilestones().stream()
                .map(row -> {
                    int id = Integer.parseInt(row.get("id").toString());
                    Map<String, Object> copy = new LinkedHashMap<>(row);
                    copy.put("reward", configFacade.activeValue(CHECKIN_STREAK_MS_PREFIX + id + ".reward")
                            .orElse(row.get("reward").toString()));
                    return copy;
                })
                .toList();
    }

    private List<Map<String, Object>> defaultStreakMilestones() {
        return List.of(
                streakMilestone(0, "3 天", "+5 NEX", "nex"),
                streakMilestone(1, "7 天", "+15 NEX", "nex"),
                streakMilestone(2, "14 天", "+$1", "usdt"),
                streakMilestone(3, "21 天", "+100 NEX", "nex"),
                streakMilestone(4, "30 天", "转盘票 ×1", "spin"),
                streakMilestone(5, "60 天", "+$10", "usdt"),
                streakMilestone(6, "100 天", "连胜大师徽章", "badge"));
    }

    private Map<String, Object> defaultStreakMilestone(int milestoneId) {
        return defaultStreakMilestones().get(milestoneId);
    }

    private Map<String, Object> streakMilestone(int id, String day, String reward, String kind) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", id);
        row.put("day", day);
        row.put("reward", reward);
        row.put("kind", kind);
        return row;
    }

    private List<Map<String, Object>> streakDistribution() {
        return List.of(
                streakDistribution("≥7 天", "38.2K", 100),
                streakDistribution("≥14", "21.4K", 56),
                streakDistribution("≥30", "9.8K", 26),
                streakDistribution("≥60", "3.1K", 8),
                streakDistribution("≥100", "640", 3));
    }

    private Map<String, Object> streakDistribution(String day, String count, int height) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("day", day);
        row.put("count", count);
        row.put("height", height);
        return row;
    }

    private List<Map<String, Object>> powerUps() {
        return defaultPowerUps().stream()
                .map(row -> {
                    int id = Integer.parseInt(row.get("id").toString());
                    Map<String, Object> copy = new LinkedHashMap<>(row);
                    copy.put("day", configDecimal(CHECKIN_POWER_UP_PREFIX + id + ".day",
                            new BigDecimal(row.get("day").toString())).intValue());
                    copy.put("note", configFacade.activeValue(CHECKIN_POWER_UP_PREFIX + id + ".note").orElse(""));
                    return copy;
                })
                .toList();
    }

    private List<Map<String, Object>> defaultPowerUps() {
        return List.of(
                powerUp(0, 7, "7 天 · 版税加成", "兑现在团队费率(F2)", "F2"),
                powerUp(1, 14, "14 天 · 历史权益迁移提示", "Premium 已下线,仅保留迁移说明和历史兼容,不再兑付订阅", "MIGRATION"),
                powerUp(2, 30, "30 天 · 下次质押 +2% 年化", "兑现在质押(G1)", "G1"),
                powerUp(3, 60, "60 天 · Genesis 白名单优先", "兑现在 Genesis(G4)", "G4"));
    }

    private Map<String, Object> defaultPowerUp(int powerUpId) {
        return defaultPowerUps().get(powerUpId);
    }

    private Map<String, Object> powerUp(int id, int day, String label, String sub, String downstream) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", id);
        row.put("day", day);
        row.put("label", label);
        row.put("sub", sub);
        row.put("downstream", downstream);
        return row;
    }

    private List<Map<String, Object>> earnMilestones() {
        return defaultEarnMilestones().stream()
                .map(row -> {
                    String key = row.get("key").toString();
                    Map<String, Object> copy = new LinkedHashMap<>(row);
                    copy.put("threshold", earnMilestoneThreshold(key));
                    copy.put("nex", earnMilestoneReward(key));
                    return copy;
                })
                .toList();
    }

    private List<Map<String, Object>> defaultEarnMilestones() {
        return List.of(
                earnMilestone(0, "earn-100", 100, 100, 912),
                earnMilestone(1, "earn-500", 500, 250, 624),
                earnMilestone(2, "earn-1000", 1000, 500, 388),
                earnMilestone(3, "earn-5000", 5000, 1500, 210),
                earnMilestone(4, "earn-10000", 10000, 3000, 80));
    }

    private Map<String, Object> earnMilestone(int id, String key, int threshold, int nex, int weekTrigger) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", id);
        row.put("key", key);
        row.put("threshold", threshold);
        row.put("nex", nex);
        row.put("weekTrigger", weekTrigger);
        return row;
    }

    private Map<String, Object> tickInterval() {
        Map<String, Object> tick = new LinkedHashMap<>();
        tick.put("value", configDecimal(EARN_TICK_INTERVAL_KEY, new BigDecimal("4")).intValue() + " 秒");
        tick.put("seconds", configDecimal(EARN_TICK_INTERVAL_KEY, new BigDecimal("4")).intValue());
        tick.put("min", 1);
        tick.put("max", 60);
        tick.put("note", "过密会拉高平台负载 · 内部参数,超管确认");
        return tick;
    }

    private Map<String, Object> phaseConfig() {
        Optional<String> raw = configFacade.activeValue(PHASE_CONFIG_KEY).filter(StringUtils::hasText);
        if (raw.isEmpty()) {
            Map<String, Object> defaults = new LinkedHashMap<>();
            defaults.put("calendar", "12_MONTH");
            defaults.put("activePhase", "P1");
            defaults.put("activeDialCount", 8);
            return defaults;
        }
        try {
            return objectMapper.readValue(raw.get(), new TypeReference<Map<String, Object>>() {
            });
        } catch (JsonProcessingException ex) {
            Map<String, Object> fallback = new LinkedHashMap<>();
            fallback.put("calendar", "12_MONTH");
            fallback.put("activePhase", "P1");
            fallback.put("activeDialCount", 8);
            fallback.put("invalidSource", PHASE_CONFIG_KEY);
            return fallback;
        }
    }

    private Map<String, Object> activeDials() {
        Map<String, Object> dials = new LinkedHashMap<>();
        dials.put("inviteRewardMultiplier", configDecimal("growth.phase.invite_reward_multiplier", BigDecimal.ONE));
        dials.put("questRewardMultiplier", configDecimal("growth.phase.quest_reward_multiplier", BigDecimal.ONE));
        dials.put("trialOffsetCapUsdt", configDecimal("growth.phase.trial_offset_cap_usdt", new BigDecimal("50")));
        dials.put("deviceReleasePacingPct", percent(configDecimal("growth.phase.device_release_pacing_pct", new BigDecimal("0.60"))));
        dials.put("commissionTighteningPct", percent(configDecimal("growth.phase.commission_tightening_pct", new BigDecimal("0.10"))));
        dials.put("campaignRewardNex", configDecimal("growth.phase.campaign_reward_nex", new BigDecimal("10")));
        dials.put("withdrawNexMinBalance", configDecimal(WITHDRAW_MIN_BALANCE_KEY, new BigDecimal("100")));
        dials.put("withdrawNexHoldDays", configDecimal(WITHDRAW_HOLD_DAYS_KEY, new BigDecimal("7")).intValue());
        return dials;
    }

    private int currentMonth() {
        int month = configDecimal(CURRENT_MONTH_KEY, new BigDecimal("7"))
                .setScale(0, RoundingMode.DOWN)
                .intValue();
        return month >= 1 && month <= 12 ? month : 7;
    }

    private String phaseForMonth(int month) {
        if (month <= 2) {
            return "P1";
        }
        if (month <= 4) {
            return "P2";
        }
        if (month <= 7) {
            return "P3";
        }
        if (month == 8) {
            return "P4";
        }
        if (month <= 10) {
            return "P5";
        }
        return "P6";
    }

    private List<Map<String, Object>> monthlyDials(int currentMonth) {
        java.util.ArrayList<Map<String, Object>> rows = new java.util.ArrayList<>();
        for (int month = 1; month <= 12; month++) {
            Map<String, Object> dials = new LinkedHashMap<>();
            for (String key : PHASE_DIAL_KEYS) {
                BigDecimal value = configDecimal(monthDialConfigKey(month, key), defaultPhaseMonthDialValue(month, key));
                dials.put(key, displayPhaseDialValue(key, value));
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("month", month);
            row.put("phase", phaseForMonth(month));
            row.put("current", month == currentMonth);
            row.put("dials", dials);
            rows.add(row);
        }
        return rows;
    }

    private List<Map<String, Object>> phaseControls() {
        return List.of(
                phaseControl("schedule", "排程", "按 12 个月增长曲线自动推进"),
                phaseControl("pin", "锁定阶段", "临时固定在指定阶段，用于投产窗口或演示"),
                phaseControl("override", "覆盖策略", "按批次或 cohort 临时覆盖阶段"));
    }

    private Map<String, Object> phaseControl(String key, String label, String description) {
        Map<String, Object> control = new LinkedHashMap<>();
        control.put("key", key);
        control.put("label", label);
        control.put("value", configFacade.activeValue(CONTROL_PREFIX + key).orElse(""));
        control.put("description", description);
        return control;
    }

    private List<Map<String, Object>> phaseOverrides() {
        return List.of(
                phaseOverride("2026-W18", "2026-W18 批次", "加速 +1 月(测试 P4 转化)· 命中 24,880 人"),
                phaseOverride("demo", "demo 批次(42 人)", "钉在 P6(投资人演示)· 长期"));
    }

    private Map<String, Object> phaseOverride(String id, String cohort, String description) {
        Map<String, Object> override = new LinkedHashMap<>();
        override.put("id", id);
        override.put("cohort", cohort);
        override.put("description", description);
        override.put("disabled", parseBooleanValue(configFacade.activeValue(OVERRIDE_PREFIX + id + ".disabled").orElse("false")));
        return override;
    }

    private List<Map<String, Object>> phaseAttribution() {
        return List.of(
                Map.of("phase", "P1 引爆(月1-2)", "first", "5.4%", "reinvest", "18%", "weekly", "$1.9M", "d7", "64.1%", "cur", false),
                Map.of("phase", "P2 扩张(月3-4)", "first", "6.2%", "reinvest", "21%", "weekly", "$2.6M", "d7", "61.0%", "cur", false),
                Map.of("phase", "P3 收紧(月5-7)· 当前", "first", "6.8%", "reinvest", "26.9%", "weekly", "$2.1M", "d7", "58.2%", "cur", true));
    }

    private Map<String, Object> coverage() {
        TreasuryCoverageSnapshot snapshot = coverageFacade.snapshot();
        Map<String, Object> coverage = new LinkedHashMap<>();
        coverage.put("coverageRatio", snapshot.coverageRatio());
        coverage.put("redlinePct", snapshot.redlinePct());
        coverage.put("redlineBreached", snapshot.coverageRatio().compareTo(snapshot.redlinePct()) < 0);
        return coverage;
    }

    private String monthDialConfigKey(int month, String key) {
        return MONTH_DIAL_PREFIX + month + "." + key;
    }

    private BigDecimal defaultPhaseMonthDialValue(int month, String key) {
        return switch (key) {
            case "inviteRewardMultiplier" -> month <= 2 ? new BigDecimal("2")
                    : month <= 4 ? new BigDecimal("1.5") : BigDecimal.ONE;
            case "questRewardMultiplier" -> month <= 2 ? new BigDecimal("4") : BigDecimal.ONE;
            case "trialOffsetCapUsdt" -> new BigDecimal("50");
            case "deviceReleasePacingPct" -> month <= 2 ? new BigDecimal("0.60")
                    : month <= 4 ? new BigDecimal("0.70")
                    : month <= 7 ? new BigDecimal("0.80") : new BigDecimal("0.90");
            case "commissionTighteningPct" -> month <= 4 ? new BigDecimal("0.10")
                    : month <= 7 ? new BigDecimal("0.15") : new BigDecimal("0.20");
            case "campaignRewardNex" -> month <= 2 ? new BigDecimal("20")
                    : month <= 4 ? new BigDecimal("15") : new BigDecimal("10");
            case "withdrawNexMinBalance" -> month <= 8 ? new BigDecimal("100") : new BigDecimal("200");
            case "withdrawNexHoldDays" -> month <= 7 ? new BigDecimal("7")
                    : month == 8 ? new BigDecimal("14") : new BigDecimal("21");
            default -> throw new IllegalArgumentException("Unsupported H1 phase dial");
        };
    }

    private Object displayPhaseDialValue(String key, BigDecimal value) {
        return switch (key) {
            case "deviceReleasePacingPct", "commissionTighteningPct" -> percent(value);
            case "withdrawNexHoldDays" -> value.intValue();
            default -> value.stripTrailingZeros();
        };
    }

    private void writeActivePhaseDial(String normalizedKey, BigDecimal newValue) {
        configFacade.upsertAdminValue(
                phaseDialConfigKey(normalizedKey),
                newValue.toPlainString(),
                "NUMBER",
                "growth",
                "H1 phase dial");
        if ("withdrawNexMinBalance".equals(normalizedKey)) {
            configFacade.upsertAdminValue(
                    WITHDRAW_MIN_BALANCE_MIRROR_KEY,
                    newValue.toPlainString(),
                    "NUMBER",
                    "wallet",
                    "D5 mirror of H1 withdraw NEX gate");
        }
        if ("withdrawNexHoldDays".equals(normalizedKey)) {
            configFacade.upsertAdminValue(
                    WITHDRAW_HOLD_DAYS_MIRROR_KEY,
                    newValue.toPlainString(),
                    "NUMBER",
                    "wallet",
                    "D5 mirror of H1 withdraw NEX gate");
        }
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

    private ApiResult<Map<String, Object>> retiredFeature() {
        return ApiResult.fail(OpsErrorCode.PHASE_PARAM_READONLY.httpStatus(), OpsErrorCode.PHASE_PARAM_READONLY.name());
    }

    private ApiResult<Map<String, Object>> coverageRedline() {
        return ApiResult.fail(OpsErrorCode.COVERAGE_BELOW_REDLINE.httpStatus(), OpsErrorCode.COVERAGE_BELOW_REDLINE.name());
    }

    private ApiResult<Map<String, Object>> validation(String message) {
        return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), message);
    }

    private ApiResult<Map<String, Object>> invalidState(String message) {
        return ApiResult.fail(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus(), message);
    }

    private boolean coverageBelowRedline() {
        TreasuryCoverageSnapshot coverage = coverageFacade.snapshot();
        return coverage.coverageRatio().compareTo(coverage.redlinePct()) < 0;
    }

    private String normalizePhaseDialKey(String key) {
        String normalized = requireText(key, "Phase dial key is required");
        if (RETIRED_KEYS.contains(normalized)) {
            return normalized;
        }
        try {
            phaseDialConfigKey(normalized);
            return normalized;
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Unsupported H1 phase dial", ex);
        }
    }

    private String phaseDialConfigKey(String key) {
        return switch (key) {
            case "inviteRewardMultiplier" -> "growth.phase.invite_reward_multiplier";
            case "questRewardMultiplier" -> "growth.phase.quest_reward_multiplier";
            case "trialOffsetCapUsdt" -> "growth.phase.trial_offset_cap_usdt";
            case "deviceReleasePacingPct" -> "growth.phase.device_release_pacing_pct";
            case "commissionTighteningPct" -> "growth.phase.commission_tightening_pct";
            case "campaignRewardNex" -> "growth.phase.campaign_reward_nex";
            case "withdrawNexMinBalance" -> WITHDRAW_MIN_BALANCE_KEY;
            case "withdrawNexHoldDays" -> WITHDRAW_HOLD_DAYS_KEY;
            default -> throw new IllegalArgumentException("Unsupported H1 phase dial");
        };
    }

    private BigDecimal defaultPhaseDialValue(String key) {
        return switch (key) {
            case "trialOffsetCapUsdt" -> new BigDecimal("50");
            case "deviceReleasePacingPct" -> new BigDecimal("0.60");
            case "commissionTighteningPct" -> new BigDecimal("0.10");
            case "campaignRewardNex" -> new BigDecimal("10");
            case "withdrawNexMinBalance" -> new BigDecimal("100");
            case "withdrawNexHoldDays" -> new BigDecimal("7");
            default -> BigDecimal.ONE;
        };
    }

    private BigDecimal normalizePhaseDialValue(String key, String raw) {
        BigDecimal value = parseDecimal(raw);
        return switch (key) {
            case "inviteRewardMultiplier", "questRewardMultiplier" -> bounded(value, new BigDecimal("0.10"), new BigDecimal("4"));
            case "trialOffsetCapUsdt" -> bounded(value, BigDecimal.ZERO, new BigDecimal("500"));
            case "deviceReleasePacingPct", "commissionTighteningPct" -> bounded(ratio(value), BigDecimal.ZERO, BigDecimal.ONE);
            case "campaignRewardNex" -> bounded(value, BigDecimal.ZERO, new BigDecimal("100000"));
            case "withdrawNexMinBalance" -> bounded(value, BigDecimal.ZERO, new BigDecimal("1000000"));
            case "withdrawNexHoldDays" -> wholeDays(value, 0, 365);
            default -> throw new IllegalArgumentException("Unsupported H1 phase dial");
        };
    }

    private boolean amplifiesPhasePayout(String key, BigDecimal oldValue, BigDecimal newValue) {
        return switch (key) {
            case "inviteRewardMultiplier", "questRewardMultiplier", "trialOffsetCapUsdt", "deviceReleasePacingPct", "campaignRewardNex" ->
                    newValue.compareTo(oldValue) > 0;
            case "commissionTighteningPct", "withdrawNexMinBalance", "withdrawNexHoldDays" -> newValue.compareTo(oldValue) < 0;
            default -> false;
        };
    }

    private String normalizeCheckInKey(String key) {
        String normalized = requireText(key, "Check-in key is required");
        if (normalized.toLowerCase(Locale.ROOT).contains("point")) {
            throw new IllegalArgumentException("Points system is sunset");
        }
        return switch (normalized) {
            case "baseRewardNex", "streakBonusNex", "luckyMultiplierMax" -> normalized;
            default -> throw new IllegalArgumentException("Unsupported H5 check-in key");
        };
    }

    private String checkInConfigKey(String key) {
        return switch (key) {
            case "baseRewardNex" -> CHECKIN_REWARD_KEY;
            case "streakBonusNex" -> CHECKIN_STREAK_BONUS_KEY;
            case "luckyMultiplierMax" -> CHECKIN_LUCKY_MULTIPLIER_KEY;
            default -> throw new IllegalArgumentException("Unsupported H5 check-in key");
        };
    }

    private BigDecimal currentCheckInValue(String key) {
        return switch (key) {
            case "baseRewardNex" -> configDecimal(CHECKIN_REWARD_KEY, new BigDecimal("0.25"));
            case "streakBonusNex" -> configDecimal(CHECKIN_STREAK_BONUS_KEY, BigDecimal.ONE);
            case "luckyMultiplierMax" -> configDecimal(CHECKIN_LUCKY_MULTIPLIER_KEY, new BigDecimal("2"));
            default -> throw new IllegalArgumentException("Unsupported H5 check-in key");
        };
    }

    private BigDecimal normalizeCheckInValue(String key, String raw) {
        BigDecimal value = parseDecimal(raw);
        return switch (key) {
            case "baseRewardNex" -> bounded(value, BigDecimal.ZERO, new BigDecimal("100"));
            case "streakBonusNex" -> bounded(value, BigDecimal.ZERO, new BigDecimal("1000"));
            case "luckyMultiplierMax" -> bounded(value, BigDecimal.ONE, new BigDecimal("10"));
            default -> throw new IllegalArgumentException("Unsupported H5 check-in key");
        };
    }

    private String normalizeWithdrawGateKey(String key) {
        String normalized = requireText(key, "Withdraw gate key is required");
        if (normalized.toLowerCase(Locale.ROOT).contains("point")) {
            return "withdrawPointsRatio";
        }
        if ("withdrawPointsRatio".equals(normalized)) {
            throw new IllegalArgumentException("Points withdrawal gate is sunset");
        }
        return switch (normalized) {
            case "minBalanceNex", "holdDays" -> normalized;
            default -> throw new IllegalArgumentException("Unsupported H1 withdraw gate key");
        };
    }

    private String withdrawGateConfigKey(String key) {
        return switch (key) {
            case "minBalanceNex" -> WITHDRAW_MIN_BALANCE_KEY;
            case "holdDays" -> WITHDRAW_HOLD_DAYS_KEY;
            default -> throw new IllegalArgumentException("Unsupported H1 withdraw gate key");
        };
    }

    private String withdrawGateMirrorKey(String key) {
        return switch (key) {
            case "minBalanceNex" -> WITHDRAW_MIN_BALANCE_MIRROR_KEY;
            case "holdDays" -> WITHDRAW_HOLD_DAYS_MIRROR_KEY;
            default -> throw new IllegalArgumentException("Unsupported H1 withdraw gate key");
        };
    }

    private BigDecimal currentWithdrawGateValue(String key) {
        return switch (key) {
            case "minBalanceNex" -> configDecimal(WITHDRAW_MIN_BALANCE_KEY, new BigDecimal("100"));
            case "holdDays" -> configDecimal(WITHDRAW_HOLD_DAYS_KEY, new BigDecimal("7"));
            default -> throw new IllegalArgumentException("Unsupported H1 withdraw gate key");
        };
    }

    private BigDecimal normalizeWithdrawGateValue(String key, String raw) {
        BigDecimal value = parseDecimal(raw);
        return switch (key) {
            case "minBalanceNex" -> bounded(value, BigDecimal.ZERO, new BigDecimal("1000000"));
            case "holdDays" -> wholeDays(value, 0, 365);
            default -> throw new IllegalArgumentException("Unsupported H1 withdraw gate key");
        };
    }

    private boolean loosensWithdrawGate(String key, BigDecimal oldValue, BigDecimal newValue) {
        return switch (key) {
            case "minBalanceNex", "holdDays" -> newValue.compareTo(oldValue) < 0;
            default -> false;
        };
    }

    private String normalizeQuestConfigKey(String raw) {
        String key = requireText(raw, "Quest config key is required")
                .replace("H3.", "")
                .replace("H4.", "")
                .replace("event.", "");
        if (key.matches("dayOne\\.tasks\\.\\d+\\.reward")) {
            int idx = Integer.parseInt(key.split("\\.")[2]);
            if (idx < 0 || idx >= defaultDayOneTasks().size()) {
                throw new IllegalArgumentException("QUEST_DAY_ONE_TASK_INVALID");
            }
            return key;
        }
        if (key.matches("dayOne\\.\\d+\\.reward")) {
            int idx = Integer.parseInt(key.split("\\.")[1]);
            if (idx < 0 || idx >= defaultDayOneTasks().size()) {
                throw new IllegalArgumentException("QUEST_DAY_ONE_TASK_INVALID");
            }
            return "dayOne.tasks." + idx + ".reward";
        }
        if (key.matches("weekly\\.t1\\.\\d+")) {
            int idx = Integer.parseInt(key.substring("weekly.t1.".length()));
            return normalizeWeeklyRewardKey("weekly.tier1", idx, defaultWeeklyTier1().size());
        }
        if (key.matches("weekly\\.tier1\\.\\d+\\.reward")) {
            int idx = Integer.parseInt(key.split("\\.")[2]);
            return normalizeWeeklyRewardKey("weekly.tier1", idx, defaultWeeklyTier1().size());
        }
        if (key.matches("weekly\\.t2\\.\\d+")) {
            int idx = Integer.parseInt(key.substring("weekly.t2.".length()));
            return normalizeWeeklyRewardKey("weekly.tier2", idx, defaultWeeklyTier2().size());
        }
        if (key.matches("weekly\\.tier2\\.\\d+\\.reward")) {
            int idx = Integer.parseInt(key.split("\\.")[2]);
            return normalizeWeeklyRewardKey("weekly.tier2", idx, defaultWeeklyTier2().size());
        }
        if (key.startsWith("weekly.mult.")) {
            String phase = key.substring("weekly.mult.".length()).replace(" 当前", "").trim();
            if (!phase.matches("P[1-6]")) {
                throw new IllegalArgumentException("QUEST_WEEKLY_MULT_INVALID");
            }
            return "weekly.mult." + phase;
        }
        if (key.matches("monthly\\.mc[0-4]\\.reward")
                || Set.of("dayOne.windowMs", "dayOne.triReward", "weekly.champBonus", "wheel.pool").contains(key)) {
            return key;
        }
        if (key.startsWith("wheel.guard.")) {
            key = "wheel.guards." + key.substring("wheel.guard.".length());
        }
        if (key.startsWith("wheel.guards.")) {
            String guard = key.substring("wheel.guards.".length());
            if (!WHEEL_GUARD_KEYS.contains(guard)) {
                throw new IllegalArgumentException("WHEEL_GUARD_INVALID");
            }
            return key;
        }
        throw new IllegalArgumentException("QUEST_CONFIG_KEY_INVALID");
    }

    private String normalizeWeeklyRewardKey(String prefix, int idx, int max) {
        if (idx < 0 || idx >= max) {
            throw new IllegalArgumentException("QUEST_WEEKLY_REWARD_INVALID");
        }
        return prefix + "." + idx + ".reward";
    }

    private String normalizeQuestValue(String key, String raw) {
        String value = normalizePlainText(raw, key.startsWith("wheel.") ? 300 : 160);
        if (key.startsWith("weekly.mult.")) {
            BigDecimal multiplier = bounded(parseDecimal(value.replace("×", "").replace("x", "")),
                    new BigDecimal("0.1"), new BigDecimal("5"));
            return multiplier.stripTrailingZeros().toPlainString() + "×";
        }
        if (key.startsWith("wheel.guards.kill")) {
            if (!Set.of("开", "关", "on", "off", "true", "false").contains(value)) {
                throw new IllegalArgumentException("WHEEL_KILL_VALUE_INVALID");
            }
            return switch (value.toLowerCase(Locale.ROOT)) {
                case "on", "true" -> "开";
                case "off", "false" -> "关";
                default -> value;
            };
        }
        return value;
    }

    private String normalizePlainText(String raw, int maxLength) {
        String value = requireText(raw, "value is required");
        if (value.length() > maxLength) {
            throw new IllegalArgumentException("VALUE_TOO_LONG");
        }
        if (value.startsWith("{") || value.startsWith("[")) {
            throw new IllegalArgumentException("MANUAL_JSON_NOT_ALLOWED");
        }
        return value;
    }

    private String questConfig(String normalizedKey) {
        return configFacade.activeValue(questConfigStorageKey(normalizedKey))
                .orElse(defaultQuestConfigValue(normalizedKey));
    }

    private String questConfigStorageKey(String key) {
        if (key.startsWith("dayOne.tasks.")) {
            int idx = Integer.parseInt(key.split("\\.")[2]);
            return QUEST_PREFIX + "day_one.task." + idx + ".reward";
        }
        if (key.startsWith("weekly.tier1.")) {
            int idx = Integer.parseInt(key.split("\\.")[2]);
            return QUEST_PREFIX + "weekly.t1." + idx + ".reward";
        }
        if (key.startsWith("weekly.tier2.")) {
            int idx = Integer.parseInt(key.split("\\.")[2]);
            return QUEST_PREFIX + "weekly.t2." + idx + ".reward";
        }
        if (key.startsWith("weekly.mult.")) {
            return QUEST_PREFIX + "weekly.mult." + key.substring("weekly.mult.".length());
        }
        if (key.startsWith("monthly.")) {
            String id = key.split("\\.")[1];
            return QUEST_PREFIX + "monthly." + id + ".reward";
        }
        if (key.startsWith("wheel.guards.")) {
            return WHEEL_PREFIX + "guard." + key.substring("wheel.guards.".length());
        }
        return switch (key) {
            case "dayOne.windowMs" -> QUEST_PREFIX + "day_one.window_ms";
            case "dayOne.triReward" -> QUEST_PREFIX + "day_one.tri_reward";
            case "weekly.champBonus" -> QUEST_PREFIX + "weekly.champ_bonus";
            case "wheel.pool" -> WHEEL_PREFIX + "pool_signature";
            default -> throw new IllegalArgumentException("QUEST_CONFIG_KEY_INVALID");
        };
    }

    private String defaultQuestConfigValue(String key) {
        if (key.startsWith("dayOne.tasks.")) {
            int idx = Integer.parseInt(key.split("\\.")[2]);
            return defaultDayOneTasks().get(idx).get("reward").toString();
        }
        if (key.startsWith("weekly.tier1.")) {
            int idx = Integer.parseInt(key.split("\\.")[2]);
            return defaultWeeklyTier1().get(idx).get("reward").toString();
        }
        if (key.startsWith("weekly.tier2.")) {
            int idx = Integer.parseInt(key.split("\\.")[2]);
            return defaultWeeklyTier2().get(idx).get("reward").toString();
        }
        if (key.startsWith("weekly.mult.")) {
            return switch (key.substring("weekly.mult.".length())) {
                case "P1", "P2" -> "1.0×";
                case "P3" -> "1.1×";
                case "P4" -> "1.2×";
                case "P5" -> "1.3×";
                case "P6" -> "1.5×";
                default -> throw new IllegalArgumentException("QUEST_CONFIG_KEY_INVALID");
            };
        }
        if (key.startsWith("monthly.")) {
            String id = key.split("\\.")[1];
            return defaultMonthlyMissions().stream()
                    .filter(row -> id.equals(row.get("id")))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("QUEST_CONFIG_KEY_INVALID"))
                    .get("reward").toString();
        }
        if (key.startsWith("wheel.guards.")) {
            return switch (key.substring("wheel.guards.".length())) {
                case "budget" -> "$2,000";
                case "cap" -> "$500×5 · $20×50 · 券×200";
                case "kill" -> "开";
                default -> throw new IllegalArgumentException("QUEST_CONFIG_KEY_INVALID");
            };
        }
        return switch (key) {
            case "dayOne.windowMs" -> "24h 全额 / 72h 宽限";
            case "dayOne.triReward" -> "500 / 200 / 0 NEX";
            case "weekly.champBonus" -> "+500 NEX × P3 1.1×";
            case "wheel.pool" -> "8 档 · EV $" + wheelEvUsd().toPlainString() + "/spin";
            default -> throw new IllegalArgumentException("QUEST_CONFIG_KEY_INVALID");
        };
    }

    private boolean amplifiesQuestValue(String key, String oldValue, String newValue) {
        if (key.startsWith("weekly.mult.")) {
            return numericToken(newValue).compareTo(numericToken(oldValue)) > 0;
        }
        if (key.equals("wheel.pool")) {
            return !oldValue.equals(newValue);
        }
        if (key.contains("reward") || key.equals("dayOne.triReward") || key.equals("weekly.champBonus")) {
            return rewardFlow(newValue).compareTo(rewardFlow(oldValue)) > 0;
        }
        return false;
    }

    private String questAuditAction(String key) {
        if (key.startsWith("wheel.guards.")) {
            return "H4_WHEEL_GUARD_CHANGED";
        }
        if (key.equals("wheel.pool")) {
            return "H4_WHEEL_POOL_CHANGED";
        }
        return "H3_QUEST_CONFIG_CHANGED";
    }

    private String questResourceType(String key) {
        return key.startsWith("wheel.") ? "WHEEL_CONFIG" : "QUEST_CONFIG";
    }

    private String questValueType(String key) {
        return "STRING";
    }

    private String weeklyMultiplier(String phase) {
        return questConfig("weekly.mult." + phase.replace(" 当前", ""));
    }

    private String normalizeEventId(String raw) {
        String id = requireText(raw, "Event id is required");
        boolean exists = defaultQuestEvents().stream().anyMatch(row -> id.equals(row.get("id")));
        if (!exists) {
            throw new IllegalArgumentException("EVENT_NOT_FOUND");
        }
        return id;
    }

    private String eventStatus(String eventId) {
        Map<String, Object> row = defaultQuestEvents().stream()
                .filter(event -> eventId.equals(event.get("id")))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("EVENT_NOT_FOUND"));
        return configFacade.activeValue(EVENT_PREFIX + eventId + ".status")
                .orElse(row.get("state").toString());
    }

    private String eventReward(String eventId) {
        Map<String, Object> row = defaultQuestEvents().stream()
                .filter(event -> eventId.equals(event.get("id")))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("EVENT_NOT_FOUND"));
        return configFacade.activeValue(EVENT_PREFIX + eventId + ".reward")
                .orElse(row.get("reward").toString());
    }

    private boolean eventFeatured(String eventId) {
        Map<String, Object> row = defaultQuestEvents().stream()
                .filter(event -> eventId.equals(event.get("id")))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("EVENT_NOT_FOUND"));
        return configFacade.activeValue(EVENT_PREFIX + eventId + ".featured")
                .map(value -> Boolean.TRUE.equals(parseBooleanValue(value)))
                .orElse(Boolean.TRUE.equals(row.get("featured")));
    }

    private BigDecimal configDecimal(String key, BigDecimal fallback) {
        return configFacade.activeValue(key)
                .flatMap(value -> Optional.ofNullable(parseDecimal(value, fallback)))
                .orElse(fallback);
    }

    private BigDecimal parseDecimal(String raw) {
        return parseDecimal(raw, null);
    }

    private BigDecimal parseDecimal(String raw, BigDecimal fallback) {
        if (!StringUtils.hasText(raw)) {
            if (fallback != null) {
                return fallback;
            }
            throw new IllegalArgumentException("Numeric value is required");
        }
        try {
            return new BigDecimal(raw.trim().replace("%", "").replace(",", ""));
        } catch (NumberFormatException ex) {
            if (fallback != null) {
                return fallback;
            }
            throw new IllegalArgumentException("Numeric value is invalid", ex);
        }
    }

    private BigDecimal ratio(BigDecimal raw) {
        return raw.compareTo(BigDecimal.ONE) > 0
                ? raw.divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP)
                : raw;
    }

    private BigDecimal bounded(BigDecimal value, BigDecimal min, BigDecimal max) {
        if (value.compareTo(min) < 0 || value.compareTo(max) > 0) {
            throw new IllegalArgumentException("Numeric value is out of range");
        }
        return value.setScale(6, RoundingMode.HALF_UP).stripTrailingZeros();
    }

    private BigDecimal wholeDays(BigDecimal value, int min, int max) {
        int days = value.setScale(0, RoundingMode.DOWN).intValue();
        if (days < min || days > max) {
            throw new IllegalArgumentException("Days value is out of range");
        }
        return BigDecimal.valueOf(days);
    }

    private BigDecimal percent(BigDecimal ratio) {
        return ratio.multiply(BigDecimal.valueOf(100)).setScale(2, RoundingMode.HALF_UP).stripTrailingZeros();
    }

    private Boolean parseBooleanValue(String raw) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "true", "1", "yes", "on", "enabled", "disabled" -> true;
            case "false", "0", "no", "off", "active" -> false;
            default -> null;
        };
    }

    private String normalizeCheckInRuleKey(String key) {
        String normalized = requireText(key, "Check-in rule key is required");
        return switch (normalized) {
            case "baseRewardNex", "baseline" -> "baseline";
            case "streakBonusNex", "bonus7" -> "bonus7";
            case "lucky15Pct", "p15" -> "p15";
            case "lucky2Pct", "p2" -> "p2";
            case "brokenHours", "broken" -> "broken";
            case "reviveCards", "saver" -> "saver";
            default -> throw new IllegalArgumentException("Unsupported H5 check-in rule");
        };
    }

    private String checkInRuleConfigKey(String key) {
        return switch (key) {
            case "baseline" -> CHECKIN_REWARD_KEY;
            case "bonus7" -> CHECKIN_STREAK_BONUS_KEY;
            case "p15" -> CHECKIN_LUCKY_15_PCT_KEY;
            case "p2" -> CHECKIN_LUCKY_2_PCT_KEY;
            case "broken" -> CHECKIN_BROKEN_HOURS_KEY;
            case "saver" -> CHECKIN_REVIVE_CARDS_KEY;
            default -> throw new IllegalArgumentException("Unsupported H5 check-in rule");
        };
    }

    private String checkInRuleValueType(String key) {
        return "saver".equals(key) ? "STRING" : "NUMBER";
    }

    private BigDecimal currentCheckInRuleNumber(String key) {
        return switch (key) {
            case "baseline" -> configDecimal(CHECKIN_REWARD_KEY, new BigDecimal("2"));
            case "bonus7" -> configDecimal(CHECKIN_STREAK_BONUS_KEY, new BigDecimal("5"));
            case "p15" -> configDecimal(CHECKIN_LUCKY_15_PCT_KEY, new BigDecimal("15"));
            case "p2" -> configDecimal(CHECKIN_LUCKY_2_PCT_KEY, new BigDecimal("5"));
            case "broken" -> configDecimal(CHECKIN_BROKEN_HOURS_KEY, new BigDecimal("48"));
            default -> throw new IllegalArgumentException("Unsupported H5 numeric check-in rule");
        };
    }

    private BigDecimal normalizeCheckInRuleNumber(String key, String raw) {
        BigDecimal value = parseDecimal(raw);
        return switch (key) {
            case "baseline", "bonus7" -> bounded(value, BigDecimal.ZERO, new BigDecimal("100000"));
            case "p15", "p2" -> bounded(value, BigDecimal.ZERO, new BigDecimal("100"));
            case "broken" -> wholeDays(value, 1, 720);
            default -> throw new IllegalArgumentException("Unsupported H5 numeric check-in rule");
        };
    }

    private BigDecimal luckyProbabilitySum(String key, BigDecimal newValue) {
        BigDecimal lucky15 = "p15".equals(key)
                ? newValue
                : configDecimal(CHECKIN_LUCKY_15_PCT_KEY, new BigDecimal("15"));
        BigDecimal lucky2 = "p2".equals(key)
                ? newValue
                : configDecimal(CHECKIN_LUCKY_2_PCT_KEY, new BigDecimal("5"));
        return lucky15.add(lucky2);
    }

    private boolean amplifiesCheckInRule(String key, BigDecimal oldValue, BigDecimal newValue) {
        return switch (key) {
            case "baseline", "bonus7", "p15", "p2" -> newValue.compareTo(oldValue) > 0;
            case "broken" -> newValue.compareTo(oldValue) < 0;
            default -> false;
        };
    }

    private BigDecimal rewardFlow(String reward) {
        if (!StringUtils.hasText(reward)) {
            return BigDecimal.ZERO;
        }
        Matcher matcher = Pattern.compile("(\\d+(?:\\.\\d+)?)").matcher(reward.replace(",", ""));
        return matcher.find() ? new BigDecimal(matcher.group(1)) : BigDecimal.ZERO;
    }

    private BigDecimal numericToken(String value) {
        if (!StringUtils.hasText(value)) {
            return BigDecimal.ZERO;
        }
        Matcher matcher = Pattern.compile("(\\d+(?:\\.\\d+)?)").matcher(value.replace(",", ""));
        return matcher.find() ? new BigDecimal(matcher.group(1)) : BigDecimal.ZERO;
    }

    private BigDecimal parseMoney(String value) {
        return numericToken(value.replace("$", ""));
    }

    private String normalizeEarnMilestoneKey(String key) {
        String normalized = requireText(key, "Earn milestone key is required");
        boolean exists = defaultEarnMilestones().stream()
                .anyMatch(row -> normalized.equals(row.get("key")));
        if (!exists) {
            throw new IllegalArgumentException("Unsupported H6 earn milestone");
        }
        return normalized;
    }

    private BigDecimal earnMilestoneThreshold(String key) {
        return configDecimal(earnThresholdConfigKey(key), defaultEarnMilestoneNumber(key, "threshold"));
    }

    private BigDecimal earnMilestoneReward(String key) {
        return configDecimal(earnRewardConfigKey(key), defaultEarnMilestoneNumber(key, "nex"));
    }

    private BigDecimal defaultEarnMilestoneNumber(String key, String field) {
        return defaultEarnMilestones().stream()
                .filter(row -> key.equals(row.get("key")))
                .findFirst()
                .map(row -> new BigDecimal(row.get(field).toString()))
                .orElseThrow(() -> new IllegalArgumentException("Unsupported H6 earn milestone"));
    }

    private boolean earnMilestonesRemainOrdered(String targetKey, BigDecimal targetThreshold) {
        BigDecimal previous = null;
        for (Map<String, Object> row : defaultEarnMilestones()) {
            String key = row.get("key").toString();
            BigDecimal threshold = key.equals(targetKey) ? targetThreshold : earnMilestoneThreshold(key);
            if (previous != null && threshold.compareTo(previous) <= 0) {
                return false;
            }
            previous = threshold;
        }
        return true;
    }

    private String earnThresholdConfigKey(String key) {
        return EARN_MS_PREFIX + key + ".threshold_usd";
    }

    private String earnRewardConfigKey(String key) {
        return EARN_MS_PREFIX + key + ".reward_nex";
    }

    private String formatSignedNex(BigDecimal value) {
        return "+" + value.stripTrailingZeros().toPlainString() + " NEX";
    }

    private String formatPercentNumber(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString() + "%";
    }

    private String requireText(String value, String message) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }

    private void audit(String action, String resourceType, String resourceId, String operator, Map<String, Object> detail) {
        auditLogService.record(AuditLogWriteRequest.builder()
                .action(action)
                .resourceType(resourceType)
                .resourceId(resourceId)
                .bizNo(resourceId)
                .actorType("ADMIN")
                .actorUsername(StringUtils.hasText(operator) ? operator.trim() : "system")
                .result("SUCCESS")
                .riskLevel("HIGH")
                .detail(detail)
                .build());
    }

    private Map<String, Object> impact(String domain, String title, String detail) {
        Map<String, Object> impact = new LinkedHashMap<>();
        impact.put("domain", domain);
        impact.put("title", title);
        impact.put("detail", detail);
        return impact;
    }

    private Map<String, Object> row(Object... pairs) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            row.put(pairs[i].toString(), pairs[i + 1]);
        }
        return row;
    }
}
