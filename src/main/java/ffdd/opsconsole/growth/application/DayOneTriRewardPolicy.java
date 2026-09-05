package ffdd.opsconsole.growth.application;

import ffdd.opsconsole.shared.exception.BizException;
import java.math.BigDecimal;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Resolves the PC-managed full / grace / expired Day-One reward policy. */
final class DayOneTriRewardPolicy {
    /** The PC contract deliberately accepts one canonical, NEX-denominated ladder only. */
    private static final Pattern STANDARD_POLICY = Pattern.compile(
            "^\\s*([0-9]+(?:\\.[0-9]+)?)\\s*/\\s*([0-9]+(?:\\.[0-9]+)?)\\s*/\\s*(0(?:\\.0+)?)\\s+NEX\\s*$",
            Pattern.CASE_INSENSITIVE);
    private static final long FULL_REWARD_HOURS = 24L;

    private DayOneTriRewardPolicy() {
    }

    static BigDecimal effectiveDayOneReward(String rawPolicy, long accountAgeHours, long eligibilityHours) {
        if (rawPolicy == null || eligibilityHours < FULL_REWARD_HOURS || eligibilityHours > 720L) {
            throw new BizException(503, "H3_DAY_ONE_REWARD_POLICY_UNAVAILABLE");
        }
        BigDecimal[] rewards = parse(rawPolicy);
        long age = Math.max(0L, accountAgeHours);
        return age < FULL_REWARD_HOURS ? rewards[0]
                : age < eligibilityHours ? rewards[1] : rewards[2];
    }

    static String normalizePolicy(String rawPolicy) {
        BigDecimal[] rewards = parse(rawPolicy);
        return plain(rewards[0]) + " / " + plain(rewards[1]) + " / " + plain(rewards[2]) + " NEX";
    }

    private static BigDecimal[] parse(String rawPolicy) {
        if (rawPolicy == null) throw unavailable();
        Matcher matcher = STANDARD_POLICY.matcher(rawPolicy);
        if (!matcher.matches()) throw unavailable();
        BigDecimal[] rewards = {
                new BigDecimal(matcher.group(1)), new BigDecimal(matcher.group(2)), new BigDecimal(matcher.group(3))};
        if (rewards[0].signum() <= 0
                || rewards[1].signum() < 0 || rewards[1].compareTo(rewards[0]) > 0
                || rewards[2].signum() != 0) {
            throw unavailable();
        }
        return rewards;
    }

    private static String plain(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }

    private static BizException unavailable() {
        return new BizException(503, "H3_DAY_ONE_REWARD_POLICY_UNAVAILABLE");
    }
}
