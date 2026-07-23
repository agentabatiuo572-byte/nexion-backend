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
import ffdd.opsconsole.device.domain.DeviceCatalogRepository;
import ffdd.opsconsole.device.domain.DeviceSkuView;
import ffdd.opsconsole.device.dto.DeviceSkuQueryRequest;
import ffdd.opsconsole.emergency.domain.EmergencyControlRepository;
import ffdd.opsconsole.growth.dto.GrowthConfigUpdateRequest;
import ffdd.opsconsole.growth.dto.GrowthEarnMilestoneUpdateRequest;
import ffdd.opsconsole.growth.dto.GrowthQuestEventRequest;
import ffdd.opsconsole.growth.dto.GrowthMissionRequest;
import ffdd.opsconsole.growth.dto.GrowthMonthlyMissionRequest;
import ffdd.opsconsole.growth.dto.GrowthWheelTierRequest;
import ffdd.opsconsole.growth.dto.GrowthWheelGuardRequest;
import ffdd.opsconsole.growth.dto.GrowthWheelProbabilityBatchRequest;
import ffdd.opsconsole.growth.dto.GrowthVoucherRequest;
import ffdd.opsconsole.growth.dto.ReferralSettlementRunRequest;
import ffdd.opsconsole.growth.mapper.GrowthQuestEventMapper;
import ffdd.opsconsole.growth.mapper.GrowthVoucherMapper;
import ffdd.opsconsole.platform.application.A2ReplayContext;
import ffdd.opsconsole.platform.domain.AuditReplayable;
import ffdd.opsconsole.platform.domain.AuditReplayCommand;
import ffdd.opsconsole.platform.domain.AuditReplayContext;
import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import ffdd.opsconsole.platform.mapper.AuditObjectLockMapper;
import ffdd.opsconsole.shared.seed.OpsReadTimeSeedPolicy;
import ffdd.opsconsole.treasury.facade.TreasuryCoverageFacade;
import ffdd.opsconsole.treasury.facade.TreasuryCoverageSnapshot;
import ffdd.opsconsole.treasury.facade.TreasuryLedgerPostingFacade;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.function.Function;
import org.springframework.util.StringUtils;
import org.springframework.transaction.annotation.Transactional;

@ApplicationService
@RequiredArgsConstructor
public class OpsGrowthService implements AuditReplayable {
    private static final String PHASE_CONFIG_KEY = "platform.phase.config";
    private static final String CURRENT_MONTH_KEY = "growth.phase.current_month";
    private static final String CURRENT_PHASE_KEY = "growth.phase.current";
    private static final String RHYTHM_TOTAL_MONTHS_KEY = "H1.rhythm.totalMonths";
    private static final String RHYTHM_CURRENT_MONTH_KEY = "H1.rhythm.currentMonth";
    private static final String RHYTHM_PHASE_PROGRESS_KEY = "H1.rhythm.phaseProgressPct";
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
    private static final String EARN_TICK_INTERVAL_KEY = "growth.earn_milestone.tick_interval_seconds";
    private static final String WITHDRAW_MIN_BALANCE_KEY = "growth.withdraw_nex_gate.min_balance_nex";
    private static final String WITHDRAW_HOLD_DAYS_KEY = "growth.withdraw_nex_gate.hold_days";
    private static final String WITHDRAW_MIN_BALANCE_MIRROR_KEY = "withdrawal.nex_gate.min_balance_nex";
    private static final String WITHDRAW_HOLD_DAYS_MIRROR_KEY = "withdrawal.nex_gate.hold_days";
    private static final String WITHDRAW_STAKING_DISCLOSURE_GATE_KEY = "disclosure.gate.staking";
    private static final String TRIAL_AUTO_PUSH_KILLED_KEY = "growth.trial.auto_push_killed";
    private static final String TRIAL_KILLSWITCH_KEY = "killswitch.trial";
    private static final String TRIAL_LEGACY_KILLSWITCH_KEY = "emergency.killswitch.trial";
    private static final String QUEST_PREFIX = "growth.quest.";
    private static final String WHEEL_PREFIX = "growth.wheel.";
    private static final String VOUCHER_SKUS_KEY = "growth.voucher.sku_options";
    private static final String SUNSET_EXCLUSIONS_KEY = "growth.sunset.exclusions";
    private static final Set<String> RETIRED_KEYS = Set.of(
            "withdrawPointsRatio",
            "pointsExchangeRate",
            "premiumUnlock",
            "premiumTrial",
            "nexV2Unlock",
            "nexV2LockReward");
    private static final List<String> PHASE_DIAL_KEYS = List.of(
            "newUserBonusMultiplier",
            "inviteRewardMultiplier",
            "reinvestMultiplier",
            "withdrawPenaltyFeeRate",
            "withdrawCooldownDays",
            "binaryDailyCap",
            "questBonusMultiplier",
            "complianceHoldEnabled");
    private static final List<Integer> RHYTHM_TOTAL_OPTIONS = List.of(9, 12, 15, 18, 24);
    private static final List<Integer> RHYTHM_PHASE_WEIGHTS = List.of(2, 2, 3, 1, 2, 2);
    private static final Set<String> PHASE_CONTROL_KEYS = Set.of("schedule", "pin", "override");
    private static final List<String> TRIAL_PARAM_KEYS = List.of(
            "trialDays",
            "graceDays",
            "extensionDays",
            "discountRate",
            "discountCapUSD",
            "autoChargeAtEnd",
            "highQualityThresholdUSD",
            "chargeFailRate",
            "trialProductId",
            "trialPriceUSD",
            "shadowDailyUSD",
            "shadowDailyNEX",
            "cooldownDays",
            "phaseOpen",
            "autoPushEnabled",
            "autoPushDelayMs",
            "autoPushCooldownHours",
            "autoPushMaxPerSession");
    private static final Set<String> TRIAL_TERMINAL_STATES = Set.of("cancelled", "redeemed");
    private static final Set<String> EVENT_STATES = Set.of("upcoming", "ongoing", "ended");
    private static final Set<String> EVENT_KINDS = Set.of(
            "discount", "referral", "wheel", "regional", "boost", "seasonal", "holding", "onboarding");
    private static final Set<String> WHEEL_REWARD_KINDS = Set.of("nex", "points", "usdt", "coupon");
    private static final Set<String> WHEEL_GUARD_KEYS = Set.of("budget", "cap", "kill");

    private final PlatformConfigFacade configFacade;
    private final EmergencyControlRepository emergencyRepository;
    private final TreasuryCoverageFacade coverageFacade;
    private final TreasuryLedgerPostingFacade ledgerPostingFacade;
    private final AuditLogService auditLogService;
    private final ObjectMapper objectMapper;
    private final OpsReadTimeSeedPolicy readTimeSeedPolicy;
    private final Optional<DeviceCatalogRepository> deviceCatalogRepository;
    private final Optional<GrowthQuestEventMapper> questEventMapper;
    private final Optional<GrowthVoucherMapper> voucherMapper;
    private final AuditObjectLockMapper lockMapper;

    private final Optional<OpsReferralRewardService> referralRewardService;

    public ApiResult<Map<String, Object>> phases() {
        ensurePhaseSeedData();
        int totalMonths = rhythmTotalMonths();
        int currentMonth = currentMonth();
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("domain", "H1");
        response.put("currentMonth", currentMonth);
        response.put("currentPhase", currentPhaseForRhythm(currentMonth, totalMonths));
        response.put("rhythm", rhythmOverview());
        response.put("phaseConfig", phaseConfig());
        Map<String, Object> activeDials = activeDials();
        response.put("dialCount", activeDials.size());
        response.put("activeDials", activeDials);
        response.put("monthlyDials", monthlyDials(currentMonth));
        response.put("controls", phaseControls());
        response.put("overrides", phaseOverrides());
        response.put("attribution", phaseAttribution());
        response.put("coverage", coverage());
        response.put("withdrawNexGate", withdrawGate().getData());
        response.put("retiredDials", List.copyOf(RETIRED_KEYS));
        response.put("sunsetExclusions", sunsetExclusions());
        response.put("sources", List.of("nx_config_item:" + PHASE_CONFIG_KEY, "nx_config_item:" + SUNSET_EXCLUSIONS_KEY, "nx_config_item:growth.*"));
        return ApiResult.ok(response);
    }

    public ApiResult<Map<String, Object>> phaseSandboxPreview() {
        ensurePhaseSeedData();
        int totalMonths = rhythmTotalMonths();
        int currentMonth = currentMonth();
        List<Map<String, Object>> monthlyDials = monthlyDials(currentMonth);
        int nextMonth = Math.min(monthlyDials.size(), currentMonth + 1);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("domain", "H1");
        response.put("mode", "READ_ONLY_SANDBOX");
        response.put("currentMonth", currentMonth);
        response.put("currentPhase", currentPhaseForRhythm(currentMonth, totalMonths));
        response.put("rhythm", rhythmOverview());
        response.put("coverage", coverage());
        response.put("withdrawNexGate", withdrawGate().getData());
        response.put("nextMonthDials", nextMonth > 0 ? monthlyDials.get(nextMonth - 1) : Map.of());
        response.put("impactMatrix", List.of(
                impact("D5", "提现派发", "withdrawPenaltyFeeRate / withdrawCooldownDays 由当月 H1 矩阵派发"),
                impact("B1", "兑付覆盖率红线", "放松提现、奖励或活动流出方向时低于红线返回 422"),
                impact("F3", "佣金与团队结算", "binaryDailyCap 约束双轨每日结算上限"),
                impact("H3", "任务奖励", "questBonusMultiplier 影响任务奖励倍率"),
                impact("J1", "Kill Switch", "红线或事故期恢复会付钱业务前必须先看 J1 闸门")));
        response.put("retiredDials", List.copyOf(RETIRED_KEYS));
        response.put("writes", false);
        response.put("sources", List.of("nx_config_item:growth.*", "treasury.coverage.snapshot", "withdraw_nex_gate"));
        return ApiResult.ok(response);
    }

    public ApiResult<Map<String, Object>> rhythm() {
        ensureRhythmSeedData();
        return ApiResult.ok(rhythmOverview());
    }

