package ffdd.opsconsole.growth.facade;

import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import ffdd.opsconsole.shared.seed.OpsReadTimeSeedPolicy;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
        List<String> sourceKeys,
        boolean reliable,
        List<String> reliabilityErrors) {
    private static final String CURRENT_MONTH_KEY = "growth.phase.current_month";
    private static final String CURRENT_PHASE_KEY = "growth.phase.current";
    private static final String RHYTHM_TOTAL_MONTHS_KEY = "H1.rhythm.totalMonths";
    private static final String RHYTHM_CURRENT_MONTH_KEY = "H1.rhythm.currentMonth";
    private static final String RHYTHM_PHASE_PROGRESS_KEY = "H1.rhythm.phaseProgressPct";
    private static final List<Integer> RHYTHM_TOTAL_OPTIONS = List.of(9, 12, 15, 18, 24);

    public GrowthRhythmSnapshot(
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
        this(totalMonths, currentMonth, currentPhase, phaseProgressPct, newUserBonusMultiplier,
                inviteRewardMultiplier, reinvestMultiplier, withdrawPenaltyFeeRate, withdrawCooldownDays,
                binaryDailyCap, questBonusMultiplier, complianceHoldEnabled, sourceKeys, true, List.of());
    }

    public static GrowthRhythmSnapshot from(PlatformConfigFacade configFacade, OpsReadTimeSeedPolicy readTimeSeedPolicy) {
        boolean allowFallback = false;
        Map<String, String> configValues = new LinkedHashMap<>(configFacade.activeValuesByKeys(
                List.of(RHYTHM_TOTAL_MONTHS_KEY, RHYTHM_CURRENT_MONTH_KEY, CURRENT_PHASE_KEY)));
        int totalMonths = rhythmTotalMonths(configValues, allowFallback);
        int currentMonth = rhythmCurrentMonth(configValues, totalMonths, allowFallback);
        String computedPhase = currentMonth > 0 && totalMonths > 0 ? phaseForRhythmMonth(currentMonth, totalMonths) : "";
        String currentPhase = activeValue(configValues, CURRENT_PHASE_KEY, computedPhase, allowFallback);
        return create(configFacade, configValues, totalMonths, currentMonth, currentPhase, allowFallback);
    }

    public static GrowthRhythmSnapshot fromMonth(
            PlatformConfigFacade configFacade,
            OpsReadTimeSeedPolicy readTimeSeedPolicy,
            int month) {
        boolean allowFallback = false;
        Map<String, String> configValues = new LinkedHashMap<>(configFacade.activeValuesByKeys(
                List.of(RHYTHM_TOTAL_MONTHS_KEY)));
        int totalMonths = rhythmTotalMonths(configValues, allowFallback);
        int selectedMonth = totalMonths < 1 || month < 1 ? 0 : clamp(month, 1, totalMonths);
        String selectedPhase = selectedMonth < 1 ? "" : phaseForRhythmMonth(selectedMonth, totalMonths);
        return create(configFacade, configValues, totalMonths, selectedMonth, selectedPhase, allowFallback);
    }

    private static GrowthRhythmSnapshot create(
            PlatformConfigFacade configFacade,
            Map<String, String> configValues,
            int totalMonths,
            int currentMonth,
            String currentPhase,
            boolean allowFallback) {
        String prefix = "growth.phase.month." + currentMonth + ".";
        configValues.putAll(configFacade.activeValuesByKeys(List.of(
                RHYTHM_PHASE_PROGRESS_KEY,
                prefix + "newUserBonusMultiplier", prefix + "inviteRewardMultiplier", prefix + "reinvestMultiplier",
                prefix + "withdrawPenaltyFeeRate", prefix + "withdrawCooldownDays", prefix + "binaryDailyCap",
                prefix + "questBonusMultiplier", prefix + "complianceHoldEnabled")));
        List<String> reliabilityErrors = validateRequiredFacts(configValues, totalMonths, currentMonth, currentPhase);
        return new GrowthRhythmSnapshot(
                totalMonths,
                currentMonth,
                currentPhase,
                clamp(configInt(configValues, RHYTHM_PHASE_PROGRESS_KEY, 58, allowFallback), 0, 100),
                monthDial(configValues, currentMonth, "newUserBonusMultiplier", defaultMonthDial(currentMonth, "newUserBonusMultiplier"), allowFallback),
                monthDial(configValues, currentMonth, "inviteRewardMultiplier", defaultMonthDial(currentMonth, "inviteRewardMultiplier"), allowFallback),
                monthDial(configValues, currentMonth, "reinvestMultiplier", defaultMonthDial(currentMonth, "reinvestMultiplier"), allowFallback),
                percent(monthDial(configValues, currentMonth, "withdrawPenaltyFeeRate", defaultMonthDial(currentMonth, "withdrawPenaltyFeeRate"), allowFallback)),
                monthDial(configValues, currentMonth, "withdrawCooldownDays", defaultMonthDial(currentMonth, "withdrawCooldownDays"), allowFallback).setScale(0, RoundingMode.DOWN).intValue(),
                monthDial(configValues, currentMonth, "binaryDailyCap", defaultMonthDial(currentMonth, "binaryDailyCap"), allowFallback),
                monthDial(configValues, currentMonth, "questBonusMultiplier", defaultMonthDial(currentMonth, "questBonusMultiplier"), allowFallback),
                monthDial(configValues, currentMonth, "complianceHoldEnabled", defaultMonthDial(currentMonth, "complianceHoldEnabled"), allowFallback).signum() > 0,
                List.of(
                        RHYTHM_TOTAL_MONTHS_KEY,
                        RHYTHM_CURRENT_MONTH_KEY,
                        RHYTHM_PHASE_PROGRESS_KEY,
                        CURRENT_MONTH_KEY,
                        CURRENT_PHASE_KEY,
                        "growth.phase.*",
                        "growth.withdraw_nex_gate.*"),
                reliabilityErrors.isEmpty(),
                reliabilityErrors);
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

    private static BigDecimal monthDial(Map<String, String> configValues, int month, String key, BigDecimal fallback, boolean allowFallback) {
        return decimal(configValues, "growth.phase.month." + month + "." + key, fallback, allowFallback);
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

    private static int rhythmTotalMonths(Map<String, String> configValues, boolean allowFallback) {
        int total = configInt(configValues, RHYTHM_TOTAL_MONTHS_KEY, 12, allowFallback);
        return RHYTHM_TOTAL_OPTIONS.contains(total) ? total : 0;
    }

    private static int rhythmCurrentMonth(Map<String, String> configValues, int totalMonths, boolean allowFallback) {
        int month = Optional.ofNullable(configValues.get(RHYTHM_CURRENT_MONTH_KEY))
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

    private static String activeValue(Map<String, String> configValues, String key, String fallback, boolean allowFallback) {
        return Optional.ofNullable(configValues.get(key))
                .map(String::trim)
                .filter(StringUtils::hasText)
                .orElseGet(() -> allowFallback ? fallback : "");
    }

    private static int configInt(Map<String, String> configValues, String key, int fallback, boolean allowFallback) {
        return Optional.ofNullable(configValues.get(key))
                .map(String::trim)
                .filter(StringUtils::hasText)
                .map(value -> parseInt(value, fallback, allowFallback))
                .orElseGet(() -> allowFallback ? fallback : 0);
    }

    private static BigDecimal decimal(Map<String, String> configValues, String key, BigDecimal fallback, boolean allowFallback) {
        return Optional.ofNullable(configValues.get(key))
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

    private static List<String> validateRequiredFacts(
            Map<String, String> configValues, int totalMonths, int currentMonth, String currentPhase) {
        List<String> errors = new java.util.ArrayList<>();
        if (!RHYTHM_TOTAL_OPTIONS.contains(totalMonths)) errors.add(RHYTHM_TOTAL_MONTHS_KEY);
        if (currentMonth < 1 || currentMonth > totalMonths) errors.add(RHYTHM_CURRENT_MONTH_KEY);
        if (currentPhase == null || !currentPhase.matches("P[1-6]")) errors.add(CURRENT_PHASE_KEY);
        validateDecimal(configValues, RHYTHM_PHASE_PROGRESS_KEY, BigDecimal.ZERO, new BigDecimal("100"), errors);
        String prefix = "growth.phase.month." + currentMonth + ".";
        validateDecimal(configValues, prefix + "newUserBonusMultiplier", new BigDecimal("0.01"), new BigDecimal("10"), errors);
        validateDecimal(configValues, prefix + "inviteRewardMultiplier", new BigDecimal("0.01"), new BigDecimal("10"), errors);
        validateDecimal(configValues, prefix + "reinvestMultiplier", new BigDecimal("0.01"), new BigDecimal("10"), errors);
        validateDecimal(configValues, prefix + "withdrawPenaltyFeeRate", BigDecimal.ZERO, BigDecimal.ONE, errors);
        validateInteger(configValues, prefix + "withdrawCooldownDays", 0, 365, errors);
        validateDecimal(configValues, prefix + "binaryDailyCap", BigDecimal.ZERO, new BigDecimal("1000000000"), errors);
        validateDecimal(configValues, prefix + "questBonusMultiplier", new BigDecimal("0.01"), new BigDecimal("10"), errors);
        validateInteger(configValues, prefix + "complianceHoldEnabled", 0, 1, errors);
        return List.copyOf(errors);
    }

    private static void validateDecimal(
            Map<String, String> configValues,
            String key,
            BigDecimal min,
            BigDecimal max,
            List<String> errors) {
        try {
            BigDecimal value = Optional.ofNullable(configValues.get(key))
                    .filter(StringUtils::hasText)
                    .map(String::trim)
                    .map(raw -> new BigDecimal(raw.replace("%", "")))
                    .orElseThrow();
            if (value.compareTo(min) < 0 || value.compareTo(max) > 0) errors.add(key);
        } catch (RuntimeException ex) {
            errors.add(key);
        }
    }

    private static void validateInteger(
            Map<String, String> configValues,
            String key,
            int min,
            int max,
            List<String> errors) {
        try {
            int value = Optional.ofNullable(configValues.get(key))
                    .filter(StringUtils::hasText)
                    .map(String::trim)
                    .map(raw -> new BigDecimal(raw).intValueExact())
                    .orElseThrow();
            if (value < min || value > max) errors.add(key);
        } catch (RuntimeException ex) {
            errors.add(key);
        }
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
