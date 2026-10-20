package ffdd.opsconsole.onboarding.application;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Pure rolling-window rules. A missing fact is never a zero count. */
public record PhoneReplacementPolicy(boolean allowReplacement, long minReplacementIntervalDays,
        int replacementWindowDays, int maxReplacementsInWindow, long policyVersion) {
    public static final String PREFIX = "E.compute.phoneBinding.";
    public static final String VERSION_KEY = PREFIX + "policyVersion";
    public static final long DAY = 86_400_000L;
    public static final long MAX_EPOCH_MILLIS = 253_402_300_799_999L; // MySQL DATETIME year 9999.
    public static final List<String> KEYS = List.of(PREFIX + "allowReplacement",
            PREFIX + "maxReplacementsInWindow", PREFIX + "minReplacementIntervalDays",
            VERSION_KEY, PREFIX + "replacementWindowDays");

    public record History(Long completeFrom, boolean allHistoryKnown, boolean changedAtVerified) { }
    public record Decision(boolean canReplace, Long usedCount, Long remainingCount,
            boolean historyComplete, Long historyCompleteFrom, Long nextAllowedAt, List<String> blockedReasons) { }

    public static PhoneReplacementPolicy parse(Map<String, String> values, long now) {
        try {
            String flag = values.get(PREFIX + "allowReplacement");
            if (!List.of("on", "off").contains(flag)) return null;
            PhoneReplacementPolicy policy = new PhoneReplacementPolicy("on".equals(flag),
                    integer(values.get(PREFIX + "minReplacementIntervalDays")),
                    Math.toIntExact(integer(values.get(PREFIX + "replacementWindowDays"))),
                    Math.toIntExact(integer(values.get(PREFIX + "maxReplacementsInWindow"))),
                    integer(values.get(VERSION_KEY)));
            return policy.fieldErrors(now).isEmpty() ? policy : null;
        } catch (RuntimeException invalid) { return null; }
    }

    private static long integer(String value) {
        if (value == null || !value.matches("[0-9]{1,16}")) throw new IllegalArgumentException("integer");
        return Long.parseLong(value);
    }

    public Map<String, String> fieldErrors(long now) {
        var errors = new java.util.LinkedHashMap<String, String>();
        if (policyVersion < 1) errors.put("policyVersion", "策略版本无效");
        if (replacementWindowDays < 1 || replacementWindowDays > 365)
            errors.put("replacementWindowDays", "滚动窗口须为1至365天");
        if (maxReplacementsInWindow < 1 || maxReplacementsInWindow > 100)
            errors.put("maxReplacementsInWindow", "更换次数须为1至100");
        try {
            if (minReplacementIntervalDays < 0 || minReplacementIntervalDays > 9_007_199_254_740_991L
                    || Math.addExact(now, Math.multiplyExact(minReplacementIntervalDays, DAY)) > MAX_EPOCH_MILLIS)
                errors.put("minReplacementIntervalDays", "间隔须为可计算的非负整数天");
        } catch (ArithmeticException invalid) { errors.put("minReplacementIntervalDays", "间隔时间超出范围"); }
        return Map.copyOf(errors);
    }

    public static boolean isReplacement(String oldInstallation, String oldHardware,
                                         String targetInstallation, String targetHardware) {
        return oldInstallation != null && (!Objects.equals(oldInstallation, targetInstallation)
                || oldHardware != null && !oldHardware.isBlank() && !Objects.equals(oldHardware, targetHardware));
    }

    public Decision evaluate(long now, Long changedAt, History history, List<Long> successfulTimes,
                             boolean waiveCount, boolean waiveInterval, List<String> hardBlocks) {
        List<String> blocked = new ArrayList<>(hardBlocks == null ? List.of() : hardBlocks);
        if (!fieldErrors(now).isEmpty()) {
            blocked.add("PHONE_REPLACEMENT_POLICY_UNAVAILABLE");
            return new Decision(false, null, null, false, history == null ? null : history.completeFrom(),
                    null, List.copyOf(blocked));
        }
        if (!allowReplacement) blocked.add("PHONE_REPLACEMENT_DISABLED");
        long windowStart = now - replacementWindowDays * DAY;
        boolean complete = history != null && (history.allHistoryKnown()
                || history.completeFrom() != null && history.completeFrom() <= windowStart);
        List<Long> times = successfulTimes.stream().filter(t -> t > windowStart && t <= now).sorted().toList();
        Long used = complete ? (long) times.size() : null;
        Long remaining = complete ? Math.max(0L, maxReplacementsInWindow - used) : null;
        Long countAt = now;
        if (!complete) {
            countAt = history != null && history.completeFrom() != null
                    ? safeAdd(history.completeFrom(), replacementWindowDays * DAY) : null;
            if (!waiveCount) blocked.add("PHONE_REPLACEMENT_HISTORY_UNKNOWN");
        } else if (times.size() >= maxReplacementsInWindow) {
            countAt = safeAdd(times.get(times.size() - maxReplacementsInWindow), replacementWindowDays * DAY);
            if (!waiveCount) blocked.add("PHONE_REPLACEMENT_LIMIT_REACHED");
        }
        Long intervalAt = now;
        if (minReplacementIntervalDays > 0) {
            if (history == null || !history.changedAtVerified() || changedAt == null) {
                intervalAt = null;
                if (!waiveInterval) blocked.add("PHONE_BINDING_TIME_UNKNOWN");
            } else {
                intervalAt = safeAdd(changedAt, Math.multiplyExact(minReplacementIntervalDays, DAY));
                if (intervalAt == null) {
                    if (!waiveInterval) blocked.add("PHONE_BINDING_TIME_UNKNOWN");
                } else if (now < intervalAt && !waiveInterval) blocked.add("PHONE_REPLACEMENT_COOLDOWN");
            }
        }
        // nextAllowedAt always describes ordinary replacement, even when a grant permits this attempt.
        Long next = countAt == null || intervalAt == null ? null : Math.max(now, Math.max(countAt, intervalAt));
        if (!allowReplacement || hardBlocks != null && !hardBlocks.isEmpty() || !fieldErrors(now).isEmpty()) next = null;
        return new Decision(blocked.isEmpty(), used, remaining, complete,
                history == null ? null : history.completeFrom(), next, List.copyOf(blocked));
    }

    private static Long safeAdd(long left, long right) {
        try { long result = Math.addExact(left, right); return result <= MAX_EPOCH_MILLIS ? result : null; }
        catch (ArithmeticException invalid) { return null; }
    }
}
