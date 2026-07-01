package ffdd.opsconsole.growth.facade;

import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import ffdd.opsconsole.shared.seed.OpsReadTimeSeedPolicy;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.util.StringUtils;

public record GrowthRhythmSnapshot(
        int totalMonths,
        int currentMonth,
        String currentPhase,
        int phaseProgressPct,
        BigDecimal inviteRewardMultiplier,
        BigDecimal questRewardMultiplier,
        BigDecimal trialOffsetCapUsdt,
        BigDecimal deviceReleasePacingPct,
        BigDecimal commissionTighteningPct,
        BigDecimal campaignRewardNex,
        BigDecimal withdrawNexMinBalance,
        int withdrawNexHoldDays,
        List<String> sourceKeys) {
    private static final String CURRENT_MONTH_KEY = "growth.phase.current_month";
    private static final String CURRENT_PHASE_KEY = "growth.phase.current";
    private static final String RHYTHM_TOTAL_MONTHS_KEY = "H1.rhythm.totalMonths";
    private static final String RHYTHM_CURRENT_MONTH_KEY = "H1.rhythm.currentMonth";
    private static final String RHYTHM_PHASE_PROGRESS_KEY = "H1.rhythm.phaseProgressPct";
    private static final List<Integer> RHYTHM_TOTAL_OPTIONS = List.of(9, 12, 15, 18, 24);

    public static GrowthRhythmSnapshot from(PlatformConfigFacade configFacade, OpsReadTimeSeedPolicy readTimeSeedPolicy) {
        boolean allowFallback = false;
        int totalMonths = rhythmTotalMonths(configFacade, allowFallback);
        int currentMonth = rhythmCurrentMonth(configFacade, totalMonths, allowFallback);
        String computedPhase = currentMonth > 0 && totalMonths > 0 ? phaseForRhythmMonth(currentMonth, totalMonths) : "";
        String currentPhase = activeValue(configFacade, CURRENT_PHASE_KEY, computedPhase, allowFallback);
        return new GrowthRhythmSnapshot(
                totalMonths,
                currentMonth,
                currentPhase,
                clamp(configInt(configFacade, RHYTHM_PHASE_PROGRESS_KEY, 58, allowFallback), 0, 100),
                decimal(configFacade, "growth.phase.invite_reward_multiplier", BigDecimal.ONE, allowFallback),
                decimal(configFacade, "growth.phase.quest_reward_multiplier", BigDecimal.ONE, allowFallback),
                decimal(configFacade, "growth.phase.trial_offset_cap_usdt", new BigDecimal("50"), allowFallback),
                percent(decimal(configFacade, "growth.phase.device_release_pacing_pct", new BigDecimal("0.60"), allowFallback)),
                percent(decimal(configFacade, "growth.phase.commission_tightening_pct", new BigDecimal("0.10"), allowFallback)),
                decimal(configFacade, "growth.phase.campaign_reward_nex", new BigDecimal("10"), allowFallback),
                decimal(configFacade, "growth.withdraw_nex_gate.min_balance_nex", new BigDecimal("100"), allowFallback),
                decimal(configFacade, "growth.withdraw_nex_gate.hold_days", new BigDecimal("7"), allowFallback).setScale(0, RoundingMode.DOWN).intValue(),
                List.of(
                        RHYTHM_TOTAL_MONTHS_KEY,
                        RHYTHM_CURRENT_MONTH_KEY,
                        RHYTHM_PHASE_PROGRESS_KEY,
                        CURRENT_MONTH_KEY,
                        CURRENT_PHASE_KEY,
                        "growth.phase.*",
                        "growth.withdraw_nex_gate.*"));
    }

    public Map<String, Object> summary() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("sourceDomain", "H1");
        map.put("totalMonths", totalMonths);
        map.put("currentMonth", currentMonth);
        map.put("currentPhase", currentPhase);
        map.put("phaseProgressPct", phaseProgressPct);
        map.put("sourceKeys", sourceKeys);
        return map;
    }

    public Map<String, Object> dials() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("inviteRewardMultiplier", inviteRewardMultiplier);
        map.put("questRewardMultiplier", questRewardMultiplier);
        map.put("trialOffsetCapUsdt", trialOffsetCapUsdt);
        map.put("deviceReleasePacingPct", deviceReleasePacingPct);
        map.put("commissionTighteningPct", commissionTighteningPct);
        map.put("campaignRewardNex", campaignRewardNex);
        map.put("withdrawNexMinBalance", withdrawNexMinBalance);
        map.put("withdrawNexHoldDays", withdrawNexHoldDays);
        return map;
    }

    private static int rhythmTotalMonths(PlatformConfigFacade configFacade, boolean allowFallback) {
        int total = configInt(configFacade, RHYTHM_TOTAL_MONTHS_KEY, 12, allowFallback);
        return RHYTHM_TOTAL_OPTIONS.contains(total) ? total : 0;
    }

    private static int rhythmCurrentMonth(PlatformConfigFacade configFacade, int totalMonths, boolean allowFallback) {
        if (!allowFallback && totalMonths <= 0
                && configFacade.activeValue(RHYTHM_CURRENT_MONTH_KEY).filter(StringUtils::hasText).isEmpty()
                && configFacade.activeValue(CURRENT_MONTH_KEY).filter(StringUtils::hasText).isEmpty()) {
            return 0;
        }
        int month = configFacade.activeValue(RHYTHM_CURRENT_MONTH_KEY)
                .filter(StringUtils::hasText)
                .map(value -> parseInt(value, configInt(configFacade, CURRENT_MONTH_KEY, 7, allowFallback), allowFallback))
                .orElseGet(() -> configInt(configFacade, CURRENT_MONTH_KEY, 7, allowFallback));
        return totalMonths <= 0 ? Math.max(0, month) : clamp(month, 1, totalMonths);
    }

    public static String phaseForRhythmMonth(int month, int totalMonths) {
        int clampedMonth = clamp(month, 1, Math.max(totalMonths, 1));
        int clampedTotal = Math.max(totalMonths, 1);
        int accumulated = 0;
        for (int i = 0; i < List.of(2, 2, 3, 1, 2, 2).size(); i++) {
            accumulated += List.of(2, 2, 3, 1, 2, 2).get(i);
            int boundary = (int) Math.ceil(accumulated * (clampedTotal / 12.0d));
            if (clampedMonth <= Math.max(boundary, i + 1)) {
                return "P" + (i + 1);
            }
        }
        return "P6";
    }

    private static String activeValue(PlatformConfigFacade configFacade, String key, String fallback, boolean allowFallback) {
        return configFacade.activeValue(key)
                .map(String::trim)
                .filter(StringUtils::hasText)
                .orElseGet(() -> allowFallback ? fallback : "");
    }

    private static int configInt(PlatformConfigFacade configFacade, String key, int fallback, boolean allowFallback) {
        return configFacade.activeValue(key)
                .map(String::trim)
                .filter(StringUtils::hasText)
                .map(value -> parseInt(value, fallback, allowFallback))
                .orElseGet(() -> allowFallback ? fallback : 0);
    }

    private static BigDecimal decimal(PlatformConfigFacade configFacade, String key, BigDecimal fallback, boolean allowFallback) {
        return configFacade.activeValue(key)
                .map(String::trim)
                .filter(StringUtils::hasText)
                .map(value -> parseDecimal(value, fallback, allowFallback))
                .orElseGet(() -> allowFallback ? fallback : BigDecimal.ZERO);
    }

    private static int parseInt(String value, int fallback, boolean allowFallback) {
        try {
            return new BigDecimal(value.trim().replace("M", "")).setScale(0, RoundingMode.DOWN).intValue();
        } catch (RuntimeException ex) {
            return allowFallback ? fallback : 0;
        }
    }

    private static BigDecimal parseDecimal(String value, BigDecimal fallback, boolean allowFallback) {
        try {
            return new BigDecimal(value.trim().replace("%", ""));
        } catch (RuntimeException ex) {
            return allowFallback ? fallback : BigDecimal.ZERO;
        }
    }

    private static BigDecimal percent(BigDecimal ratio) {
        return ratio.multiply(BigDecimal.valueOf(100)).setScale(2, RoundingMode.HALF_UP).stripTrailingZeros();
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