    @Transactional
    public ApiResult<Map<String, Object>> updateRhythmParam(
            String idempotencyKey,
            String paramKey,
            GrowthConfigUpdateRequest request) {
        ApiResult<Map<String, Object>> guard = requireCommand(idempotencyKey, request == null ? null : request.reason());
        if (guard != null) {
            return guard;
        }
        ensureRhythmSeedData();
        try {
            if (questEventMapper.isEmpty()
                    || !"H1_RHYTHM".equals(questEventMapper.get().lockGrowthMutation("H1_RHYTHM"))) {
                return validation("H1_RHYTHM_MUTEX_UNAVAILABLE");
            }
            String normalizedKey = normalizeRhythmParamKey(paramKey);
            int totalMonths = rhythmTotalMonths();
            String normalizedValue = normalizeRhythmValue(normalizedKey, request.value(), totalMonths);
            int storedCurrentMonth = storedRhythmCurrentMonth();
            String configKey = rhythmConfigKey(normalizedKey);
            if ("currentMonth".equals(normalizedKey)) {
                int nextMonth = Integer.parseInt(normalizedValue);
                if (monthTransitionAmplifiesPayout(storedCurrentMonth, nextMonth) && coverageBelowRedline()) {
                    return coverageRedline();
                }
            }
            if ("totalMonths".equals(normalizedKey)) {
                int nextTotalMonths = Integer.parseInt(normalizedValue);
                int mirroredMonth = Math.min(storedCurrentMonth, nextTotalMonths);
                if (mirroredMonth > 0
                        && monthTransitionAmplifiesPayout(storedCurrentMonth, mirroredMonth)
                        && coverageBelowRedline()) {
                    return coverageRedline();
                }
            }
            configFacade.upsertAdminValue(configKey, normalizedValue, "NUMBER", "growth", "H1 rhythm state");
            if ("currentMonth".equals(normalizedKey)) {
                int nextMonth = Integer.parseInt(normalizedValue);
                configFacade.upsertAdminValue(CURRENT_MONTH_KEY, normalizedValue, "NUMBER", "growth", "H1 rhythm current month mirror");
                configFacade.upsertAdminValue(
                        CURRENT_PHASE_KEY,
                        phaseForRhythmMonth(nextMonth, totalMonths),
                        "STRING",
                        "growth",
                        "H1 rhythm current phase mirror");
                syncActivePhaseDialsFromMonth(nextMonth);
            }
            if ("totalMonths".equals(normalizedKey)) {
                int nextTotalMonths = Integer.parseInt(normalizedValue);
                int mirroredMonth = Math.min(storedCurrentMonth, nextTotalMonths);
                if (storedCurrentMonth > nextTotalMonths) {
                    configFacade.upsertAdminValue(RHYTHM_CURRENT_MONTH_KEY, normalizedValue, "NUMBER", "growth", "H1 rhythm current month clamp");
                    configFacade.upsertAdminValue(CURRENT_MONTH_KEY, normalizedValue, "NUMBER", "growth", "H1 rhythm current month mirror");
                }
                configFacade.upsertAdminValue(
                        CURRENT_PHASE_KEY,
                        phaseForRhythmMonth(mirroredMonth, nextTotalMonths),
                        "STRING",
                        "growth",
                        "H1 rhythm current phase mirror");
                if (mirroredMonth > 0) {
                    syncActivePhaseDialsFromMonth(mirroredMonth);
                }
            }
            audit("H1_RHYTHM_CHANGED", "GROWTH_RHYTHM", configKey, request.operator(), Map.of(
                    "key", normalizedKey,
                    "value", normalizedValue,
                    "reason", request.reason().trim(),
                    "idempotencyKey", idempotencyKey.trim()));
            return rhythm();
        } catch (IllegalArgumentException ex) {
            return validation(ex.getMessage());
        }
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
        if (month < 1 || month > rhythmTotalMonths()) {
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
        if (!A2ReplayContext.isReplaying()
                && lockMapper.countActiveByTarget("H", "growth_phase_control", controlKey) > 0) {
            return ApiResult.fail(409, "OBJECT_LOCKED_BY_A2");
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
        if (!A2ReplayContext.isReplaying()
                && lockMapper.countActiveByTarget("H", "growth_phase_override", overrideId) > 0) {
            return ApiResult.fail(409, "OBJECT_LOCKED_BY_A2");
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
        ensureTrialSeedData();
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("domain", "H2");
        response.put("stats", trialStats());
        response.put("modelA", trialModelA());
        response.put("params", trialParams());
        response.put("gates", trialGates());
        response.put("states", trialStates());
        response.put("sessions", trialSessions());
        response.put("autoPushKilled", trialAutoPushKilled());
        response.put("j1TrialGate", trialKillSwitch());
        response.put("serverOnlyFields", List.of("chargeFailRate"));
        response.put("coverage", coverage());
        response.put("sources", List.of(
                "nx_growth_trial_policy",
                "nx_growth_trial_gate",
                "nx_trial_claim",
                "nx_config_item:" + TRIAL_AUTO_PUSH_KILLED_KEY,
                "nx_config_item:" + TRIAL_KILLSWITCH_KEY));
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
        if (Set.of("phaseOpen", "trialProductId").contains(key)) {
            return validation("TRIAL_PARAM_READONLY");
        }
        String value;
        try {
            value = normalizeTrialParamValue(key, request.value());
        } catch (IllegalArgumentException ex) {
            return validation(ex.getMessage());
        }
        boolean serverOnly = "chargeFailRate".equals(key);
        ensureGrowthBusinessTables();
        if (questEventMapper.isEmpty()) {
            return validation("TRIAL_POLICY_BUSINESS_TABLE_UNAVAILABLE");
        }
        questEventMapper.get().upsertTrialPolicyValue(
                key,
                value,
                trialParamValueType(key),
                trialParamHot(key),
                trialParamSection(key),
                serverOnly,
                TRIAL_PARAM_KEYS.indexOf(key));
        audit("H2_TRIAL_PARAM_CHANGED", "TRIAL_PARAM", key, request.operator(), Map.of(
                "key", key,
                "newValue", serverOnly ? "MASKED_SERVER_ONLY" : value,
                "serverOnly", serverOnly,
                "reason", request.reason().trim(),
                "idempotencyKey", idempotencyKey.trim()));
        Map<String, Object> response = trials().getData();
        response.put("updated", Map.of(
                "key", key,
                "source", "nx_growth_trial_policy",
                "value", serverOnly ? "•••(server only)" : value,
                "serverOnly", serverOnly));
        return ApiResult.ok(response);
    }

    @Transactional
    public ApiResult<Map<String, Object>> cancelTrialSession(
            String idempotencyKey,
            String sessionId,
            GrowthConfigUpdateRequest request) {
        ApiResult<Map<String, Object>> guard = requireCommand(idempotencyKey, request == null ? null : request.reason());
        if (guard != null) {
            return guard;
        }
        if (!A2ReplayContext.isReplaying()
                && lockMapper.countActiveByTarget("H", "trial_session", sessionId) > 0) {
            return ApiResult.fail(409, "OBJECT_LOCKED_BY_A2");
        }
        String sid = normalizeTrialSessionId(sessionId);
        if (questEventMapper.isEmpty()) {
            return validation("TRIAL_SESSION_BUSINESS_TABLE_UNAVAILABLE");
        }
        Map<String, Object> locked = questEventMapper.get().lockTrialSession(sid);
        if (locked == null || locked.isEmpty()) {
            return validation("TRIAL_SESSION_NOT_FOUND");
        }
        String state = String.valueOf(locked.get("state")).toLowerCase(Locale.ROOT);
        if (TRIAL_TERMINAL_STATES.contains(state)) {
            return invalidState("TRIAL_SESSION_ALREADY_TERMINAL");
        }
        if (questEventMapper.get().transitionTrialSessionStatus(sid, "CANCELLED") != 1) {
            return invalidState("TRIAL_SESSION_STATE_CONFLICT");
        }
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

    @Transactional
    public ApiResult<Map<String, Object>> chargeTrialSession(
            String idempotencyKey,
            String sessionId,
            GrowthConfigUpdateRequest request) {
        ApiResult<Map<String, Object>> guard = requireCommand(idempotencyKey, request == null ? null : request.reason());
        if (guard != null) {
            return guard;
        }
        if (!A2ReplayContext.isReplaying()
                && lockMapper.countActiveByTarget("H", "trial_session", sessionId) > 0) {
            return ApiResult.fail(409, "OBJECT_LOCKED_BY_A2");
        }
        String sid = normalizeTrialSessionId(sessionId);
        if (!trialKillSwitchEnabled()) {
            return validation("TRIAL_KILL_SWITCH_DISABLED");
        }
        if (questEventMapper.isEmpty()) {
            return validation("TRIAL_SESSION_BUSINESS_TABLE_UNAVAILABLE");
        }
        Map<String, Object> locked = questEventMapper.get().lockTrialSession(sid);
        if (locked == null || locked.isEmpty()) {
            return validation("TRIAL_SESSION_NOT_FOUND");
        }
        String state = String.valueOf(locked.get("state")).toLowerCase(Locale.ROOT);
        if (TRIAL_TERMINAL_STATES.contains(state)) {
            return invalidState("TRIAL_SESSION_ALREADY_TERMINAL");
        }
        Long userId = locked.get("userId") instanceof Number number ? number.longValue() : null;
        if (userId == null || userId <= 0) {
            return validation("TRIAL_SESSION_USER_INVALID");
        }
        BigDecimal chargeAmount = parseDecimal(String.valueOf(locked.get("chargeAmount")), BigDecimal.ZERO);
        BigDecimal walletBefore = questEventMapper.get().lockWalletUsdt(userId);
        if (walletBefore == null || walletBefore.compareTo(chargeAmount) < 0) {
            return validation("TRIAL_WALLET_INSUFFICIENT_FUNDS");
        }
        if (chargeAmount.signum() > 0) {
            if (questEventMapper.get().debitWalletUsdt(userId, chargeAmount) != 1) {
                throw new IllegalStateException("TRIAL_WALLET_DEBIT_CONFLICT");
            }
            String claimNo = String.valueOf(locked.get("claimNo"));
            if (questEventMapper.get().insertTrialChargeLedger(
                    userId,
                    "H2-TRIAL-CHARGE-" + claimNo,
                    chargeAmount,
                    walletBefore.subtract(chargeAmount),
                    sid) != 1) {
                throw new IllegalStateException("TRIAL_LEDGER_WRITE_FAILED");
            }
        }
        if (questEventMapper.get().transitionTrialSessionStatus(sid, "REDEEMED") != 1) {
            throw new IllegalStateException("TRIAL_SESSION_STATE_CONFLICT");
        }
        audit("H2_TRIAL_SESSION_CHARGED", "TRIAL_SESSION", sid, request.operator(), Map.of(
                "sessionId", sid,
                "userId", userId,
                "amountUsdt", chargeAmount,
                "balanceBefore", walletBefore,
                "balanceAfter", walletBefore.subtract(chargeAmount),
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
        ensureQuestEventSeedData();
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("domain", "H3_H4");
        response.put("rewardAsset", "NEX");
        response.put("h3Stats", questStats());
        response.put("h4Stats", eventStats());
        response.put("dayOneWindow", dayOneWindow());
        response.put("dayOneTriReward", rewardSummary(dayOneTaskRows()));
        response.put("dayOneTasks", dayOneTasks());
        response.put("dayOneStates", dayOneStates());
        response.put("weeklyTier1", weeklyTier1());
        response.put("weeklyTier2", weeklyTier2());
        response.put("weeklyChampionBonus", rewardSummary(weeklyTaskRows()));
        response.put("weeklyMultipliers", weeklyMultipliers());
        response.put("monthlyMissions", monthlyMissions());
        response.put("taskMonitor", taskMonitor());
        response.put("taskContracts", taskContracts());
        response.put("promoBanner", promoBanner());
        response.put("phaseMultiplierReadonly", phaseMultiplierReadonly());
        response.put("events", questEventsList());
        response.put("eventStates", eventStateLegend());
        response.put("wheelTiers", wheelTiers());
        response.put("wheelSignature", wheelPoolSignature());
        response.put("wheelEvUsd", wheelEvUsd());
        response.put("wheelGuards", wheelGuards());
        response.put("trackables", trackables());
        response.put("coverage", coverage());
        response.put("pointsSystemStatus", "SUNSET_HISTORY_ONLY");
        response.put("disabledOutputs", List.of("已退役权益写入", "已退役积分账本写入"));
        response.put("sources", List.of(
                "nx_mission",
                "nx_user_mission",
                "nx_monthly_challenge",
                "nx_growth_promo_banner",
                "nx_event_quest",
                "nx_user_event_quest",
                "nx_growth_wheel_tier",
                "nx_growth_wheel_guard"));
        return ApiResult.ok(response);
    }

    public ApiResult<Map<String, Object>> questTasks() {
        Map<String, Object> response = new LinkedHashMap<>(questEvents().getData());
        List.of("h4Stats", "events", "eventStates", "wheelTiers", "wheelSignature", "wheelEvUsd",
                        "wheelGuards", "trackables")
                .forEach(response::remove);
        response.put("domain", "H3");
        return ApiResult.ok(response);
    }

    public ApiResult<Map<String, Object>> questEventOverview() {
        Map<String, Object> response = new LinkedHashMap<>(questEvents().getData());
        List.of("h3Stats", "dayOneWindow", "dayOneTriReward", "dayOneTasks", "dayOneStates",
                        "weeklyTier1", "weeklyTier2", "weeklyChampionBonus", "weeklyMultipliers",
                        "monthlyMissions", "taskMonitor", "taskContracts", "promoBanner", "phaseMultiplierReadonly")
                .forEach(response::remove);
        response.put("domain", "H4");
        return ApiResult.ok(response);
    }

    @Transactional
    public ApiResult<Map<String, Object>> createQuestEvent(
            String idempotencyKey,
            GrowthQuestEventRequest request) {
        ApiResult<Map<String, Object>> guard = requireCommand(idempotencyKey, request == null ? null : request.reason());
        if (guard != null) {
            return guard;
        }
        ApiResult<Map<String, Object>> trialGate = requireTrialGateForH4Mutation();
        if (trialGate != null) {
            return trialGate;
        }
        if (questEventMapper.isEmpty()) {
            return validation("QUEST_EVENT_BUSINESS_TABLE_UNAVAILABLE");
        }
        try {
            if (!"H4_EVENT".equals(questEventMapper.get().lockGrowthMutation("H4_EVENT"))) {
                return validation("H4_EVENT_MUTEX_UNAVAILABLE");
            }
            String id = normalizeNewEventId(request.id());
            if (questEventMapper.get().countById(id) > 0) {
                return validation("EVENT_ALREADY_EXISTS");
            }
            String name = normalizePlainText(request.name(), 128);
            String kind = normalizePlainText(StringUtils.hasText(request.kind()) ? request.kind() : "discount", 64)
                    .toLowerCase(Locale.ROOT);
            if (!EVENT_KINDS.contains(kind)) {
                return validation("EVENT_KIND_INVALID");
            }
            String state = normalizeEventState(request.state());
            String reward = normalizePlainText(StringUtils.hasText(request.reward()) ? request.reward() : "0 NEX", 128);
            boolean featured = Boolean.TRUE.equals(request.featured());
            if (featured && !"ongoing".equals(state)) {
                return validation("EVENT_FEATURED_REQUIRES_ONGOING");
            }
            if (featured && questEventsList().stream()
                    .filter(row -> Boolean.TRUE.equals(row.get("featured")))
                    .filter(row -> "ongoing".equals(row.get("state")))
                    .anyMatch(row -> !id.equals(row.get("id")))) {
                return validation("EVENT_FEATURED_UNIQUE_VIOLATION");
            }
            if (rewardFlow(reward).compareTo(BigDecimal.ZERO) > 0 && coverageBelowRedline()) {
                return coverageRedline();
            }
            int sortOrder = Math.toIntExact(Math.min(Integer.MAX_VALUE, questEventRows().size() * 10L + 10L));
            questEventMapper.get().insertEvent(
                    id,
                    name,
                    normalizeEventCondition(request.condition()),
                    kind,
                    1,
                    reward.toUpperCase(Locale.ROOT).contains("USDT") ? "USDT" : "NEX",
                    rewardFlow(reward),
                    reward,
                    featured ? "FEATURED" : null,
                    sortOrder,
                    eventStatusCode(state),
                    LocalDateTime.now());
            audit("H4_EVENT_CREATED", "GROWTH_EVENT", id, request.operator(), Map.of(
                    "eventId", id,
                    "name", name,
                    "state", state,
                    "reward", reward,
                    "featured", featured,
                    "reason", request.reason().trim(),
                    "idempotencyKey", idempotencyKey.trim()));
            Map<String, Object> response = questEventOverview().getData();
            response.put("updated", Map.of("eventId", id, "action", "created"));
            return ApiResult.ok(response);
        } catch (IllegalArgumentException ex) {
            return validation(ex.getMessage());
        }
    }

    public ApiResult<Map<String, Object>> createMission(
            String idempotencyKey,
            GrowthMissionRequest request) {
        ApiResult<Map<String, Object>> guard = requireCommand(idempotencyKey, request == null ? null : request.reason());
        if (guard != null) {
            return guard;
        }
        if (questEventMapper.isEmpty()) {
            return validation("GROWTH_BUSINESS_TABLE_UNAVAILABLE");
        }
        try {
            String code = normalizePlainText(request.missionCode(), 64);
            if (!Pattern.compile("^[A-Za-z0-9_-]{2,64}$").matcher(code).matches()) {
                throw new IllegalArgumentException("MISSION_CODE_INVALID");
            }
            if (questEventMapper.get().countByMissionCode(code) > 0) {
                return validation("MISSION_ALREADY_EXISTS");
            }
            String name = normalizePlainText(request.missionName(), 128);
            String type = normalizePlainText(request.missionType(), 32).toUpperCase(Locale.ROOT);
            if (!Set.of("DAY_ONE", "WEEKLY_T1", "WEEKLY_T2").contains(type)) {
                throw new IllegalArgumentException("MISSION_TYPE_UNSUPPORTED");
            }
            int rewardPoints = request.rewardPoints() == null ? 0 : request.rewardPoints();
            if (rewardPoints < 0) {
                throw new IllegalArgumentException("MISSION_REWARD_INVALID");
            }
            if (rewardPoints > 0 && coverageBelowRedline()) {
                return coverageRedline();
            }
            questEventMapper.get().insertMission(code, name, type, rewardPoints, 1, LocalDateTime.now());
            audit("H3_MISSION_CREATED", "GROWTH_MISSION", code, request.operator(), Map.of(
                    "missionCode", code,
                    "missionName", name,
                    "missionType", type,
                    "rewardPoints", rewardPoints,
                    "reason", request.reason().trim(),
                    "idempotencyKey", idempotencyKey.trim()));
            Map<String, Object> response = questTasks().getData();
            response.put("updated", Map.of("missionCode", code, "missionType", type, "action", "created"));
            return ApiResult.ok(response);
        } catch (IllegalArgumentException ex) {
            return validation(ex.getMessage());
        }
    }

    public ApiResult<Map<String, Object>> createMonthlyMission(
            String idempotencyKey,
            GrowthMonthlyMissionRequest request) {
        ApiResult<Map<String, Object>> guard = requireCommand(idempotencyKey, request == null ? null : request.reason());
        if (guard != null) {
            return guard;
        }
        if (questEventMapper.isEmpty()) {
            return validation("GROWTH_BUSINESS_TABLE_UNAVAILABLE");
        }
        try {
            String code = normalizePlainText(request.challengeCode(), 64);
            if (!Pattern.compile("^[A-Za-z0-9_-]{2,64}$").matcher(code).matches()) {
                throw new IllegalArgumentException("CHALLENGE_CODE_INVALID");
            }
            if (questEventMapper.get().countByChallengeCode(code) > 0) {
                return validation("CHALLENGE_ALREADY_EXISTS");
            }
            String name = normalizePlainText(request.challengeName(), 128);
            String theme = request.theme() == null ? "" : request.theme().trim();
            String description = request.description() == null ? "" : request.description().trim();
            int monthsFrom = request.monthsFrom() == null ? 0 : request.monthsFrom();
            int monthsTo = request.monthsTo() == null ? 999 : request.monthsTo();
            if (monthsFrom < 0 || monthsFrom > 999 || monthsTo < monthsFrom || monthsTo > 999) {
                throw new IllegalArgumentException("CHALLENGE_AGE_RANGE_INVALID");
            }
            String targetType = normalizePlainText(request.targetType(), 64);
            int targetValue = request.targetValue() == null ? 1 : request.targetValue();
            if (targetValue < 1) {
                throw new IllegalArgumentException("CHALLENGE_TARGET_VALUE_INVALID");
            }
            String rewardType = normalizePlainText(StringUtils.hasText(request.rewardType()) ? request.rewardType() : "NEX", 32).toUpperCase(Locale.ROOT);
            BigDecimal rewardAmount = request.rewardAmount() == null ? BigDecimal.ZERO : request.rewardAmount();
            if (rewardAmount.compareTo(BigDecimal.ZERO) < 0) {
                throw new IllegalArgumentException("CHALLENGE_REWARD_AMOUNT_INVALID");
            }
            String rewardName = normalizePlainText(request.rewardName(), 128);
            if (rewardAmount.compareTo(BigDecimal.ZERO) > 0 && coverageBelowRedline()) {
                return coverageRedline();
            }
            int sortOrder = Math.toIntExact(Math.min(Integer.MAX_VALUE, growthRows(GrowthQuestEventMapper::monthlyMissions).size() * 10L + 10L));
            questEventMapper.get().insertMonthlyChallenge(code, name, description, theme, monthsFrom, monthsTo,
                    targetType, targetValue, rewardType, rewardAmount, rewardName, null, sortOrder, 1, LocalDateTime.now());
            audit("H3_MONTHLY_MISSION_CREATED", "GROWTH_MISSION", code, request.operator(), Map.of(
                    "challengeCode", code,
                    "challengeName", name,
                    "rewardAmount", rewardAmount,
                    "reason", request.reason().trim(),
                    "idempotencyKey", idempotencyKey.trim()));
            Map<String, Object> response = questTasks().getData();
            response.put("updated", Map.of("challengeCode", code, "action", "created"));
            return ApiResult.ok(response);
        } catch (IllegalArgumentException ex) {
            return validation(ex.getMessage());
        }
    }

    @Transactional
    public ApiResult<Map<String, Object>> createWheelTier(
            String idempotencyKey,
            GrowthWheelTierRequest request) {
        ApiResult<Map<String, Object>> guard = requireCommand(idempotencyKey, request == null ? null : request.reason());
        if (guard != null) {
            return guard;
        }
        if (questEventMapper.isEmpty()) {
            return validation("GROWTH_BUSINESS_TABLE_UNAVAILABLE");
        }
        try {
            if (!"H4_WHEEL".equals(questEventMapper.get().lockWheelMutation())) {
                return validation("H4_WHEEL_MUTEX_UNAVAILABLE");
            }
            String tierName = normalizePlainText(request.tierName(), 128);
            if (questEventMapper.get().countByTierName(tierName) > 0) {
                return validation("WHEEL_TIER_ALREADY_EXISTS");
            }
            if (questEventMapper.get().countActiveWheelTiers() >= 12) {
                return validation("WHEEL_TIER_COUNT_OUT_OF_RANGE");
            }
            String rewardName = normalizePlainText(request.rewardName(), 128);
            BigDecimal probability = request.probabilityPct() == null ? BigDecimal.ZERO : request.probabilityPct();
            if (probability.compareTo(BigDecimal.ZERO) < 0 || probability.compareTo(new BigDecimal("100")) > 0) {
                throw new IllegalArgumentException("WHEEL_TIER_PROBABILITY_INVALID");
            }
            BigDecimal currentProbability = Optional.ofNullable(questEventMapper.get().activeWheelProbabilitySum())
                    .orElse(BigDecimal.ZERO);
            if (currentProbability.add(probability).compareTo(new BigDecimal("100")) > 0) {
                return validation("WHEEL_PROBABILITY_TOTAL_EXCEEDS_100");
            }
            int realOutflow = (request.realOutflow() != null && request.realOutflow() == 1) ? 1 : 0;
            String rewardKind = normalizePlainText(StringUtils.hasText(request.rewardKind()) ? request.rewardKind() : "nex", 64).toLowerCase(Locale.ROOT);
            if (!WHEEL_REWARD_KINDS.contains(rewardKind)) {
                return validation("WHEEL_TIER_KIND_INVALID");
            }
            // 真实出金档位会放大资金流出,过 B1 备付金红线
            if (realOutflow == 1 && coverageBelowRedline()) {
                return coverageRedline();
            }
            int sortOrder = Math.toIntExact(Math.min(Integer.MAX_VALUE, growthRows(GrowthQuestEventMapper::wheelTiers).size() * 10L + 10L));
            questEventMapper.get().insertWheelTier(tierName, rewardName, probability, realOutflow, rewardKind, sortOrder, 1, LocalDateTime.now());
            audit("H4_WHEEL_TIER_CREATED", "WHEEL_CONFIG", tierName, request.operator(), Map.of(
                    "tierName", tierName,
                    "rewardName", rewardName,
                    "probabilityPct", probability,
                    "realOutflow", realOutflow,
                    "reason", request.reason().trim(),
                    "idempotencyKey", idempotencyKey.trim()));
            Map<String, Object> response = questEventOverview().getData();
            response.put("updated", Map.of("tierName", tierName, "action", "created"));
            return ApiResult.ok(response);
        } catch (IllegalArgumentException ex) {
            return validation(ex.getMessage());
        }
    }

    @Transactional
    public ApiResult<Map<String, Object>> updateWheelTier(
            String idempotencyKey,
            String existingTierName,
            GrowthWheelTierRequest request) {
        ApiResult<Map<String, Object>> guard = requireCommand(idempotencyKey, request == null ? null : request.reason());
        if (guard != null) {
            return guard;
        }
        if (questEventMapper.isEmpty()) {
            return validation("GROWTH_BUSINESS_TABLE_UNAVAILABLE");
        }
        try {
            if (!"H4_WHEEL".equals(questEventMapper.get().lockWheelMutation())) {
                return validation("H4_WHEEL_MUTEX_UNAVAILABLE");
            }
            String tierName = normalizePlainText(existingTierName, 128);
            Map<String, Object> before = growthRows(GrowthQuestEventMapper::wheelTiers).stream()
                    .filter(row -> tierName.equals(String.valueOf(row.get("tier"))))
                    .findFirst()
                    .orElse(null);
            if (before == null) {
                return validation("WHEEL_TIER_NOT_FOUND");
            }
            String rewardName = normalizePlainText(request.rewardName(), 128);
            BigDecimal probability = request.probabilityPct() == null
                    ? parseDecimal(String.valueOf(before.get("prob")), BigDecimal.ZERO)
                    : request.probabilityPct();
            if (probability.compareTo(BigDecimal.ZERO) < 0 || probability.compareTo(new BigDecimal("100")) > 0
                    || probability.scale() > 4) {
                return validation("WHEEL_TIER_PROBABILITY_INVALID");
            }
            BigDecimal previousProbability = parseDecimal(String.valueOf(before.get("prob")), BigDecimal.ZERO);
            BigDecimal currentTotal = Optional.ofNullable(questEventMapper.get().activeWheelProbabilitySum())
                    .orElse(BigDecimal.ZERO);
            if (currentTotal.subtract(previousProbability).add(probability).compareTo(new BigDecimal("100")) != 0) {
                return validation("WHEEL_PROBABILITY_TOTAL_MUST_EQUAL_100");
            }
            int realOutflow = request.realOutflow() != null
                    ? (request.realOutflow() == 1 ? 1 : 0)
                    : (truthy(before.get("real")) ? 1 : 0);
            String rewardKind = normalizePlainText(
                    StringUtils.hasText(request.rewardKind()) ? request.rewardKind() : String.valueOf(before.get("kind")), 64)
                    .toLowerCase(Locale.ROOT);
            if (!WHEEL_REWARD_KINDS.contains(rewardKind)) {
                return validation("WHEEL_TIER_KIND_INVALID");
            }
            boolean amplifiesRealOutflow = realOutflow == 1
                    && (!truthy(before.get("real")) || probability.compareTo(previousProbability) > 0);
            if (amplifiesRealOutflow && coverageBelowRedline()) {
                return coverageRedline();
            }
            if (questEventMapper.get().updateWheelTier(
                    tierName, rewardName, probability, realOutflow, rewardKind) != 1) {
                throw new IllegalStateException("WHEEL_TIER_UPDATE_FAILED");
            }
            audit("H4_WHEEL_TIER_CHANGED", "WHEEL_CONFIG", tierName, request.operator(), Map.of(
                    "before", before,
                    "rewardName", rewardName,
                    "probabilityPct", probability,
                    "realOutflow", realOutflow,
                    "rewardKind", rewardKind,
                    "reason", request.reason().trim(),
                    "idempotencyKey", idempotencyKey.trim()));
            Map<String, Object> response = questEventOverview().getData();
            response.put("updated", Map.of("tierName", tierName, "action", "changed"));
            return ApiResult.ok(response);
        } catch (IllegalArgumentException ex) {
            return validation(ex.getMessage());
        }
    }

    @Transactional
    public ApiResult<Map<String, Object>> deleteWheelTier(
            String idempotencyKey,
            String tierNameRaw,
            GrowthConfigUpdateRequest request) {
        ApiResult<Map<String, Object>> guard = requireCommand(idempotencyKey, request == null ? null : request.reason());
        if (guard != null) {
            return guard;
        }
        if (questEventMapper.isEmpty()) {
            return validation("GROWTH_BUSINESS_TABLE_UNAVAILABLE");
        }
        try {
            if (!"H4_WHEEL".equals(questEventMapper.get().lockWheelMutation())) {
                return validation("H4_WHEEL_MUTEX_UNAVAILABLE");
            }
            String tierName = normalizePlainText(tierNameRaw, 128);
            List<Map<String, Object>> tiers = growthRows(GrowthQuestEventMapper::wheelTiers);
            Map<String, Object> before = tiers.stream()
                    .filter(row -> tierName.equals(String.valueOf(row.get("tier"))))
                    .findFirst()
                    .orElse(null);
            if (before == null) {
                return validation("WHEEL_TIER_NOT_FOUND");
            }
            if (tiers.size() <= 2) {
                return validation("WHEEL_TIER_COUNT_OUT_OF_RANGE");
            }
            if (parseDecimal(String.valueOf(before.get("prob")), BigDecimal.ZERO).compareTo(BigDecimal.ZERO) != 0) {
                return validation("WHEEL_TIER_DELETE_REQUIRES_ZERO_PROBABILITY");
            }
            if (questEventMapper.get().softDeleteWheelTier(tierName) != 1) {
                throw new IllegalStateException("WHEEL_TIER_DELETE_FAILED");
            }
            audit("H4_WHEEL_TIER_DELETED", "WHEEL_CONFIG", tierName, request.operator(), Map.of(
                    "before", before,
                    "reason", request.reason().trim(),
                    "idempotencyKey", idempotencyKey.trim()));
            Map<String, Object> response = questEventOverview().getData();
            response.put("updated", Map.of("tierName", tierName, "action", "deleted"));
            return ApiResult.ok(response);
        } catch (IllegalArgumentException ex) {
            return validation(ex.getMessage());
        }
    }

    @Transactional
    public ApiResult<Map<String, Object>> updateWheelProbabilities(
            String idempotencyKey,
            GrowthWheelProbabilityBatchRequest request) {
        ApiResult<Map<String, Object>> guard = requireCommand(idempotencyKey, request == null ? null : request.reason());
        if (guard != null) {
            return guard;
        }
        if (questEventMapper.isEmpty()) {
            return validation("GROWTH_BUSINESS_TABLE_UNAVAILABLE");
        }
        try {
            if (!"H4_WHEEL".equals(questEventMapper.get().lockWheelMutation())) {
                return validation("H4_WHEEL_MUTEX_UNAVAILABLE");
            }
            List<Map<String, Object>> rows = growthRows(GrowthQuestEventMapper::wheelTiers);
            Map<String, BigDecimal> requested = request.probabilities() == null
                    ? Map.of() : new LinkedHashMap<>(request.probabilities());
            Set<String> activeNames = rows.stream()
                    .map(row -> String.valueOf(row.get("tier")))
                    .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
            if (requested.size() != activeNames.size() || !requested.keySet().equals(activeNames)) {
                return validation("WHEEL_TIER_SET_CHANGED");
            }
            BigDecimal total = BigDecimal.ZERO;
            boolean amplifiesRealOutflow = false;
            for (Map<String, Object> row : rows) {
                String tierName = String.valueOf(row.get("tier"));
                BigDecimal probability = requested.get(tierName);
                if (probability == null || probability.compareTo(BigDecimal.ZERO) < 0
                        || probability.compareTo(new BigDecimal("100")) > 0
                        || probability.scale() > 4) {
                    return validation("WHEEL_TIER_PROBABILITY_INVALID");
                }
                total = total.add(probability);
                BigDecimal before = parseDecimal(String.valueOf(row.get("prob")), BigDecimal.ZERO);
                if (truthy(row.get("real")) && probability.compareTo(before) > 0) {
                    amplifiesRealOutflow = true;
                }
            }
            if (total.compareTo(new BigDecimal("100")) != 0) {
                return validation("WHEEL_PROBABILITY_TOTAL_MUST_EQUAL_100");
            }
            if (amplifiesRealOutflow && coverageBelowRedline()) {
                return coverageRedline();
            }
            for (Map.Entry<String, BigDecimal> entry : requested.entrySet()) {
                if (questEventMapper.get().updateWheelTierProbability(entry.getKey(), entry.getValue()) != 1) {
                    throw new IllegalStateException("WHEEL_TIER_UPDATE_FAILED");
                }
            }
            audit("H4_WHEEL_PROBABILITIES_CHANGED", "WHEEL_CONFIG", "ACTIVE_POOL", request.operator(), Map.of(
                    "probabilities", requested,
                    "total", total,
                    "reason", request.reason().trim(),
                    "idempotencyKey", idempotencyKey.trim()));
            Map<String, Object> response = questEventOverview().getData();
            response.put("updated", Map.of("probabilities", requested, "total", total));
            return ApiResult.ok(response);
        } catch (IllegalArgumentException ex) {
            return validation(ex.getMessage());
        }
    }

    public ApiResult<Map<String, Object>> createWheelGuard(
            String idempotencyKey,
            GrowthWheelGuardRequest request) {
        ApiResult<Map<String, Object>> guard = requireCommand(idempotencyKey, request == null ? null : request.reason());
        if (guard != null) {
            return guard;
        }
        if (questEventMapper.isEmpty()) {
            return validation("GROWTH_BUSINESS_TABLE_UNAVAILABLE");
        }
        try {
            String key = normalizePlainText(request.guardKey(), 64).toLowerCase(Locale.ROOT);
            if (!Pattern.compile("^[a-z][a-z0-9_]{1,63}$").matcher(key).matches()) {
                throw new IllegalArgumentException("GUARD_KEY_INVALID");
            }
            if (questEventMapper.get().countByGuardKey(key) > 0) {
                return validation("WHEEL_GUARD_ALREADY_EXISTS");
            }
            String label = normalizePlainText(request.guardLabel(), 128);
            String value = request.guardValue() == null ? "" : request.guardValue().trim();
            String note = request.note() == null ? "" : request.note().trim();
            int sortOrder = Math.toIntExact(Math.min(Integer.MAX_VALUE, growthRows(GrowthQuestEventMapper::wheelGuards).size() * 10L + 10L));
            questEventMapper.get().insertWheelGuard(key, label, value, note, sortOrder, 1, LocalDateTime.now());
            audit("H4_WHEEL_GUARD_CREATED", "WHEEL_CONFIG", key, request.operator(), Map.of(
                    "guardKey", key,
                    "guardLabel", label,
                    "reason", request.reason().trim(),
                    "idempotencyKey", idempotencyKey.trim()));
            Map<String, Object> response = questEventOverview().getData();
            response.put("updated", Map.of("guardKey", key, "action", "created"));
            return ApiResult.ok(response);
        } catch (IllegalArgumentException ex) {
            return validation(ex.getMessage());
        }
    }

    public ApiResult<Map<String, Object>> updateQuestConfig(
            String idempotencyKey,
            String configKey,
            GrowthConfigUpdateRequest request) {
        ApiResult<Map<String, Object>> guard = requireCommand(idempotencyKey, request == null ? null : request.reason());
        if (guard != null) {
            return guard;
        }
        ensureQuestEventSeedData();
        Matcher taskReward = Pattern.compile("^dayOne\\.tasks\\.(\\d+)\\.reward$").matcher(configKey == null ? "" : configKey);
        if (taskReward.matches()) {
            int displayId = Integer.parseInt(taskReward.group(1));
            int newReward;
            try {
                newReward = bounded(parseDecimal(request.value()), BigDecimal.ZERO, new BigDecimal("100000"))
                        .setScale(0, RoundingMode.UNNECESSARY).intValueExact();
            } catch (RuntimeException ex) {
                return validation("QUEST_REWARD_INVALID");
            }
            Integer oldReward = questEventMapper.map(mapper -> mapper.missionRewardByDisplayId(displayId)).orElse(null);
            if (oldReward == null) {
                return validation("QUEST_TASK_NOT_FOUND");
            }
            if (newReward > oldReward && coverageBelowRedline()) {
                return coverageRedline();
            }
            int updated = questEventMapper.map(mapper -> mapper.updateMissionRewardByDisplayId(displayId, newReward)).orElse(0);
            if (updated != 1) {
                return validation("QUEST_TASK_UPDATE_FAILED");
            }
            audit("H3_MISSION_REWARD_CHANGED", "GROWTH_MISSION", String.valueOf(displayId), request.operator(), Map.of(
                    "oldValue", oldReward, "newValue", newReward,
                    "reason", request.reason().trim(), "idempotencyKey", idempotencyKey.trim()));
            Map<String, Object> response = questTasks().getData();
            response.put("updated", Map.of("key", configKey, "value", newReward + " NEX"));
            return ApiResult.ok(response);
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
        Map<String, Object> response = questTasks().getData();
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
        ApiResult<Map<String, Object>> trialGate = requireTrialGateForH4Mutation();
        if (trialGate != null) {
            return trialGate;
        }
        ensureQuestEventSeedData();
        String id = normalizeEventId(eventId);
        String value;
        try {
            value = normalizePlainText(request.value(), 160);
        } catch (IllegalArgumentException ex) {
            return validation(ex.getMessage());
        }
        String oldValue = eventReward(id);
        if (rewardFlow(value).compareTo(rewardFlow(oldValue)) > 0 && coverageBelowRedline()) {
            return coverageRedline();
        }
        if (!updateBusinessEventReward(id, value)) {
            return validation("QUEST_EVENT_BUSINESS_TABLE_UPDATE_FAILED");
        }
        audit("H4_EVENT_REWARD_CHANGED", "GROWTH_EVENT", id, request.operator(), Map.of(
                "eventId", id,
                "oldValue", oldValue,
                "newValue", value,
                "reason", request.reason().trim(),
                "idempotencyKey", idempotencyKey.trim()));
        Map<String, Object> response = questEventOverview().getData();
        response.put("updated", Map.of("eventId", id, "field", "reward", "value", value));
        return ApiResult.ok(response);
    }

    @Transactional
    public ApiResult<Map<String, Object>> updateQuestEventStatus(
            String idempotencyKey,
            String eventId,
            GrowthConfigUpdateRequest request) {
        ApiResult<Map<String, Object>> guard = requireCommand(idempotencyKey, request == null ? null : request.reason());
        if (guard != null) {
            return guard;
        }
        ApiResult<Map<String, Object>> trialGate = requireTrialGateForH4Mutation();
        if (trialGate != null) {
            return trialGate;
        }
        ensureQuestEventSeedData();
        if (questEventMapper.isEmpty()
                || !"H4_EVENT".equals(questEventMapper.get().lockGrowthMutation("H4_EVENT"))) {
            return validation("H4_EVENT_MUTEX_UNAVAILABLE");
        }
        String id = normalizeEventId(eventId);
        String status = requireText(request.value(), "Event status is required").toLowerCase(Locale.ROOT);
        if (!EVENT_STATES.contains(status)) {
            return validation("EVENT_STATUS_INVALID");
        }
        String oldStatus = eventStatus(id);
        if (!updateBusinessEventStatus(id, status)) {
            return validation("QUEST_EVENT_BUSINESS_TABLE_UPDATE_FAILED");
        }
        if ("ended".equals(status) && eventFeatured(id)) {
            if (!updateBusinessEventFeatured(id, false)) {
                return validation("QUEST_EVENT_BUSINESS_TABLE_UPDATE_FAILED");
            }
        }
        audit("H4_EVENT_STATUS_CHANGED", "GROWTH_EVENT", id, request.operator(), Map.of(
                "eventId", id,
                "oldStatus", oldStatus,
                "newStatus", status,
                "reason", request.reason().trim(),
                "idempotencyKey", idempotencyKey.trim()));
        Map<String, Object> response = questEventOverview().getData();
        response.put("updated", Map.of("eventId", id, "field", "status", "value", status));
        return ApiResult.ok(response);
    }

    @Transactional
    public ApiResult<Map<String, Object>> updateQuestEventFeatured(
            String idempotencyKey,
            String eventId,
            GrowthConfigUpdateRequest request) {
        ApiResult<Map<String, Object>> guard = requireCommand(idempotencyKey, request == null ? null : request.reason());
        if (guard != null) {
            return guard;
        }
        ApiResult<Map<String, Object>> trialGate = requireTrialGateForH4Mutation();
        if (trialGate != null) {
            return trialGate;
        }
        ensureQuestEventSeedData();
        String id = normalizeEventId(eventId);
        Boolean featured = parseBooleanValue(request.value());
        if (featured == null) {
            return validation("BOOLEAN_VALUE_INVALID");
        }
        if (questEventMapper.isEmpty()) {
            return validation("QUEST_EVENT_BUSINESS_TABLE_UNAVAILABLE");
        }
        if (!"H4_EVENT".equals(questEventMapper.get().lockGrowthMutation("H4_EVENT"))) {
            return validation("H4_EVENT_MUTEX_UNAVAILABLE");
        }
        questEventMapper.get().lockEventRowsForUpdate();
        if (featured && !"ongoing".equals(eventStatus(id))) {
            return validation("EVENT_FEATURED_REQUIRES_ONGOING");
        }
        if (featured) {
            // This must be a current locking read, not the read-model snapshot:
            // two commands can establish snapshots before one waits on H4_EVENT.
            if (!questEventMapper.get().featuredOngoingEventIdsForUpdate(id).isEmpty()) {
                return validation("EVENT_FEATURED_UNIQUE_VIOLATION");
            }
        }
        if (!updateBusinessEventFeatured(id, featured)) {
            return validation("QUEST_EVENT_BUSINESS_TABLE_UPDATE_FAILED");
        }
        audit("H4_EVENT_FEATURED_CHANGED", "GROWTH_EVENT", id, request.operator(), Map.of(
                "eventId", id,
                "featured", featured,
                "reason", request.reason().trim(),
                "idempotencyKey", idempotencyKey.trim()));
        Map<String, Object> response = questEventOverview().getData();
        response.put("updated", Map.of("eventId", id, "field", "featured", "value", featured));
        return ApiResult.ok(response);
    }

    public ApiResult<Map<String, Object>> checkIn() {
        ensureCheckInSeedData();
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("domain", "H5");
        response.put("rewardAsset", "NEX");
        response.put("baseRewardNex", currentCheckInValue("baseRewardNex"));
        response.put("streakBonusNex", currentCheckInValue("streakBonusNex"));
        response.put("luckyMultiplierMax", currentCheckInValue("luckyMultiplierMax"));
        response.put("stats", checkInStats());
        response.put("rules", checkInRules());
        response.put("streakMilestones", streakMilestones());
        response.put("streakDistribution", streakDistribution());
        response.put("powerUps", powerUps());
        response.put("earnMilestones", earnMilestones());
        response.put("tickInterval", tickInterval());
        response.put("coverage", coverage());
        response.put("pointsSystemStatus", "SUNSET_HISTORY_ONLY");
        response.put("disabledOutputs", List.of("已退役积分账本写入", "已退役积分兑换"));
        response.put("sources", List.of(
                "nx_growth_checkin_rule",
                "nx_daily_check_in",
                "nx_user_streak",
                "nx_streak_milestone",
                "nx_user_streak_power_up",
                "nx_earning_milestone_rule",
                "nx_earning_milestone"));
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
        String ruleKey = checkInRewardRuleKey(key);
        ensureGrowthBusinessTables();
        if (questEventMapper.isEmpty()) {
            return validation("CHECKIN_RULE_BUSINESS_TABLE_UNAVAILABLE");
        }
        questEventMapper.get().upsertCheckInRuleValue(
                ruleKey,
                newValue.toPlainString(),
                "NUMBER",
                checkInRuleHot(ruleKey),
                checkInRuleSortOrder(ruleKey));
        audit("H5_CHECKIN_NEX_CONFIG_CHANGED", "CHECKIN_NEX_REWARD", ruleKey, request.operator(), Map.of(
                "key", key,
                "oldValue", oldValue,
                "newValue", newValue,
                "reason", request.reason().trim(),
                "idempotencyKey", idempotencyKey.trim()));
        Map<String, Object> response = checkIn().getData();
        response.put("updated", Map.of("key", key, "source", "nx_growth_checkin_rule", "ruleKey", ruleKey, "oldValue", oldValue, "newValue", newValue));
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
        if (!A2ReplayContext.isReplaying()
                && lockMapper.countActiveByTarget("H", "checkin_rule", ruleKey) > 0) {
            return ApiResult.fail(409, "OBJECT_LOCKED_BY_A2");
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
        ensureGrowthBusinessTables();
        if (questEventMapper.isEmpty()) {
            return validation("CHECKIN_RULE_BUSINESS_TABLE_UNAVAILABLE");
        }
        questEventMapper.get().upsertCheckInRuleValue(
                key,
                value,
                checkInRuleValueType(key),
                checkInRuleHot(key),
                checkInRuleSortOrder(key));
        audit("H5_CHECKIN_RULE_CHANGED", "CHECKIN_RULE", key, request.operator(), Map.of(
                "key", key,
                "value", value,
                "reason", request.reason().trim(),
                "idempotencyKey", idempotencyKey.trim()));
        Map<String, Object> response = checkIn().getData();
        response.put("updated", Map.of("key", key, "source", "nx_growth_checkin_rule", "value", value));
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
        List<Map<String, Object>> rows = streakMilestones();
        Optional<Map<String, Object>> current = indexedBusinessRow(rows, milestoneId);
        if (current.isEmpty()) {
            return validation("STREAK_MILESTONE_ID_INVALID");
        }
        String reward = requireText(request.value(), "reward is required");
        BigDecimal oldFlow = rewardFlow(String.valueOf(current.get().get("reward")));
        BigDecimal newFlow = rewardFlow(reward);
        if (newFlow.compareTo(oldFlow) > 0 && coverageBelowRedline()) {
            return coverageRedline();
        }
        if (questEventMapper.isEmpty()
                || questEventMapper.get().updateStreakMilestoneReward(
                        milestoneId, reward, rewardTypeFromReward(reward), newFlow) <= 0) {
            return validation("STREAK_MILESTONE_BUSINESS_TABLE_UPDATE_FAILED");
        }
        audit("H5_STREAK_MILESTONE_CHANGED", "STREAK_MILESTONE", String.valueOf(milestoneId), request.operator(), Map.of(
                "milestoneId", milestoneId,
                "reward", reward,
                "reason", request.reason().trim(),
                "idempotencyKey", idempotencyKey.trim()));
        Map<String, Object> response = checkIn().getData();
        response.put("updated", Map.of("milestoneId", milestoneId, "source", "nx_streak_milestone", "reward", reward));
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
        List<Map<String, Object>> rows = powerUps();
        Optional<Map<String, Object>> current = indexedBusinessRow(rows, powerUpId);
        if (current.isEmpty()) {
            return validation("POWER_UP_ID_INVALID");
        }
        String key = requireText(request.key(), "Power-up key is required");
        if (!Set.of("day", "note").contains(key)) {
            return validation("POWER_UP_KEY_INVALID");
        }
        String value = requireText(request.value(), "value is required");
        int updated;
        if ("day".equals(key)) {
            BigDecimal oldDay = rowDecimal(current.get(), "day");
            BigDecimal newDay = bounded(parseDecimal(value), BigDecimal.ONE, new BigDecimal("365"));
            // Unlocking earlier increases reward exposure; delaying the unlock is restrictive.
            if (newDay.compareTo(oldDay) < 0 && coverageBelowRedline()) {
                return coverageRedline();
            }
            value = newDay.setScale(0, RoundingMode.DOWN).toPlainString();
            updated = questEventMapper.isEmpty() ? 0 : questEventMapper.get().updatePowerUpDay(powerUpId, Integer.parseInt(value));
        } else {
            updated = questEventMapper.isEmpty() ? 0 : questEventMapper.get().updatePowerUpNote(powerUpId, value);
        }
        if (updated <= 0) {
            return validation("POWER_UP_BUSINESS_TABLE_UPDATE_FAILED");
        }
        audit("H5_POWER_UP_CHANGED", "POWER_UP", String.valueOf(powerUpId), request.operator(), Map.of(
                "powerUpId", powerUpId,
                "key", key,
                "value", value,
                "reason", request.reason().trim(),
                "idempotencyKey", idempotencyKey.trim()));
        Map<String, Object> response = checkIn().getData();
        response.put("updated", Map.of("powerUpId", powerUpId, "source", "nx_streak_power_up", "key", key, "value", value));
        return ApiResult.ok(response);
    }

    @Transactional
    public ApiResult<Map<String, Object>> updateEarnMilestone(
            String idempotencyKey,
            String milestoneKey,
            GrowthEarnMilestoneUpdateRequest request) {
        ApiResult<Map<String, Object>> guard = requireCommand(idempotencyKey, request == null ? null : request.reason());
        if (guard != null) {
            return guard;
        }
        ensureGrowthBusinessTables();
        if (questEventMapper.isEmpty()
                || !"H5_MILESTONE".equals(questEventMapper.get().lockGrowthMutation("H5_MILESTONE"))) {
            return validation("H5_MILESTONE_MUTEX_UNAVAILABLE");
        }
        String key = normalizeEarnMilestoneKey(milestoneKey);
        List<Map<String, Object>> rows = earnMilestones();
        Optional<Map<String, Object>> current = earnMilestoneRow(rows, key);
        if (current.isEmpty()) {
            return validation("EARN_MILESTONE_BUSINESS_ROW_NOT_FOUND");
        }
        BigDecimal oldThreshold = earnMilestoneThreshold(current.get());
        BigDecimal oldReward = earnMilestoneReward(current.get());
        BigDecimal newThreshold = bounded(request.thresholdUsd(), BigDecimal.ONE, new BigDecimal("100000000"));
        BigDecimal newReward = bounded(request.rewardNex(), BigDecimal.ZERO, new BigDecimal("100000000"));
        if (!earnMilestonesRemainOrdered(rows, key, newThreshold)) {
            return validation("EARN_MILESTONE_THRESHOLD_ORDER_INVALID");
        }
        if ((newReward.compareTo(oldReward) > 0 || newThreshold.compareTo(oldThreshold) < 0) && coverageBelowRedline()) {
            return coverageRedline();
        }
        ensureGrowthBusinessTables();
        if (questEventMapper.isEmpty()
                || questEventMapper.get().updateEarnMilestoneRule(key, newThreshold, newReward) <= 0) {
            return validation("EARN_MILESTONE_BUSINESS_TABLE_UPDATE_FAILED");
        }
        audit("H5_EARN_MILESTONE_CHANGED", "EARN_MILESTONE", key, request.operator(), Map.of(
                "key", key,
                "oldThresholdUsd", oldThreshold,
                "newThresholdUsd", newThreshold,
                "oldRewardNex", oldReward,
                "newRewardNex", newReward,
                "reason", request.reason().trim(),
                "idempotencyKey", idempotencyKey.trim()));
        Map<String, Object> response = checkIn().getData();
        response.put("updated", Map.of(
                "key", key,
                "source", "nx_earning_milestone_rule",
                "oldThresholdUsd", oldThreshold,
                "newThresholdUsd", newThreshold,
                "oldRewardNex", oldReward,
                "newRewardNex", newReward));
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
        configFacade.upsertAdminValue(EARN_TICK_INTERVAL_KEY, value, "NUMBER", "growth", "H5 milestone tick interval");
        audit("H5_TICK_INTERVAL_CHANGED", "EARN_MILESTONE_TICK", EARN_TICK_INTERVAL_KEY, request.operator(), Map.of(
                "seconds", value,
                "reason", request.reason().trim(),
                "idempotencyKey", idempotencyKey.trim()));
        Map<String, Object> response = checkIn().getData();
        response.put("updated", Map.of("configKey", EARN_TICK_INTERVAL_KEY, "seconds", value));
        return ApiResult.ok(response);
    }

    public ApiResult<Map<String, Object>> withdrawGate() {
        ensureWithdrawGateSeedData();
        boolean disclosureGateActive = disclosureGateActive(WITHDRAW_STAKING_DISCLOSURE_GATE_KEY);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("domain", "D5_H1");
        response.put("asset", "NEX");
        response.put("enabled", !disclosureGateActive);
        response.put("blockedBy", disclosureGateActive ? "I5_DISCLOSURE_GATE" : null);
        response.put("disclosureGate", Map.of("staking", disclosureGateActive));
        response.put("minBalanceNex", configDecimal(WITHDRAW_MIN_BALANCE_KEY, new BigDecimal("100")));
        response.put("holdDays", configDecimal(WITHDRAW_HOLD_DAYS_KEY, new BigDecimal("7")).intValue());
        response.put("canonicalOwner", "H1");
        response.put("mirrors", List.of("D5 withdrawal parameters", "D2 withdrawal queue readonly"));
        response.put("retiredReplacement", "withdrawPointsRatio -> withdrawNexGate");
        response.put("sources", List.of(
                "nx_config_item:" + WITHDRAW_MIN_BALANCE_KEY,
                "nx_config_item:" + WITHDRAW_HOLD_DAYS_KEY,
                "nx_emergency_control_setting:" + WITHDRAW_STAKING_DISCLOSURE_GATE_KEY));
        return ApiResult.ok(response);
    }

    public ApiResult<Map<String, Object>> vouchers() {
        List<Map<String, Object>> rows = voucherRows();
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("domain", "H7");
        response.put("vouchers", rows);
        response.put("skus", voucherSkuOptions());
        response.put("stats", row(
                "total", rows.size(),
                "active", rows.stream().filter(voucher -> "active".equals(voucher.get("status"))).count(),
                "paused", rows.stream().filter(voucher -> "paused".equals(voucher.get("status"))).count(),
                "popup", rows.stream()
                        .filter(voucher -> "active".equals(voucher.get("status")))
                        .filter(voucher -> Boolean.TRUE.equals(voucher.get("popupEnabled")))
                        .count()));
        response.put("sources", List.of(
                "nx_growth_voucher",
                "nx_device_sku"));
        return ApiResult.ok(response);
    }

    public ApiResult<Map<String, Object>> createVoucher(String idempotencyKey, GrowthVoucherRequest request) {
        ApiResult<Map<String, Object>> guard = requireCommand(idempotencyKey, request == null ? null : request.reason());
        if (guard != null) {
            return guard;
        }
        try {
            List<Map<String, Object>> rows = new ArrayList<>(voucherRows());
            Map<String, Object> next = normalizeVoucher(request, null);
            String id = next.get("id").toString();
            if (rows.stream().anyMatch(row -> id.equals(row.get("id")))) {
                return validation("VOUCHER_ID_ALREADY_EXISTS");
            }
            if (voucherMapper.isEmpty()) {
                return validation("VOUCHER_BUSINESS_TABLE_UNAVAILABLE");
            }
            voucherMapper.get().insertVoucher(
                    id,
                    stringValue(next.get("name"), ""),
                    stringValue(next.get("type"), ""),
                    voucherNumberValue(next.get("amountUSD")),
                    voucherNumberValue(next.get("percent")),
                    voucherNumberValue(next.get("minPurchaseUSD")),
                    voucherNumberValue(next.get("maxDiscountUSD")),
                    toJsonString(normalizeStringList(castStringList(next.get("applicableSkus")), 16, 40)),
                    stringValue(next.get("audience"), ""),
                    longValue(next.get("startAt")),
                    longValue(next.get("endAt")),
                    toJsonString(normalizeStringList(castStringList(next.get("claimSurfaces")), 8, 20)),
                    Boolean.TRUE.equals(next.get("popupEnabled")),
                    Boolean.TRUE.equals(next.get("stackWithTrial")),
                    Boolean.TRUE.equals(next.get("stackWithOthers")),
                    Boolean.TRUE.equals(next.get("splittable")),
                    stringValue(next.get("status"), ""),
                    request.operator());
            audit("H7_VOUCHER_CREATED", "VOUCHER", id, request.operator(), Map.of(
                    "voucherId", id,
                    "reason", request.reason().trim(),
                    "idempotencyKey", idempotencyKey.trim()));
            Map<String, Object> response = vouchers().getData();
            response.put("updated", Map.of("voucherId", id, "action", "created"));
            return ApiResult.ok(response);
        } catch (IllegalArgumentException ex) {
            return validation(ex.getMessage());
        }
    }

    public ApiResult<Map<String, Object>> updateVoucher(String idempotencyKey, String voucherId, GrowthVoucherRequest request) {
        ApiResult<Map<String, Object>> guard = requireCommand(idempotencyKey, request == null ? null : request.reason());
        if (guard != null) {
            return guard;
        }
        try {
            String id = normalizeVoucherId(voucherId);
            List<Map<String, Object>> rows = new ArrayList<>(voucherRows());
            voucherIndex(rows, id);
            Map<String, Object> next = normalizeVoucher(request, id);
            if (voucherMapper.isEmpty()) {
                return validation("VOUCHER_BUSINESS_TABLE_UNAVAILABLE");
            }
            int updatedRows = voucherMapper.get().updateVoucher(
                    id,
                    stringValue(next.get("name"), ""),
                    stringValue(next.get("type"), ""),
                    voucherNumberValue(next.get("amountUSD")),
                    voucherNumberValue(next.get("percent")),
                    voucherNumberValue(next.get("minPurchaseUSD")),
                    voucherNumberValue(next.get("maxDiscountUSD")),
                    toJsonString(normalizeStringList(castStringList(next.get("applicableSkus")), 16, 40)),
                    stringValue(next.get("audience"), ""),
                    longValue(next.get("startAt")),
                    longValue(next.get("endAt")),
                    toJsonString(normalizeStringList(castStringList(next.get("claimSurfaces")), 8, 20)),
                    Boolean.TRUE.equals(next.get("popupEnabled")),
                    Boolean.TRUE.equals(next.get("stackWithTrial")),
                    Boolean.TRUE.equals(next.get("stackWithOthers")),
                    Boolean.TRUE.equals(next.get("splittable")),
                    stringValue(next.get("status"), ""),
                    request.operator());
            if (updatedRows == 0) {
                return validation("VOUCHER_NOT_FOUND");
            }
            audit("H7_VOUCHER_UPDATED", "VOUCHER", id, request.operator(), Map.of(
                    "voucherId", id,
                    "reason", request.reason().trim(),
                    "idempotencyKey", idempotencyKey.trim()));
            Map<String, Object> response = vouchers().getData();
            response.put("updated", Map.of("voucherId", id, "action", "updated"));
            return ApiResult.ok(response);
        } catch (IllegalArgumentException ex) {
            return validation(ex.getMessage());
        }
    }

    public ApiResult<Map<String, Object>> updateVoucherStatus(String idempotencyKey, String voucherId, GrowthConfigUpdateRequest request) {
        ApiResult<Map<String, Object>> guard = requireCommand(idempotencyKey, request == null ? null : request.reason());
        if (guard != null) {
            return guard;
        }
        try {
            String id = normalizeVoucherId(voucherId);
            String status = normalizeVoucherStatus(request.value());
            List<Map<String, Object>> rows = new ArrayList<>(voucherRows());
            voucherIndex(rows, id);
            if (voucherMapper.isEmpty()) {
                return validation("VOUCHER_BUSINESS_TABLE_UNAVAILABLE");
            }
            if (voucherMapper.get().updateStatus(id, status, request.operator()) == 0) {
                return validation("VOUCHER_NOT_FOUND");
            }
            audit("H7_VOUCHER_STATUS_CHANGED", "VOUCHER", id, request.operator(), Map.of(
                    "voucherId", id,
                    "status", status,
                    "reason", request.reason().trim(),
                    "idempotencyKey", idempotencyKey.trim()));
            Map<String, Object> response = vouchers().getData();
            response.put("updated", Map.of("voucherId", id, "status", status));
            return ApiResult.ok(response);
        } catch (IllegalArgumentException ex) {
            return validation(ex.getMessage());
        }
    }

    public ApiResult<Map<String, Object>> deleteVoucher(String idempotencyKey, String voucherId, GrowthConfigUpdateRequest request) {
        ApiResult<Map<String, Object>> guard = requireCommand(idempotencyKey, request == null ? null : request.reason());
        if (guard != null) {
            return guard;
        }
        try {
            String id = normalizeVoucherId(voucherId);
            List<Map<String, Object>> rows = new ArrayList<>(voucherRows());
            voucherIndex(rows, id);
            if (voucherMapper.isEmpty()) {
                return validation("VOUCHER_BUSINESS_TABLE_UNAVAILABLE");
            }
            if (voucherMapper.get().softDelete(id, request.operator()) == 0) {
                return validation("VOUCHER_NOT_FOUND");
            }
            audit("H7_VOUCHER_DELETED", "VOUCHER", id, request.operator(), Map.of(
                    "voucherId", id,
                    "reason", request.reason().trim(),
                    "idempotencyKey", idempotencyKey.trim()));
            Map<String, Object> response = vouchers().getData();
            response.put("updated", Map.of("voucherId", id, "action", "deleted"));
            return ApiResult.ok(response);
        } catch (IllegalArgumentException ex) {
            return validation(ex.getMessage());
        }
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
        if (dayOneTaskRows().isEmpty() && weeklyTier1Rows().isEmpty() && weeklyTier2Rows().isEmpty()
                && monthlyMissionRows().isEmpty() && !StringUtils.hasText(weeklyMultiplier("P3"))) {
            return Map.of();
        }
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("dayOneTaskCount", dayOneTaskRows().size());
        stats.put("weeklyTier1Count", weeklyTier1Rows().size());
        stats.put("weeklyTier2Count", weeklyTier2Rows().size());
        stats.put("monthlyMissionCount", monthlyMissionRows().size());
        stats.put("phaseBonusP3", weeklyMultiplier("P3"));
        return stats;
    }

    private Map<String, Object> eventStats() {
        List<Map<String, Object>> events = questEventsList();
        Map<String, Object> stats = new LinkedHashMap<>();
        long ongoing = events.stream().filter(row -> "ongoing".equals(row.get("state"))).count();
        String featured = events.stream()
                .filter(row -> Boolean.TRUE.equals(row.get("featured")))
                .map(row -> row.get("name").toString())
                .findFirst()
                .orElse("--");
        int join = events.stream().mapToInt(row -> intValue(row.get("joinCount"), 0)).sum();
        int done = events.stream().mapToInt(row -> intValue(row.get("doneCount"), 0)).sum();
        int claim = events.stream().mapToInt(row -> intValue(row.get("claimCount"), 0)).sum();
        stats.put("ongoing", ongoing);
        stats.put("featuredEv", featured);
        stats.put("trackJoin", join);
        stats.put("trackDone", done);
        stats.put("trackClaim", claim);
        return stats;
    }

    private List<Map<String, Object>> dayOneTasks() {
        return dayOneTaskRows();
    }

    private String dayOneWindow() {
        List<Map<String, Object>> rows = dayOneTaskRows();
        if (rows.isEmpty()) {
            return "";
        }
        return rows.size() + " 个首日任务定义来自 nx_mission";
    }

    private List<Map<String, Object>> dayOneTaskRows() {
        return growthRows(mapper -> mapper.missionRows("DAY_ONE"));
    }

    private List<Map<String, Object>> dayOneStates() {
        return defaultDayOneStates();
    }

    private List<Map<String, Object>> defaultDayOneStates() {
        return List.of(
                row("st", "active", "label", "ACTIVE", "tone", "ok"),
                row("st", "grace", "label", "GRACE", "tone", "warn"),
                row("st", "expired", "label", "EXPIRED", "tone", "dim"));
    }

    private List<Map<String, Object>> weeklyTier1() {
        return weeklyTier1Rows();
    }

    private List<Map<String, Object>> weeklyTier1Rows() {
        return growthRows(mapper -> mapper.missionRows("WEEKLY_T1"));
    }

    private List<Map<String, Object>> weeklyTier2() {
        return weeklyTier2Rows();
    }

    private List<Map<String, Object>> weeklyTier2Rows() {
        return growthRows(mapper -> mapper.missionRows("WEEKLY_T2"));
    }

    private List<Map<String, Object>> weeklyTaskRows() {
        List<Map<String, Object>> rows = new ArrayList<>();
        rows.addAll(weeklyTier1Rows());
        rows.addAll(weeklyTier2Rows());
        return rows;
    }

    private String rewardSummary(List<Map<String, Object>> rows) {
        return rows.stream()
                .map(row -> stringValue(row.get("reward"), ""))
                .filter(StringUtils::hasText)
                .distinct()
                .collect(java.util.stream.Collectors.joining(" / "));
    }

    private List<Map<String, Object>> weeklyMultipliers() {
        List<Map<String, Object>> rows = List.of(
                row("p", "P1", "mult", weeklyMultiplier("P1")),
                row("p", "P2", "mult", weeklyMultiplier("P2")),
                row("p", "P3 当前", "mult", weeklyMultiplier("P3")),
                row("p", "P4", "mult", weeklyMultiplier("P4")),
                row("p", "P5", "mult", weeklyMultiplier("P5")),
                row("p", "P6", "mult", weeklyMultiplier("P6")));
        boolean hasRuntimeMultiplier = rows.stream()
                .map(row -> row.get("mult"))
                .map(value -> value == null ? "" : value.toString())
                .anyMatch(StringUtils::hasText);
        return hasRuntimeMultiplier ? rows : List.of();
    }

    private List<Map<String, Object>> monthlyMissions() {
        return monthlyMissionRows();
    }

    private List<Map<String, Object>> monthlyMissionRows() {
        return growthRows(GrowthQuestEventMapper::monthlyMissions);
    }

    private Map<String, Object> promoBanner() {
        return growthMap(GrowthQuestEventMapper::promoBanner);
    }

    private List<Map<String, Object>> taskMonitor() {
        return growthRows(GrowthQuestEventMapper::taskMonitor);
    }

    private List<Map<String, Object>> taskContracts() {
        return growthRows(GrowthQuestEventMapper::taskContracts);
    }

    private Map<String, Object> phaseMultiplierReadonly() {
        int totalMonths = rhythmTotalMonths();
        int currentMonth = currentMonth();
        return row(
                "sourceDomain", "H1",
                "currentPhase", currentPhaseForRhythm(currentMonth, totalMonths),
                "value", activeDials().get("questBonusMultiplier"),
                "note", "H1 owns global quest reward multiplier; H3 only consumes it");
    }

    private List<Map<String, Object>> questEventsList() {
        return questEventRows().stream()
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

    private List<Map<String, Object>> questEventRows() {
        return businessQuestEventRows();
    }

    private List<Map<String, Object>> businessQuestEventRows() {
        if (questEventMapper.isEmpty()) {
            return List.of();
        }
        return questEventMapper.get().listEvents().stream()
                .map(row -> {
                    Map<String, Object> event = new LinkedHashMap<>();
                    event.put("id", stringValue(row.get("id"), ""));
                    event.put("name", stringValue(row.get("name"), ""));
                    event.put("kind", stringValue(row.get("kind"), "EVENT_ACTIONS"));
                    event.put("state", stringValue(row.get("state"), "ongoing"));
                    event.put("reward", stringValue(row.get("reward"), "0 NEX"));
                    event.put("featured", truthy(row.get("featured")));
                    event.put("trackable", truthy(row.get("trackable")));
                    event.put("condition", stringValue(row.get("condition"), ""));
                    event.put("geo", stringValue(row.get("geo"), ""));
                    event.put("joinCount", intValue(row.get("joinCount"), 0));
                    event.put("doneCount", intValue(row.get("doneCount"), 0));
                    event.put("claimCount", intValue(row.get("claimCount"), 0));
                    return event;
                })
                .filter(row -> StringUtils.hasText(String.valueOf(row.get("id"))))
                .toList();
    }

    private List<Map<String, Object>> eventStateLegend() {
        return defaultEventStateLegend();
    }

    private List<Map<String, Object>> defaultEventStateLegend() {
        return List.of(
                row("state", "upcoming", "label", "预告", "tone", "dim"),
                row("state", "ongoing", "label", "进行中", "tone", "ok"),
                row("state", "ended", "label", "已结束", "tone", "dim"));
    }

    private List<Map<String, Object>> wheelTiers() {
        return growthRows(GrowthQuestEventMapper::wheelTiers);
    }

    private BigDecimal wheelEvUsd() {
        BigDecimal value = wheelTiers().stream()
                .filter(row -> Boolean.TRUE.equals(row.get("real")))
                .map(row -> parseMoney(row.get("reward").toString()).multiply(new BigDecimal(row.get("prob").toString()))
                        .divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP))
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP)
                .stripTrailingZeros();
        return new BigDecimal(value.toPlainString());
    }

    private String wheelPoolSignature() {
        List<Map<String, Object>> tiers = wheelTiers();
        if (tiers.isEmpty()) {
            return "";
        }
        return tiers.size() + " 档 · EV $" + wheelEvUsd().toPlainString() + "/spin";
    }

    private List<Map<String, Object>> wheelGuards() {
        return growthRows(GrowthQuestEventMapper::wheelGuards);
    }

    private List<Map<String, Object>> trackables() {
        return questEventsList().stream()
                .filter(event -> truthy(event.get("trackable")))
                .map(event -> row(
                        "id", event.get("id"),
                        "name", event.get("name"),
                        "cond", event.get("condition"),
                        "join", intValue(event.get("joinCount"), 0),
                        "done", intValue(event.get("doneCount"), 0),
                        "claim", intValue(event.get("claimCount"), 0),
                        "geo", event.get("geo")))
                .toList();
    }

    private Map<String, Object> trialStats() {
        Map<String, Object> row = growthMap(GrowthQuestEventMapper::trialStats);
        if (row.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("activeSessions", intValue(row.get("activeSessions"), 0));
        stats.put("inTrial", intValue(row.get("inTrial"), 0));
        stats.put("inGrace", intValue(row.get("inGrace"), 0));
        stats.put("inExtended", intValue(row.get("inExtended"), 0));
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
        return growthRows(GrowthQuestEventMapper::listTrialPolicies).stream()
                .map(row -> {
                    Map<String, Object> copy = new LinkedHashMap<>(row);
                    if (truthy(copy.get("serverOnly"))) {
                        copy.put("cur", "•••(server only)");
                    }
                    return copy;
                })
                .toList();
    }

    private String trialParamCurrentValue(String key, String fallback) {
        if ("chargeFailRate".equals(key)) {
            return "•••(server only)";
        }
        if (questEventMapper.isEmpty()) {
            return "";
        }
        try {
            return Optional.ofNullable(questEventMapper.get().trialPolicyValue(key))
                    .filter(StringUtils::hasText)
                    .orElse("");
        } catch (RuntimeException ex) {
            return "";
        }
    }

    private List<Map<String, Object>> trialGates() {
        return growthRows(GrowthQuestEventMapper::trialGates);
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
        return growthRows(mapper -> mapper.trialSessions(100));
    }

    private boolean trialAutoPushKilled() {
        return Boolean.TRUE.equals(parseBooleanValue(configFacade.activeValue(TRIAL_AUTO_PUSH_KILLED_KEY).orElse("false")));
    }

    private Map<String, Object> trialKillSwitch() {
        boolean enabled = trialKillSwitchEnabled();
        Map<String, Object> gate = new LinkedHashMap<>();
        gate.put("ownerDomain", "J1");
        gate.put("configKey", TRIAL_KILLSWITCH_KEY);
        gate.put("enabled", enabled);
        gate.put("blockedBy", enabled ? null : "J1_TRIAL_KILL_SWITCH");
        return gate;
    }

    private boolean trialKillSwitchEnabled() {
        return ffdd.opsconsole.emergency.domain.KillSwitchState.enabled(
                controlValue(TRIAL_KILLSWITCH_KEY),
                controlValue(TRIAL_LEGACY_KILLSWITCH_KEY));
    }

    private ApiResult<Map<String, Object>> requireTrialGateForH4Mutation() {
        if (!trialKillSwitchEnabled()) {
            return validation("J1_TRIAL_KILLSWITCH_DISABLED");
        }
        return null;
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
        if ("chargeFailRate".equals(key)) {
            return bounded(parseDecimal(value), BigDecimal.ZERO, new BigDecimal("100")).toPlainString();
        }
        if (isTrialDayParam(key)) {
            return normalizeTrialDayParam(key, value).toPlainString();
        }
        if (Set.of("autoChargeAtEnd", "phaseOpen", "autoPushEnabled").contains(key)) {
            return normalizeTrialAutoCharge(value);
        }
        if (Set.of("discountRate").contains(key)) {
            return bounded(parseDecimal(value.replace("%", "")), BigDecimal.ZERO, new BigDecimal("100")).stripTrailingZeros().toPlainString();
        }
        if (Set.of("discountCapUSD", "highQualityThresholdUSD", "trialPriceUSD", "shadowDailyUSD", "shadowDailyNEX").contains(key)) {
            return bounded(parseDecimal(value.replace("$", "").replace(",", "")), BigDecimal.ZERO, new BigDecimal("1000000")).stripTrailingZeros().toPlainString();
        }
        if (Set.of("autoPushDelayMs", "autoPushCooldownHours", "autoPushMaxPerSession").contains(key)) {
            return wholeDays(parseDecimal(value), 0, 86400000).toPlainString();
        }
        return value;
    }

    private boolean isTrialDayParam(String key) {
        return Set.of("trialDays", "graceDays", "extensionDays", "cooldownDays").contains(key);
    }

    private BigDecimal normalizeTrialDayParam(String key, String raw) {
        BigDecimal value = parseDecimal(raw);
        return switch (key) {
            case "trialDays" -> wholeDays(value, 1, 90);
            case "graceDays", "extensionDays" -> wholeDays(value, 0, 30);
            case "cooldownDays" -> wholeDays(value, 0, 365);
            default -> throw new IllegalArgumentException("Unsupported H2 trial day parameter");
        };
    }

    private String normalizeTrialAutoCharge(String value) {
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "开", "on", "true", "1", "enabled" -> "开";
            case "关", "off", "false", "0", "disabled" -> "关";
            default -> throw new IllegalArgumentException("AUTO_CHARGE_VALUE_INVALID");
        };
    }

    private String normalizeTrialSessionId(String sessionId) {
        String sid = requireText(sessionId, "Trial session id is required");
        if (!sid.matches("[A-Za-z0-9_-]+")) {
            throw new IllegalArgumentException("TRIAL_SESSION_ID_INVALID");
        }
        if (questEventMapper.isEmpty() || questEventMapper.get().trialSession(sid) == null) {
            throw new IllegalArgumentException("TRIAL_SESSION_NOT_FOUND");
        }
        return sid;
    }

    private String trialSessionState(String sid) {
        if (questEventMapper.isEmpty()) {
            return "idle";
        }
        return Optional.ofNullable(questEventMapper.get().trialSession(sid))
                .map(row -> stringValue(row.get("state"), "idle").toLowerCase(Locale.ROOT))
                .orElse("idle");
    }

    private Map<String, Object> checkInStats() {
        BigDecimal lucky15Pct = currentCheckInRuleNumber("p15");
        BigDecimal lucky2Pct = currentCheckInRuleNumber("p2");
        Map<String, Object> stats = new LinkedHashMap<>(growthMap(GrowthQuestEventMapper::checkInStats));
        stats.put("lucky15Config", lucky15Pct.stripTrailingZeros().toPlainString() + "%");
        stats.put("lucky2Config", lucky2Pct.stripTrailingZeros().toPlainString() + "%");
        return stats;
    }

    private List<Map<String, Object>> checkInRules() {
        return growthRows(GrowthQuestEventMapper::checkInRules);
    }

    private List<Map<String, Object>> streakMilestones() {
        return growthRows(GrowthQuestEventMapper::streakMilestones);
    }

    private List<Map<String, Object>> streakDistribution() {
        return growthRows(GrowthQuestEventMapper::streakDistribution);
    }

    private Map<String, Object> streakDistribution(String day, String count, int height) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("day", day);
        row.put("count", count);
        row.put("height", height);
        return row;
    }

    private List<Map<String, Object>> powerUps() {
        return growthRows(GrowthQuestEventMapper::powerUps);
    }

    private List<Map<String, Object>> earnMilestones() {
        return growthRows(GrowthQuestEventMapper::earnMilestones);
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
            return Map.of();
        }
        try {
            return objectMapper.readValue(raw.get(), new TypeReference<Map<String, Object>>() {
            });
        } catch (JsonProcessingException ex) {
            return Map.of("invalidSource", PHASE_CONFIG_KEY);
        }
    }

    private Map<String, Object> defaultPhaseConfig() {
        Map<String, Object> defaults = new LinkedHashMap<>();
        defaults.put("calendar", "12_MONTH");
        defaults.put("activePhase", "P1");
        defaults.put("activeDialCount", PHASE_DIAL_KEYS.size());
        return defaults;
    }

    private void ensurePhaseSeedData() {
        if (readTimeBusinessSeedsDisabled()) {
            return;
        }
        ensureRhythmSeedData();
        seedJsonIfMissing(PHASE_CONFIG_KEY, defaultPhaseConfig(), "platform", "H1 phase config seed");
        seedIfMissing(CURRENT_PHASE_KEY, phaseForRhythmMonth(rhythmCurrentMonth(rhythmTotalMonths()), rhythmTotalMonths()), "STRING", "growth", "H1 current phase seed");
        for (String key : PHASE_DIAL_KEYS) {
            seedIfMissing(phaseDialConfigKey(key), defaultPhaseDialValue(key).toPlainString(), "NUMBER", "growth", "H1 active phase dial seed");
        }
        for (int month = 1; month <= rhythmTotalMonths(); month++) {
            for (String key : PHASE_DIAL_KEYS) {
                seedIfMissing(monthDialConfigKey(month, key), defaultPhaseMonthDialValue(month, key).toPlainString(), "NUMBER", "growth", "H1 monthly phase dial seed");
            }
        }
        seedIfMissing(CONTROL_PREFIX + "schedule", "按 12 个月增长曲线自动推进", "STRING", "growth", "H1 phase control seed");
        seedIfMissing(CONTROL_PREFIX + "pin", "P3", "STRING", "growth", "H1 phase control seed");
        seedIfMissing(CONTROL_PREFIX + "override", "启用 cohort override", "STRING", "growth", "H1 phase control seed");
        for (Map<String, Object> override : phaseOverrides()) {
            seedIfMissing(OVERRIDE_PREFIX + override.get("id") + ".disabled", "false", "BOOLEAN", "growth", "H1 phase override seed");
        }
        ensureWithdrawGateSeedData();
    }

    private void ensureGrowthBusinessTables() {
        questEventMapper.ifPresent(mapper -> {
            mapper.createGrowthTrialPolicyTable();
            mapper.createGrowthTrialGateTable();
            mapper.createGrowthCheckinRuleTable();
            mapper.createGrowthWheelTierTable();
            mapper.createGrowthWheelGuardTable();
            mapper.createGrowthPromoBannerTable();
        });
    }

    private List<Map<String, Object>> growthRows(Function<GrowthQuestEventMapper, List<Map<String, Object>>> reader) {
        if (questEventMapper.isEmpty()) {
            return List.of();
        }
        try {
            List<Map<String, Object>> rows = reader.apply(questEventMapper.get());
            return rows == null ? List.of() : rows;
        } catch (RuntimeException ex) {
            return List.of();
        }
    }

    private Map<String, Object> growthMap(Function<GrowthQuestEventMapper, Map<String, Object>> reader) {
        if (questEventMapper.isEmpty()) {
            return Map.of();
        }
        try {
            Map<String, Object> row = reader.apply(questEventMapper.get());
            return row == null ? Map.of() : row;
        } catch (RuntimeException ex) {
            return Map.of();
        }
    }

    private void ensureTrialSeedData() {
        if (readTimeBusinessSeedsDisabled()) {
            return;
        }
        seedIfMissing(TRIAL_AUTO_PUSH_KILLED_KEY, "false", "BOOLEAN", "growth", "H2 auto-push seed");
    }

    private String trialParamValueType(String key) {
        return Set.of("discountRate", "discountCapUSD", "highQualityThresholdUSD", "chargeFailRate",
                "trialPriceUSD", "shadowDailyUSD", "shadowDailyNEX", "autoPushDelayMs",
                "autoPushCooldownHours", "autoPushMaxPerSession").contains(key) || isTrialDayParam(key) ? "NUMBER" : "STRING";
    }

    private boolean trialParamHot(String key) {
        return Set.of("trialPriceUSD", "chargeFailRate", "autoChargeAtEnd").contains(key);
    }

    private String trialParamSection(String key) {
        return Set.of("trialDays", "graceDays", "extensionDays", "autoChargeAtEnd", "trialProductId",
                "trialPriceUSD", "shadowDailyUSD", "shadowDailyNEX").contains(key) ? "newonly" : "live";
    }

    private void ensureQuestEventSeedData() {
        if (readTimeBusinessSeedsDisabled()) {
            return;
        }
        if (!readTimeSeedPolicy.enabled()) {
            return;
        }
        seedIfMissing(questConfigStorageKey("dayOne.windowMs"), defaultQuestConfigValue("dayOne.windowMs"), "STRING", "growth", "H3 day-one seed");
        seedIfMissing(questConfigStorageKey("dayOne.triReward"), defaultQuestConfigValue("dayOne.triReward"), "STRING", "growth", "H3 day-one seed");
        seedIfMissing(questConfigStorageKey("weekly.champBonus"), defaultQuestConfigValue("weekly.champBonus"), "STRING", "growth", "H3 weekly champion seed");
        for (String phase : List.of("P1", "P2", "P3", "P4", "P5", "P6")) {
            String key = "weekly.mult." + phase;
            seedIfMissing(questConfigStorageKey(key), defaultQuestConfigValue(key), "STRING", "growth", "H3 weekly multiplier seed");
        }
        seedIfMissing(questConfigStorageKey("wheel.pool"), defaultQuestConfigValue("wheel.pool"), "STRING", "growth", "H4 wheel seed");
        for (String guard : WHEEL_GUARD_KEYS) {
            String key = "wheel.guards." + guard;
            seedIfMissing(questConfigStorageKey(key), defaultQuestConfigValue(key), "STRING", "growth", "H4 wheel guard seed");
        }
    }

    private void ensureCheckInSeedData() {
        if (readTimeBusinessSeedsDisabled()) {
            return;
        }
        seedIfMissing(CHECKIN_REWARD_KEY, "2", "NUMBER", "growth", "H5 check-in seed");
        seedIfMissing(CHECKIN_STREAK_BONUS_KEY, "5", "NUMBER", "growth", "H5 check-in seed");
        seedIfMissing(CHECKIN_LUCKY_MULTIPLIER_KEY, "2", "NUMBER", "growth", "H5 check-in seed");
        seedIfMissing(CHECKIN_LUCKY_15_PCT_KEY, "15", "NUMBER", "growth", "H5 check-in seed");
        seedIfMissing(CHECKIN_LUCKY_2_PCT_KEY, "5", "NUMBER", "growth", "H5 check-in seed");
        seedIfMissing(CHECKIN_BROKEN_HOURS_KEY, "48", "NUMBER", "growth", "H5 check-in seed");
        seedIfMissing(CHECKIN_REVIVE_CARDS_KEY, "1 张 / 30 天", "STRING", "growth", "H5 check-in seed");
        seedIfMissing(EARN_TICK_INTERVAL_KEY, "4", "NUMBER", "growth", "H5 milestone tick interval seed");
    }

    private void ensureWithdrawGateSeedData() {
        if (readTimeBusinessSeedsDisabled()) {
            return;
        }
        seedIfMissing(WITHDRAW_MIN_BALANCE_KEY, "100", "NUMBER", "growth", "H1 withdraw NEX gate seed");
        seedIfMissing(WITHDRAW_HOLD_DAYS_KEY, "7", "NUMBER", "growth", "H1 withdraw NEX gate seed");
        seedIfMissing(WITHDRAW_MIN_BALANCE_MIRROR_KEY, "100", "NUMBER", "wallet", "D5 mirror of H1 withdraw NEX gate seed");
        seedIfMissing(WITHDRAW_HOLD_DAYS_MIRROR_KEY, "7", "NUMBER", "wallet", "D5 mirror of H1 withdraw NEX gate seed");
    }

    private void seedIfMissing(String key, String value, String type, String group, String remark) {
        // Intentionally empty: read paths must not initialize business configuration.
    }

    private void seedJsonIfMissing(String key, Object value, String group, String remark) {
        // Intentionally empty: read paths must not initialize business configuration.
    }

    private void backfillJsonRows(String key, List<Map<String, Object>> defaults, String remark) {
        // Intentionally empty: read paths must not backfill business rows.
    }

    private boolean backfillJsonRowsByIdentity(String key, List<Map<String, Object>> rows, List<Map<String, Object>> defaults) {
        String identityField = jsonRowIdentityField(key);
        if (!StringUtils.hasText(identityField)) {
            return backfillJsonRowsByPosition(rows, defaults);
        }
        Map<String, Map<String, Object>> existingById = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            Object id = row.get(identityField);
            if (id != null) {
                existingById.putIfAbsent(id.toString(), row);
            }
        }

        boolean changed = false;
        for (Map<String, Object> defaultRow : defaults) {
            Object id = defaultRow.get(identityField);
            if (id == null) {
                continue;
            }
            Map<String, Object> row = existingById.get(id.toString());
            if (row == null) {
                Map<String, Object> copy = new LinkedHashMap<>(defaultRow);
                rows.add(copy);
                existingById.put(id.toString(), copy);
                changed = true;
                continue;
            }
            changed |= backfillJsonRow(row, defaultRow);
        }
        return changed;
    }

    private boolean backfillJsonRowsByPosition(List<Map<String, Object>> rows, List<Map<String, Object>> defaults) {
        boolean changed = false;
        for (int i = 0; i < defaults.size(); i++) {
            if (i >= rows.size()) {
                rows.add(new LinkedHashMap<>(defaults.get(i)));
                changed = true;
                continue;
            }
            changed |= backfillJsonRow(rows.get(i), defaults.get(i));
        }
        return changed;
    }

    private boolean backfillJsonRow(Map<String, Object> row, Map<String, Object> defaults) {
        boolean changed = false;
        for (Map.Entry<String, Object> entry : defaults.entrySet()) {
            if (!row.containsKey(entry.getKey()) || row.get(entry.getKey()) == null) {
                row.put(entry.getKey(), entry.getValue());
                changed = true;
            }
        }
        return changed;
    }

    private String jsonRowIdentityField(String key) {
        return "";
    }

    private void backfillJsonMap(String key, Map<String, Object> defaults, String remark) {
        // Intentionally empty: read paths must not backfill business rows.
    }

    private void writeJsonConfig(String key, Object value, String remark) {
        writeJsonConfig(key, value, remark, "growth");
    }

    private void writeJsonConfig(String key, Object value, String remark, String group) {
        try {
            configFacade.upsertAdminValue(key, objectMapper.writeValueAsString(value), "JSON", group, remark);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("GROWTH_CONFIG_JSON_SERIALIZE_FAILED", ex);
        }
    }

    private void ensureRhythmSeedData() {
        if (readTimeBusinessSeedsDisabled()) {
            return;
        }
        seedRhythmValue(RHYTHM_TOTAL_MONTHS_KEY, "12");
        seedRhythmValue(RHYTHM_CURRENT_MONTH_KEY, "7");
        seedRhythmValue(RHYTHM_PHASE_PROGRESS_KEY, "58");
        if (readTimeSeedPolicy.enabled() && configFacade.activeValue(CURRENT_MONTH_KEY).isEmpty()) {
            configFacade.upsertAdminValue(CURRENT_MONTH_KEY, "7", "NUMBER", "growth", "H1 rhythm current month mirror");
        }
    }

    private void seedRhythmValue(String key, String value) {
        // Intentionally empty: read paths must not initialize rhythm configuration.
    }

    private boolean readTimeBusinessSeedsDisabled() {
        return true;
    }

    private Map<String, Object> rhythmOverview() {
        int totalMonths = rhythmTotalMonths();
        int currentMonth = rhythmCurrentMonth(totalMonths);
        int phaseProgressPct = rhythmPhaseProgressPct();
        String currentPhase = currentPhaseForRhythm(currentMonth, totalMonths);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("domain", "H1");
        response.put("totalMonths", totalMonths);
        response.put("currentMonth", currentMonth);
        response.put("currentPhase", currentPhase);
        response.put("phaseProgressPct", phaseProgressPct);
        response.put("options", RHYTHM_TOTAL_OPTIONS);
        response.put("sources", List.of(
                "nx_config_item:" + RHYTHM_TOTAL_MONTHS_KEY,
                "nx_config_item:" + RHYTHM_CURRENT_MONTH_KEY,
                "nx_config_item:" + RHYTHM_PHASE_PROGRESS_KEY,
                "nx_config_item:" + CURRENT_MONTH_KEY));
        return response;
    }

    private int rhythmTotalMonths() {
        int total = configDecimal(RHYTHM_TOTAL_MONTHS_KEY, new BigDecimal("12"))
                .setScale(0, RoundingMode.DOWN)
                .intValue();
        return RHYTHM_TOTAL_OPTIONS.contains(total) ? total : 0;
    }

    private int storedRhythmCurrentMonth() {
        return configDecimal(RHYTHM_CURRENT_MONTH_KEY, configDecimal(CURRENT_MONTH_KEY, new BigDecimal("7")))
                .setScale(0, RoundingMode.DOWN)
                .intValue();
    }

    private int rhythmCurrentMonth(int totalMonths) {
        if (totalMonths < 1) {
            return 0;
        }
        int month = storedRhythmCurrentMonth();
        if (month < 1) {
            return 0;
        }
        return Math.min(month, totalMonths);
    }

    private int rhythmPhaseProgressPct() {
        int progress = configDecimal(RHYTHM_PHASE_PROGRESS_KEY, new BigDecimal("58"))
                .setScale(0, RoundingMode.DOWN)
                .intValue();
        return Math.max(0, Math.min(100, progress));
    }

    private String normalizeRhythmParamKey(String key) {
        String normalized = requireText(key, "Rhythm parameter key is required");
        return switch (normalized) {
            case "totalMonths", "H1.rhythm.totalMonths" -> "totalMonths";
            case "currentMonth", "H1.rhythm.currentMonth" -> "currentMonth";
            case "phaseProgressPct", "H1.rhythm.phaseProgressPct" -> "phaseProgressPct";
            default -> throw new IllegalArgumentException("RHYTHM_PARAM_KEY_INVALID");
        };
    }

    private String rhythmConfigKey(String key) {
        return switch (key) {
            case "totalMonths" -> RHYTHM_TOTAL_MONTHS_KEY;
            case "currentMonth" -> RHYTHM_CURRENT_MONTH_KEY;
            case "phaseProgressPct" -> RHYTHM_PHASE_PROGRESS_KEY;
            default -> throw new IllegalArgumentException("RHYTHM_PARAM_KEY_INVALID");
        };
    }

    private String normalizeRhythmValue(String key, String raw, int totalMonths) {
        int value = parseDecimal(raw)
                .setScale(0, RoundingMode.DOWN)
                .intValue();
        return switch (key) {
            case "totalMonths" -> {
                if (!RHYTHM_TOTAL_OPTIONS.contains(value)) {
                    throw new IllegalArgumentException("RHYTHM_TOTAL_MONTHS_INVALID");
                }
                yield String.valueOf(value);
            }
            case "currentMonth" -> {
                if (value < 1 || value > totalMonths) {
                    throw new IllegalArgumentException("RHYTHM_CURRENT_MONTH_OUT_OF_RANGE");
                }
                yield String.valueOf(value);
            }
            case "phaseProgressPct" -> {
                if (value < 0 || value > 100) {
                    throw new IllegalArgumentException("RHYTHM_PHASE_PROGRESS_OUT_OF_RANGE");
                }
                yield String.valueOf(value);
            }
            default -> throw new IllegalArgumentException("RHYTHM_PARAM_KEY_INVALID");
        };
    }

    private Map<String, Object> activeDials() {
        Map<String, Object> dials = new LinkedHashMap<>();
        int month = currentMonth();
        if (month < 1) {
            return dials;
        }
        for (String key : PHASE_DIAL_KEYS) {
            dials.put(key, displayPhaseDialValue(key, monthDialValue(month, key)));
        }
        return dials;
    }

    private int currentMonth() {
        int totalMonths = rhythmTotalMonths();
        if (totalMonths < 1) {
            return 0;
        }
        int month = storedRhythmCurrentMonth();
        if (month < 1) {
            return 0;
        }
        return Math.min(month, totalMonths);
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

    private String phaseForRhythmMonth(int month, int totalMonths) {
        if (totalMonths < 1 || month < 1) {
            return "";
        }
        if (totalMonths == 12) {
            return phaseForMonth(month);
        }
        int clampedMonth = Math.max(1, Math.min(month, totalMonths));
        int weightSum = RHYTHM_PHASE_WEIGHTS.stream().mapToInt(Integer::intValue).sum();
        int accumulatedWeight = 0;
        int previousEnd = 0;
        for (int i = 0; i < RHYTHM_PHASE_WEIGHTS.size(); i++) {
            accumulatedWeight += RHYTHM_PHASE_WEIGHTS.get(i);
            int phaseEnd = Math.round((float) accumulatedWeight / weightSum * totalMonths);
            phaseEnd = Math.max(previousEnd, phaseEnd);
            if (clampedMonth <= phaseEnd) {
                return "P" + (i + 1);
            }
            previousEnd = phaseEnd;
        }
        return "P6";
    }

    private String currentPhaseForRhythm(int currentMonth, int totalMonths) {
        return configFacade.activeValue(CURRENT_PHASE_KEY)
                .filter(StringUtils::hasText)
                .orElse("");
    }

    private List<Map<String, Object>> monthlyDials(int currentMonth) {
        java.util.ArrayList<Map<String, Object>> rows = new java.util.ArrayList<>();
        int totalMonths = rhythmTotalMonths();
        for (int month = 1; month <= totalMonths; month++) {
            Map<String, Object> dials = new LinkedHashMap<>();
            for (String key : PHASE_DIAL_KEYS) {
                dials.put(key, displayPhaseDialValue(key, monthDialValue(month, key)));
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("month", month);
            row.put("phase", phaseForRhythmMonth(month, totalMonths));
            row.put("current", month == currentMonth);
            row.put("dials", dials);
            rows.add(row);
        }
        return rows;
    }

    private List<Map<String, Object>> phaseControls() {
        // 三项阶段控制是固定目录,不再按 value 是否配置过滤(value 空时前端显示"未配置")。
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
        return List.of();
    }

    private List<Map<String, Object>> phaseAttribution() {
        // 三阶段归因是固定目录(P1/P2/P3),保证归因矩阵非空。
        return List.of(
                phaseAttributionRow("P1", "新手上手期", "新用户与任务加成驱动激活", "newUserBonusMultiplier", "2", "增长主导"),
                phaseAttributionRow("P2", "裂变扩散期", "邀请奖励拉新驱动裂变", "inviteRewardMultiplier", "1.5", "增长主导"),
                phaseAttributionRow("P3", "沉淀转化期", "复投加成驱动沉淀", "reinvestMultiplier", "2", "增长+运营"));
    }

    private Map<String, Object> phaseAttributionRow(String phase, String name, String driver, String paramKey, String value, String owner) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("phase", phase);
        row.put("name", name);
        row.put("driver", driver);
        row.put("paramKey", paramKey);
        row.put("value", value);
        row.put("owner", owner);
        return row;
    }

    private Map<String, Object> coverage() {
        TreasuryCoverageSnapshot snapshot = coverageFacade.snapshot();
        Map<String, Object> coverage = new LinkedHashMap<>();
        boolean reliable = snapshot != null && snapshot.reliable()
                && snapshot.coverageRatio() != null && snapshot.redlinePct() != null
                && snapshot.coverageRatio().signum() > 0 && snapshot.redlinePct().signum() > 0;
        coverage.put("reliable", reliable);
        coverage.put("coverageRatio", snapshot == null ? null : snapshot.coverageRatio());
        coverage.put("redlinePct", snapshot == null ? null : snapshot.redlinePct());
        coverage.put("redlineBreached", !reliable
                || snapshot.coverageRatio().compareTo(snapshot.redlinePct()) < 0);
        return coverage;
    }

    private String monthDialConfigKey(int month, String key) {
        return MONTH_DIAL_PREFIX + month + "." + key;
    }

    private BigDecimal defaultPhaseMonthDialValue(int month, String key) {
        return switch (key) {
            case "newUserBonusMultiplier", "inviteRewardMultiplier" -> month <= 2 ? new BigDecimal("2")
                    : month <= 4 ? new BigDecimal("1.5") : BigDecimal.ONE;
            case "reinvestMultiplier" -> month >= 5 && month <= 6 ? new BigDecimal("2") : BigDecimal.ONE;
            case "withdrawPenaltyFeeRate" -> month <= 8 ? new BigDecimal("0.20")
                    : month <= 10 ? new BigDecimal("0.25") : new BigDecimal("0.30");
            case "withdrawCooldownDays" -> month <= 7 ? new BigDecimal("30")
                    : month == 8 ? new BigDecimal("35") : new BigDecimal("45");
            case "binaryDailyCap" -> month <= 6 ? new BigDecimal("5000") : new BigDecimal("2000");
            case "questBonusMultiplier" -> month <= 2 ? new BigDecimal("4") : BigDecimal.ONE;
            case "complianceHoldEnabled" -> month >= 8 ? BigDecimal.ONE : BigDecimal.ZERO;
            default -> throw new IllegalArgumentException("Unsupported H1 phase dial");
        };
    }

    private Object displayPhaseDialValue(String key, BigDecimal value) {
        return switch (key) {
            case "withdrawPenaltyFeeRate" -> percent(value);
            case "withdrawCooldownDays" -> value.intValue();
            case "complianceHoldEnabled" -> value.signum() > 0 ? "是" : "否";
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
    }

    private List<Map<String, Object>> voucherRows() {
        if (voucherMapper.isEmpty()) {
            return new ArrayList<>();
        }
        try {
            return voucherMapper.get().listVouchers().stream()
                    .map(this::voucherRowFromBusinessTable)
                    .toList();
        } catch (RuntimeException ex) {
            return List.of();
        }
    }

    private List<Map<String, Object>> voucherSkuOptions() {
        if (deviceCatalogRepository.isPresent()) {
            List<Map<String, Object>> skus = deviceCatalogRepository.get()
                    .pageSkus(new DeviceSkuQueryRequest(null, null, 1L, 500L))
                    .getRecords()
                    .stream()
                    .map(this::voucherSkuOption)
                    .toList();
            if (!skus.isEmpty()) {
                return skus;
            }
        }
        return new ArrayList<>();
    }

    private Map<String, Object> voucherSkuOption(DeviceSkuView sku) {
        return row(
                "id", sku.skuId(),
                "name", sku.name(),
                "status", "ACTIVE".equalsIgnoreCase(sku.status()) || "ON".equalsIgnoreCase(sku.status()) ? "on" : "off");
    }

    private Map<String, Object> voucherRowFromBusinessTable(Map<String, Object> row) {
        Map<String, Object> voucher = new LinkedHashMap<>();
        voucher.put("id", stringValue(row.get("id"), ""));
        voucher.put("name", stringValue(row.get("name"), ""));
        voucher.put("type", stringValue(row.get("type"), ""));
        voucher.put("amountUSD", voucherNumberValue(row.get("amountUSD")).stripTrailingZeros());
        voucher.put("percent", voucherNumberValue(row.get("percent")).stripTrailingZeros());
        voucher.put("minPurchaseUSD", voucherNumberValue(row.get("minPurchaseUSD")).stripTrailingZeros());
        voucher.put("maxDiscountUSD", voucherNumberValue(row.get("maxDiscountUSD")).stripTrailingZeros());
        voucher.put("applicableSkus", parseJsonStringList(row.get("applicableSkusJson")));
        voucher.put("audience", stringValue(row.get("audience"), ""));
        voucher.put("startAt", longValue(row.get("startAt")));
        voucher.put("endAt", longValue(row.get("endAt")));
        voucher.put("claimSurfaces", parseJsonStringList(row.get("claimSurfacesJson")));
        voucher.put("popupEnabled", truthy(row.get("popupEnabled")));
        voucher.put("stackWithTrial", truthy(row.get("stackWithTrial")));
        voucher.put("stackWithOthers", truthy(row.get("stackWithOthers")));
        voucher.put("splittable", truthy(row.get("splittable")));
        voucher.put("status", stringValue(row.get("status"), ""));
        return voucher;
    }

    private List<Map<String, Object>> readJsonRows(String key, List<Map<String, Object>> fallback) {
        Optional<String> raw = configFacade.activeValue(key).filter(StringUtils::hasText);
        if (raw.isEmpty()) {
            return fallbackRows(fallback);
        }
        try {
            List<Map<String, Object>> rows = objectMapper.readValue(raw.get(), new TypeReference<List<Map<String, Object>>>() {
            });
            return rows == null ? fallbackRows(fallback) : new ArrayList<>(rows);
        } catch (JsonProcessingException ex) {
            return fallbackRows(fallback);
        }
    }

    private Map<String, Object> readJsonMap(String key, Map<String, Object> fallback) {
        Optional<String> raw = configFacade.activeValue(key).filter(StringUtils::hasText);
        if (raw.isEmpty()) {
            return fallbackMap(fallback);
        }
        try {
            Map<String, Object> row = objectMapper.readValue(raw.get(), new TypeReference<Map<String, Object>>() {
            });
            return row == null ? fallbackMap(fallback) : new LinkedHashMap<>(row);
        } catch (JsonProcessingException ex) {
            return fallbackMap(fallback);
        }
    }

    private List<Map<String, Object>> fallbackRows(List<Map<String, Object>> fallback) {
        return new ArrayList<>();
    }

    private Map<String, Object> fallbackMap(Map<String, Object> fallback) {
        return new LinkedHashMap<>();
    }

    private boolean disclosureGateActive(String key) {
        return controlValue(key)
                .map(value -> switch (value.trim().toLowerCase(Locale.ROOT)) {
                    case "enabled", "enable", "on", "true", "1", "blocked", "required" -> true;
                    default -> false;
                })
                .orElse(false);
    }

    private Optional<String> controlValue(String key) {
        return emergencyRepository.settingValue(key);
    }

    private Map<String, Object> normalizeVoucher(GrowthVoucherRequest request, String enforcedId) {
        if (request == null) {
            throw new IllegalArgumentException("VOUCHER_BODY_REQUIRED");
        }
        String id = StringUtils.hasText(enforcedId)
                ? enforcedId.trim()
                : (StringUtils.hasText(request.id()) ? request.id().trim() : "vc-" + Instant.now().toEpochMilli());
        id = normalizeVoucherId(id);
        String name = normalizePlainText(request.name(), 80);
        String type = requireText(request.type(), "VOUCHER_TYPE_REQUIRED").toLowerCase(Locale.ROOT);
        if (!Set.of("fixed", "percent").contains(type)) {
            throw new IllegalArgumentException("VOUCHER_TYPE_INVALID");
        }
        BigDecimal amount = "fixed".equals(type)
                ? bounded(voucherNumber(request.amountUSD(), BigDecimal.ZERO), BigDecimal.ONE, new BigDecimal("1000000"))
                : BigDecimal.ZERO;
        BigDecimal percent = "percent".equals(type)
                ? bounded(voucherNumber(request.percent(), BigDecimal.ZERO), BigDecimal.ONE, new BigDecimal("100"))
                : BigDecimal.ZERO;
        BigDecimal minPurchase = bounded(voucherNumber(request.minPurchaseUSD(), BigDecimal.ZERO), BigDecimal.ZERO, new BigDecimal("10000000"));
        BigDecimal maxDiscount = bounded(voucherNumber(request.maxDiscountUSD(), BigDecimal.ZERO), BigDecimal.ZERO, new BigDecimal("10000000"));
        String audience = requireText(request.audience(), "VOUCHER_AUDIENCE_REQUIRED").toLowerCase(Locale.ROOT);
        if (!Set.of("new", "all").contains(audience)) {
            throw new IllegalArgumentException("VOUCHER_AUDIENCE_INVALID");
        }
        String status = normalizeVoucherStatus(request.status());
        long startAt = voucherTimestamp(request.startAt());
        long endAt = voucherTimestamp(request.endAt());
        if (startAt > 0 && endAt > 0 && endAt < startAt) {
            throw new IllegalArgumentException("VOUCHER_TIME_RANGE_INVALID");
        }
        List<String> skus = normalizeVoucherSkus(request.applicableSkus());
        List<String> claimSurfaces = normalizeClaimSurfaces(request.claimSurfaces());
        Map<String, Object> voucher = new LinkedHashMap<>();
        voucher.put("id", id);
        voucher.put("name", name);
        voucher.put("type", type);
        voucher.put("amountUSD", amount.stripTrailingZeros());
        voucher.put("percent", percent.stripTrailingZeros());
        voucher.put("minPurchaseUSD", minPurchase.stripTrailingZeros());
        voucher.put("maxDiscountUSD", maxDiscount.stripTrailingZeros());
        voucher.put("applicableSkus", skus);
        voucher.put("audience", audience);
        voucher.put("startAt", startAt);
        voucher.put("endAt", endAt);
        voucher.put("claimSurfaces", claimSurfaces);
        voucher.put("popupEnabled", Boolean.TRUE.equals(request.popupEnabled()));
        voucher.put("stackWithTrial", Boolean.TRUE.equals(request.stackWithTrial()));
        voucher.put("stackWithOthers", Boolean.TRUE.equals(request.stackWithOthers()));
        voucher.put("splittable", Boolean.TRUE.equals(request.splittable()));
        voucher.put("status", status);
        return voucher;
    }

    private BigDecimal voucherNumber(BigDecimal value, BigDecimal fallback) {
        return value == null ? fallback : value;
    }

    private long voucherTimestamp(Long value) {
        if (value == null || value < 0) {
            return 0;
        }
        return value;
    }

    private List<String> normalizeVoucherSkus(List<String> raw) {
        List<String> skus = normalizeStringList(raw, 16, 40);
        Set<String> skuIds = voucherSkuIds();
        for (String sku : skus) {
            if (!skuIds.contains(sku)) {
                throw new IllegalArgumentException("VOUCHER_SKU_INVALID");
            }
        }
        return skus;
    }

    private Set<String> voucherSkuIds() {
        java.util.LinkedHashSet<String> ids = new java.util.LinkedHashSet<>();
        for (Map<String, Object> sku : voucherSkuOptions()) {
            Object id = sku.get("id");
            if (id != null && StringUtils.hasText(id.toString())) {
                ids.add(id.toString());
            }
        }
        return ids;
    }

    private List<String> normalizeClaimSurfaces(List<String> raw) {
        List<String> surfaces = normalizeStringList(raw, 8, 20);
        if (surfaces.isEmpty()) {
            return List.of("home", "store");
        }
        Set<String> allowed = Set.of("home", "store", "me", "earn");
        for (String surface : surfaces) {
            if (!allowed.contains(surface)) {
                throw new IllegalArgumentException("VOUCHER_SURFACE_INVALID");
            }
        }
        return surfaces;
    }

    private List<String> normalizeStringList(List<String> raw, int maxItems, int maxLength) {
        if (raw == null) {
            return List.of();
        }
        java.util.LinkedHashSet<String> values = new java.util.LinkedHashSet<>();
        for (String item : raw) {
            if (!StringUtils.hasText(item)) {
                continue;
            }
            String value = item.trim();
            if (value.length() > maxLength) {
                throw new IllegalArgumentException("VOUCHER_LIST_ITEM_TOO_LONG");
            }
            values.add(value);
        }
        if (values.size() > maxItems) {
            throw new IllegalArgumentException("VOUCHER_LIST_TOO_LONG");
        }
        return List.copyOf(values);
    }

    private String normalizeVoucherId(String raw) {
        String id = requireText(raw, "VOUCHER_ID_REQUIRED");
        if (!id.matches("[A-Za-z0-9_-]+")) {
            throw new IllegalArgumentException("VOUCHER_ID_INVALID");
        }
        return id;
    }

    private String normalizeVoucherStatus(String raw) {
        String status = requireText(raw, "VOUCHER_STATUS_REQUIRED").toLowerCase(Locale.ROOT);
        return switch (status) {
            case "active", "on", "enabled", "投放", "投放中" -> "active";
            case "paused", "pause", "off", "disabled", "暂停", "已暂停" -> "paused";
            default -> throw new IllegalArgumentException("VOUCHER_STATUS_INVALID");
        };
    }

    private int voucherIndex(List<Map<String, Object>> rows, String id) {
        for (int i = 0; i < rows.size(); i++) {
            if (id.equals(rows.get(i).get("id"))) {
                return i;
            }
        }
        throw new IllegalArgumentException("VOUCHER_NOT_FOUND");
    }

    private ApiResult<Map<String, Object>> requireCommand(String idempotencyKey, String reason) {
        if (!StringUtils.hasText(idempotencyKey)) {
            return ApiResult.fail(OpsErrorCode.IDEMPOTENCY_KEY_REQUIRED.httpStatus(), OpsErrorCode.IDEMPOTENCY_KEY_REQUIRED.name());
        }
        if (!StringUtils.hasText(reason) || reason.trim().length() < 8 || reason.trim().length() > 200) {
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
        return coverage == null || !coverage.reliable()
                || coverage.coverageRatio() == null || coverage.redlinePct() == null
                || coverage.coverageRatio().signum() <= 0 || coverage.redlinePct().signum() <= 0
                || coverage.coverageRatio().compareTo(coverage.redlinePct()) < 0;
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
            case "newUserBonusMultiplier" -> "growth.phase.new_user_bonus_multiplier";
            case "inviteRewardMultiplier" -> "growth.phase.invite_reward_multiplier";
            case "reinvestMultiplier" -> "growth.phase.reinvest_multiplier";
            case "withdrawPenaltyFeeRate" -> "growth.phase.withdraw_penalty_fee_rate";
            case "withdrawCooldownDays" -> "growth.phase.withdraw_cooldown_days";
            case "binaryDailyCap" -> "growth.phase.binary_daily_cap";
            case "questBonusMultiplier" -> "growth.phase.quest_bonus_multiplier";
            case "complianceHoldEnabled" -> "growth.phase.compliance_hold_enabled";
            default -> throw new IllegalArgumentException("Unsupported H1 phase dial");
        };
    }

    private BigDecimal defaultPhaseDialValue(String key) {
        return switch (key) {
            case "newUserBonusMultiplier", "inviteRewardMultiplier", "reinvestMultiplier", "questBonusMultiplier" -> BigDecimal.ONE;
            case "withdrawPenaltyFeeRate" -> new BigDecimal("0.20");
            case "withdrawCooldownDays" -> new BigDecimal("30");
            case "binaryDailyCap" -> new BigDecimal("5000");
            case "complianceHoldEnabled" -> BigDecimal.ZERO;
            default -> throw new IllegalArgumentException("Unsupported H1 phase dial");
        };
    }

    private BigDecimal monthDialValue(int month, String key) {
        BigDecimal fallback = defaultPhaseMonthDialValue(month, key);
        return configFacade.activeValue(monthDialConfigKey(month, key))
                .map(value -> parseDecimal(value, fallback))
                .orElse(fallback);
    }

    private boolean monthTransitionAmplifiesPayout(int currentMonth, int nextMonth) {
        if (nextMonth < 1 || currentMonth == nextMonth) {
            return false;
        }
        for (String key : PHASE_DIAL_KEYS) {
            BigDecimal oldValue = configFacade.activeValue(phaseDialConfigKey(key))
                    .filter(StringUtils::hasText)
                    .map(ignored -> configDecimal(phaseDialConfigKey(key), defaultPhaseMonthDialValue(Math.max(1, currentMonth), key)))
                    .orElseGet(() -> monthDialValue(Math.max(1, currentMonth), key));
            if (amplifiesPhasePayout(key, oldValue, monthDialValue(nextMonth, key))) {
                return true;
            }
        }
        return false;
    }

    private void syncActivePhaseDialsFromMonth(int month) {
        for (String key : PHASE_DIAL_KEYS) {
            writeActivePhaseDial(key, monthDialValue(month, key));
        }
    }

    private BigDecimal normalizePhaseDialValue(String key, String raw) {
        if ("complianceHoldEnabled".equals(key)) {
            String value = requireText(raw, "Compliance dial value is required").trim().toLowerCase(Locale.ROOT);
            if (Set.of("1", "true", "on", "是").contains(value)) return BigDecimal.ONE;
            if (Set.of("0", "false", "off", "否").contains(value)) return BigDecimal.ZERO;
            throw new IllegalArgumentException("COMPLIANCE_DIAL_INVALID");
        }
        BigDecimal value = parseDecimal(raw);
        return switch (key) {
            case "newUserBonusMultiplier", "inviteRewardMultiplier", "reinvestMultiplier", "questBonusMultiplier" ->
                    bounded(value, BigDecimal.ONE, new BigDecimal("4"));
            case "withdrawPenaltyFeeRate" -> bounded(ratio(value), BigDecimal.ZERO, BigDecimal.ONE);
            case "withdrawCooldownDays" -> wholeDays(value, 7, 90);
            case "binaryDailyCap" -> bounded(value, BigDecimal.ZERO, new BigDecimal("50000"));
            default -> throw new IllegalArgumentException("Unsupported H1 phase dial");
        };
    }

    private String booleanDialLabel(String value) {
        return Set.of("1", "true", "on", "是").contains(value.trim().toLowerCase(Locale.ROOT)) ? "是" : "否";
    }

    private boolean amplifiesPhasePayout(String key, BigDecimal oldValue, BigDecimal newValue) {
        return switch (key) {
            case "newUserBonusMultiplier", "inviteRewardMultiplier", "reinvestMultiplier", "binaryDailyCap", "questBonusMultiplier" ->
                    newValue.compareTo(oldValue) > 0;
            case "withdrawCooldownDays", "withdrawPenaltyFeeRate", "complianceHoldEnabled" ->
                    newValue.compareTo(oldValue) < 0;
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

    private String checkInRewardRuleKey(String key) {
        return switch (key) {
            case "baseRewardNex" -> "baseline";
            case "streakBonusNex" -> "bonus7";
            case "luckyMultiplierMax" -> "luckyMultiplierMax";
            default -> throw new IllegalArgumentException("Unsupported H5 check-in key");
        };
    }

    private BigDecimal currentCheckInValue(String key) {
        return checkInRuleNumber(checkInRewardRuleKey(key));
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
        if (key.startsWith("weekly.mult.")) {
            String phase = key.substring("weekly.mult.".length()).replace(" 当前", "").trim();
            if (!phase.matches("P[1-6]")) {
                throw new IllegalArgumentException("QUEST_WEEKLY_MULT_INVALID");
            }
            return "weekly.mult." + phase;
        }
        if (Set.of("dayOne.windowMs", "dayOne.triReward", "weekly.champBonus", "wheel.pool").contains(key)) {
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
                .orElse("");
    }

    private String questConfigStorageKey(String key) {
        if (key.startsWith("weekly.mult.")) {
            return QUEST_PREFIX + "weekly.mult." + key.substring("weekly.mult.".length());
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
        boolean exists = questEventRows().stream().anyMatch(row -> id.equals(row.get("id")));
        if (!exists) {
            throw new IllegalArgumentException("EVENT_NOT_FOUND");
        }
        return id;
    }

    private String normalizeNewEventId(String raw) {
        String id = requireText(raw, "EVENT_ID_REQUIRED").trim();
        if (!id.matches("[a-zA-Z0-9][a-zA-Z0-9_-]{1,62}")) {
            throw new IllegalArgumentException("EVENT_ID_INVALID");
        }
        return id;
    }

    private String normalizeEventState(String raw) {
        String state = StringUtils.hasText(raw) ? raw.trim().toLowerCase(Locale.ROOT) : "upcoming";
        if (!EVENT_STATES.contains(state)) {
            throw new IllegalArgumentException("EVENT_STATUS_INVALID");
        }
        return state;
    }

    private int eventStatusCode(String state) {
        return switch (state) {
            case "upcoming" -> 0;
            case "ended" -> 2;
            default -> 1;
        };
    }

    private String normalizeEventCondition(String raw) {
        return StringUtils.hasText(raw) ? normalizePlainText(raw, 512) : "";
    }

    private boolean updateBusinessEventReward(String eventId, String reward) {
        return questEventMapper.isPresent()
                && questEventMapper.get().updateReward(eventId, reward, rewardFlow(reward), LocalDateTime.now()) > 0;
    }

    private boolean updateBusinessEventStatus(String eventId, String status) {
        return questEventMapper.isPresent()
                && questEventMapper.get().updateStatus(eventId, eventStatusCode(status), LocalDateTime.now()) > 0;
    }

    private boolean updateBusinessEventFeatured(String eventId, boolean featured) {
        return questEventMapper.isPresent()
                && questEventMapper.get().updateFeatured(eventId, featured ? "FEATURED" : null, LocalDateTime.now()) > 0;
    }

    private String stringValue(Object raw, String fallback) {
        if (raw == null) {
            return fallback;
        }
        String value = raw.toString();
        return StringUtils.hasText(value) ? value : fallback;
    }

    private long longValue(Object raw) {
        if (raw instanceof Number number) {
            return number.longValue();
        }
        if (raw == null) {
            return 0L;
        }
        try {
            return Long.parseLong(raw.toString());
        } catch (NumberFormatException ex) {
            return 0L;
        }
    }

    private int intValue(Object raw, int fallback) {
        if (raw instanceof Number number) {
            return number.intValue();
        }
        if (raw == null || !StringUtils.hasText(raw.toString())) {
            return fallback;
        }
        try {
            return new BigDecimal(raw.toString().replace(",", "")).intValue();
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private BigDecimal voucherNumberValue(Object raw) {
        if (raw instanceof BigDecimal value) {
            return value;
        }
        if (raw instanceof Number number) {
            return BigDecimal.valueOf(number.doubleValue());
        }
        if (raw == null || !StringUtils.hasText(raw.toString())) {
            return BigDecimal.ZERO;
        }
        try {
            return new BigDecimal(raw.toString());
        } catch (NumberFormatException ex) {
            return BigDecimal.ZERO;
        }
    }

    private String toJsonString(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("JSON_SERIALIZE_FAILED");
        }
    }

    @SuppressWarnings("unchecked")
    private List<String> castStringList(Object raw) {
        if (raw instanceof List<?> list) {
            return list.stream()
                    .filter(item -> item != null && StringUtils.hasText(item.toString()))
                    .map(Object::toString)
                    .toList();
        }
        return List.of();
    }

    private List<String> parseJsonStringList(Object raw) {
        if (raw == null || !StringUtils.hasText(raw.toString())) {
            return List.of();
        }
        try {
            List<String> values = objectMapper.readValue(raw.toString(), new TypeReference<List<String>>() {
            });
            return values == null ? List.of() : values;
        } catch (JsonProcessingException ex) {
            return List.of();
        }
    }

    private boolean truthy(Object raw) {
        if (raw instanceof Boolean value) {
            return value;
        }
        if (raw instanceof Number number) {
            return number.intValue() != 0;
        }
        return Boolean.TRUE.equals(parseBooleanValue(raw == null ? null : raw.toString()));
    }

    private String eventStatus(String eventId) {
        Map<String, Object> row = eventRow(eventId);
        return row.get("state").toString();
    }

    private String eventReward(String eventId) {
        Map<String, Object> row = eventRow(eventId);
        return row.get("reward").toString();
    }

    private boolean eventFeatured(String eventId) {
        Map<String, Object> row = eventRow(eventId);
        return truthy(row.get("featured"));
    }

    private Map<String, Object> eventRow(String eventId) {
        return questEventRows().stream()
                .filter(event -> eventId.equals(event.get("id")))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("EVENT_NOT_FOUND"));
    }

    private BigDecimal configDecimal(String key, BigDecimal fallback) {
        return configFacade.activeValue(key)
                .flatMap(value -> Optional.ofNullable(parseDecimal(value, fallback)))
                .orElse(BigDecimal.ZERO);
    }

    private List<String> sunsetExclusions() {
        Optional<String> raw = configFacade.activeValue(SUNSET_EXCLUSIONS_KEY).filter(StringUtils::hasText);
        if (raw.isEmpty()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (String item : raw.get().split("[,;，；\\r\\n]+")) {
            if (StringUtils.hasText(item)) {
                String trimmed = item.trim();
                if (!values.contains(trimmed)) {
                    values.add(trimmed);
                }
            }
        }
        return values;
    }

    private BigDecimal parseDecimal(String raw) {
        return parseDecimal(raw, null);
    }

    private BigDecimal parseDecimal(String raw, BigDecimal fallback) {
        if (!StringUtils.hasText(raw)) {
            if (fallback != null) {
                return BigDecimal.ZERO;
            }
            throw new IllegalArgumentException("Numeric value is required");
        }
        try {
            return new BigDecimal(raw.trim().replace("%", "").replace(",", ""));
        } catch (NumberFormatException ex) {
            if (fallback != null) {
                return BigDecimal.ZERO;
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
            case "true", "1", "yes", "on", "enabled", "active" -> true;
            case "false", "0", "no", "off", "disabled", "inactive" -> false;
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

    private String checkInRuleValueType(String key) {
        return "saver".equals(key) ? "STRING" : "NUMBER";
    }

    private boolean checkInRuleHot(String key) {
        return Set.of("p15", "p2", "luckyMultiplierMax").contains(key);
    }

    private int checkInRuleSortOrder(String key) {
        return switch (key) {
            case "baseline" -> 10;
            case "bonus7" -> 20;
            case "luckyMultiplierMax" -> 25;
            case "p15" -> 30;
            case "p2" -> 40;
            case "broken" -> 50;
            case "saver" -> 60;
            default -> 100;
        };
    }

    private BigDecimal currentCheckInRuleNumber(String key) {
        return checkInRuleNumber(key);
    }

    private BigDecimal checkInRuleNumber(String key) {
        if (questEventMapper.isEmpty()) {
            return BigDecimal.ZERO;
        }
        String value;
        try {
            value = questEventMapper.get().checkInRuleValue(key);
        } catch (RuntimeException ex) {
            return BigDecimal.ZERO;
        }
        if (!StringUtils.hasText(value)) {
            return BigDecimal.ZERO;
        }
        return parseDecimal(value, BigDecimal.ZERO);
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
                : currentCheckInRuleNumber("p15");
        BigDecimal lucky2 = "p2".equals(key)
                ? newValue
                : currentCheckInRuleNumber("p2");
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

    private BigDecimal trialChargeAmount() {
        return parseMoney(trialParamCurrentValue("trialPriceUSD", "1299"));
    }

    private Long userIdFromToken(String token) {
        if (!StringUtils.hasText(token)) {
            return 0L;
        }
        String digits = token.replaceAll("\\D+", "");
        if (!StringUtils.hasText(digits)) {
            return 0L;
        }
        try {
            return Long.parseLong(digits);
        } catch (NumberFormatException ex) {
            return 0L;
        }
    }

    private String normalizeEarnMilestoneKey(String key) {
        return requireText(key, "Earn milestone key is required");
    }

    private Optional<Map<String, Object>> indexedBusinessRow(List<Map<String, Object>> rows, int id) {
        return rows.stream()
                .filter(row -> row.get("id") != null)
                .filter(row -> String.valueOf(id).equals(String.valueOf(row.get("id"))))
                .findFirst();
    }

    private Optional<Map<String, Object>> earnMilestoneRow(List<Map<String, Object>> rows, String key) {
        return rows.stream()
                .filter(row -> key.equals(String.valueOf(row.get("key"))))
                .findFirst();
    }

    private BigDecimal earnMilestoneThreshold(Map<String, Object> row) {
        return rowDecimal(row, "threshold");
    }

    private BigDecimal earnMilestoneReward(Map<String, Object> row) {
        return rowDecimal(row, "nex");
    }

    private boolean earnMilestonesRemainOrdered(List<Map<String, Object>> rows, String targetKey, BigDecimal targetThreshold) {
        BigDecimal previous = null;
        for (Map<String, Object> row : rows) {
            String key = String.valueOf(row.get("key"));
            BigDecimal threshold = key.equals(targetKey) ? targetThreshold : earnMilestoneThreshold(row);
            if (previous != null && threshold.compareTo(previous) <= 0) {
                return false;
            }
            previous = threshold;
        }
        return true;
    }

    private BigDecimal rowDecimal(Map<String, Object> row, String field) {
        Object value = row.get(field);
        if (value instanceof BigDecimal decimal) {
            return decimal.stripTrailingZeros();
        }
        if (value instanceof Number number) {
            return new BigDecimal(number.toString()).stripTrailingZeros();
        }
        if (value != null && StringUtils.hasText(value.toString())) {
            return parseDecimal(value.toString()).stripTrailingZeros();
        }
        return BigDecimal.ZERO;
    }

    private String rewardTypeFromReward(String reward) {
        String normalized = reward == null ? "" : reward.toUpperCase(Locale.ROOT);
        if (normalized.contains("USDT") || normalized.contains("$")) {
            return "USDT";
        }
        if (normalized.contains("SPIN") || normalized.contains("转盘")) {
            return "SPIN";
        }
        if (normalized.contains("BADGE") || normalized.contains("徽章")) {
            return "BADGE";
        }
        return "NEX";
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
        auditLogService.recordRequired(AuditLogWriteRequest.builder()
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

    @Override
    public String domain() {
        return "H";
    }

    @Override
    public ApiResult<?> replay(AuditReplayCommand cmd, AuditReplayContext ctx) {
        Map<String, Object> p = cmd.params() == null ? Map.of() : cmd.params();
        String operator = ctx.operator();
        String reason = ctx.reason();
        String idem = ctx.idempotencyKey();
        switch (cmd.op()) {
            case "h1_phase_dial" -> {
                GrowthConfigUpdateRequest req = new GrowthConfigUpdateRequest(null, str(p, "value"), reason, operator);
                Integer month = intVal(p, "month");
                return updatePhaseMonthDial(idem, month == null ? 0 : month, str(p, "dialKey"), req);
            }
            case "h1_phase_control" -> {
                GrowthConfigUpdateRequest req = new GrowthConfigUpdateRequest(null, str(p, "value"), reason, operator);
                return updatePhaseControl(idem, str(p, "controlKey"), req);
            }
            case "h1_phase_override" -> {
                // updatePhaseOverride 内部用 parseBooleanValue(request.value()) 解析布尔,disabled 以字符串放入 value
                GrowthConfigUpdateRequest req = new GrowthConfigUpdateRequest(null, String.valueOf(boolVal(p, "disabled")), reason, operator);
                return updatePhaseOverride(idem, str(p, "overrideId"), req);
            }
            case "h2_trial_cancel" -> {
                GrowthConfigUpdateRequest req = new GrowthConfigUpdateRequest(null, null, reason, operator);
                return cancelTrialSession(idem, str(p, "sid"), req);
            }
            case "h2_trial_charge" -> {
                // 直接动 USDT OUT 台账,金额由 trialChargeAmount() 实时算,复用原方法保证一致;
                // 前置闸门 trialKillSwitchEnabled()(J1 联动)由原方法保留。H 域无 @Transactional,无自调用代理问题。
                GrowthConfigUpdateRequest req = new GrowthConfigUpdateRequest(null, null, reason, operator);
                return chargeTrialSession(idem, str(p, "sid"), req);
            }
            case "h5_checkin_rule" -> {
                GrowthConfigUpdateRequest req = new GrowthConfigUpdateRequest(null, str(p, "value"), reason, operator);
                return updateCheckInRule(idem, str(p, "ruleKey"), req);
            }
            case "h8_referral_settlement" -> {
                if (referralRewardService.isEmpty()) {
                    return ApiResult.fail(503, "H8_REFERRAL_REWARD_SERVICE_UNAVAILABLE");
                }
                Integer requestedLimit = intVal(p, "limit");
                int limit = requestedLimit == null ? 20 : requestedLimit;
                return ApiResult.ok(referralRewardService.get().runSettlements(
                        idem,
                        new ReferralSettlementRunRequest(limit, reason, operator)));
            }
            default -> {
                return ApiResult.fail(422, "UNKNOWN_REPLAY_OP:" + cmd.op());
            }
        }
    }

    /** 从 replay params 取字符串,null 安全。 */
    private static String str(Map<String, Object> params, String key) {
        Object v = params.get(key);
        return v == null ? null : String.valueOf(v).trim();
    }

    /** 从 replay params 取 Boolean,null 安全。 */
    private static Boolean boolVal(Map<String, Object> params, String key) {
        Object v = params.get(key);
        if (v == null) return null;
        if (v instanceof Boolean b) return b;
        if (v instanceof String s) return Boolean.parseBoolean(s.trim());
        return null;
    }

    /** 从 replay params 取 Integer,null 安全(缺失返回 null,由 DTO 默认逻辑兜底)。 */
    private static Integer intVal(Map<String, Object> params, String key) {
        Object v = params.get(key);
        if (v == null) return null;
        if (v instanceof Number n) return n.intValue();
        try {
            return Integer.parseInt(String.valueOf(v).trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

}
