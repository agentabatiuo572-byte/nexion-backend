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
        BigDecimal newUserBonusMultiplier,
        BigDecimal inviteRewardMultiplier,
        BigDecimal reinvestMultiplier,
        BigDecimal withdrawPenaltyFeeRate,
        int withdrawCooldownDays,
        BigDecimal binaryDailyCap,
        BigDecimal questBonusMultiplier,
        boolean complianceHoldEnabled,
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
        return create(configFacade, totalMonths, currentMonth, currentPhase, allowFallback);
    }

    public static GrowthRhythmSnapshot fromMonth(
            PlatformConfigFacade configFacade,
            OpsReadTimeSeedPolicy readTimeSeedPolicy,
            int month) {
        boolean allowFallback = false;
        int totalMonths = rhythmTotalMonths(configFacade, allowFallback);
        int selectedMonth = totalMonths < 1 || month < 1 ? 0 : clamp(month, 1, totalMonths);
        String selectedPhase = selectedMonth < 1 ? "" : phaseForRhythmMonth(selectedMonth, totalMonths);
        return create(configFacade, totalMonths, selectedMonth, selectedPhase, allowFallback);
    }

    private static GrowthRhythmSnapshot create(
            PlatformConfigFacade configFacade,
            int totalMonths,
            int currentMonth,
            String currentPhase,
            boolean allowFallback) {
        return new GrowthRhythmSnapshot(
                totalMonths,
                currentMonth,
                currentPhase,
                clamp(configInt(configFacade, RHYTHM_PHASE_PROGRESS_KEY, 58, allowFallback), 0, 100),
                monthDial(configFacade, currentMonth, "newUserBonusMultiplier", defaultMonthDial(currentMonth, "newUserBonusMultiplier"), allowFallback),
                monthDial(configFacade, currentMonth, "inviteRewardMultiplier", defaultMonthDial(currentMonth, "inviteRewardMultiplier"), allowFallback),
                monthDial(configFacade, currentMonth, "reinvestMultiplier", defaultMonthDial(currentMonth, "reinvestMultiplier"), allowFallback),
                percent(monthDial(configFacade, currentMonth, "withdrawPenaltyFeeRate", defaultMonthDial(currentMonth, "withdrawPenaltyFeeRate"), allowFallback)),
                monthDial(configFacade, currentMonth, "withdrawCooldownDays", defaultMonthDial(currentMonth, "withdrawCooldownDays"), allowFallback).setScale(0, RoundingMode.DOWN).intValue(),
                monthDial(configFacade, currentMonth, "binaryDailyCap", defaultMonthDial(currentMonth, "binaryDailyCap"), allowFallback),
                monthDial(configFacade, currentMonth, "questBonusMultiplier", defaultMonthDial(currentMonth, "questBonusMultiplier"), allowFallback),
                monthDial(configFacade, currentMonth, "complianceHoldEnabled", defaultMonthDial(currentMonth, "complianceHoldEnabled"), allowFallback).signum() > 0,
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
        map.put("newUserBonusMultiplier", newUserBonusMultiplier);
        map.put("inviteRewardMultiplier", inviteRewardMultiplier);
        map.put("reinvestMultiplier", reinvestMultiplier);
        map.put("withdrawPenaltyFeeRate", withdrawPenaltyFeeRate);
        map.put("withdrawCooldownDays", withdrawCooldownDays);
        map.put("binaryDailyCap", binaryDailyCap);
        map.put("questBonusMultiplier", questBonusMultiplier);
        map.put("complianceHoldEnabled", complianceHoldEnabled);
        return map;
    }

    private static BigDecimal monthDial(PlatformConfigFacade configFacade, int month, String key, BigDecimal fallback, boolean allowFallback) {
        return decimal(configFacade, "growth.phase.month." + month + "." + key, fallback, allowFallback);
    }

    private static BigDecimal defaultMonthDial(int month, String key) {
        return switch (key) {
            case "newUserBonusMultiplier", "inviteRewardMultiplier" -> month <= 2 ? new BigDecimal("2") : month <= 4 ? new BigDecimal("1.5") : BigDecimal.ONE;
            case "reinvestMultiplier" -> month >= 5 && month <= 6 ? new BigDecimal("2") : BigDecimal.ONE;
            case "withdrawPenaltyFeeRate" -> month <= 8 ? new BigDecimal("0.20") : month <= 10 ? new BigDecimal("0.25") : new BigDecimal("0.30");
            case "withdrawCooldownDays" -> month <= 7 ? new BigDecimal("30") : month == 8 ? new BigDecimal("35") : new BigDecimal("45");
            case "binaryDailyCap" -> month <= 6 ? new BigDecimal("5000") : new BigDecimal("2000");
            case "questBonusMultiplier" -> month <= 2 ? new BigDecimal("4") : BigDecimal.ONE;
            case "complianceHoldEnabled" -> month >= 8 ? BigDecimal.ONE : BigDecimal.ZERO;
            default -> BigDecimal.ZERO;
        };
    }

    private static int rhythmTotalMonths(PlatformConfigFacade configFacade, boolean allowFallback) {
        int total = configInt(configFacade, RHYTHM_TOTAL_MONTHS_KEY, 12, allowFallback);
        return RHYTHM_TOTAL_OPTIONS.contains(total) ? total : 0;
    }

    private static int rhythmCurrentMonth(PlatformConfigFacade configFacade, int totalMonths, boolean allowFallback) {
        int month = configFacade.activeValue(RHYTHM_CURRENT_MONTH_KEY)
                .filter(StringUtils::hasText)
                .map(value -> parseInt(value, 7, allowFallback))
                .orElseGet(() -> allowFallback ? 7 : 0);
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
