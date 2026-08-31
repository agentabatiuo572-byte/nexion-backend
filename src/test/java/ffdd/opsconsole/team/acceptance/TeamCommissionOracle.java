package ffdd.opsconsole.team.acceptance;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Independent acceptance oracle for F1-F4 money and rank rules.
 *
 * <p>This class deliberately has no dependency on a production service, mapper, repository,
 * Spring bean, or configuration facade. Acceptance tests feed it an immutable configuration
 * snapshot and compare its output with the product-side events and ledgers.
 */
final class TeamCommissionOracle {
    static final int SCALE = 6;
    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(SCALE);

    private TeamCommissionOracle() {
    }

    static List<NetworkPayout> network(
            BigDecimal subtotalUsdt,
            List<NetworkLevel> levels,
            BigDecimal promoMultiplier,
            BigDecimal maximumExitPercent,
            int depthGateLayer,
            int depthGateRank) {
        requireNonNegative(subtotalUsdt, "subtotalUsdt");
        requireNonNegative(maximumExitPercent, "maximumExitPercent");
        if (promoMultiplier == null || promoMultiplier.compareTo(BigDecimal.ONE) < 0) {
            throw new IllegalArgumentException("promoMultiplier must be >= 1");
        }

        BigDecimal cap = subtotalUsdt.multiply(maximumExitPercent)
                .divide(HUNDRED, SCALE, RoundingMode.DOWN);
        BigDecimal allocated = ZERO;
        List<NetworkPayout> payouts = new ArrayList<>();
        for (NetworkLevel level : levels) {
            if (level.layer() < 1 || level.layer() > 7 || level.paused()
                    || level.usdtPercent() == null || level.usdtPercent().signum() <= 0) {
                continue;
            }
            if (level.layer() >= depthGateLayer && level.rank() < depthGateRank) {
                continue;
            }
            BigDecimal base = subtotalUsdt.multiply(level.usdtPercent())
                    .divide(HUNDRED, SCALE, RoundingMode.HALF_UP);
            BigDecimal influence = level.layer() == 1
                    ? BigDecimal.ONE : positiveOrOne(level.influenceScore());
            BigDecimal amount = base.multiply(influence).setScale(SCALE, RoundingMode.HALF_UP)
                    .multiply(promoMultiplier).setScale(SCALE, RoundingMode.HALF_UP);
            BigDecimal remaining = cap.subtract(allocated).max(BigDecimal.ZERO);
            amount = amount.min(remaining).setScale(SCALE, RoundingMode.DOWN);
            if (amount.signum() <= 0) {
                continue;
            }
            BigDecimal nex = level.nexPerUsd() == null || level.nexPerUsd().signum() <= 0
                    ? ZERO
                    : amount.multiply(level.nexPerUsd()).setScale(SCALE, RoundingMode.HALF_UP);
            payouts.add(new NetworkPayout(level.recipientUserId(), level.layer(), amount, nex));
            allocated = allocated.add(amount);
        }
        return List.copyOf(payouts);
    }

    static BinaryPayout binary(
            BigDecimal leftVolume,
            BigDecimal rightVolume,
            BigDecimal consumedBefore,
            BigDecimal threshold,
            BigDecimal matchRate,
            BigDecimal dailyCap,
            int periodDays,
            BigDecimal settledInWindow) {
        for (Map.Entry<String, BigDecimal> entry : Map.of(
                "leftVolume", leftVolume,
                "rightVolume", rightVolume,
                "consumedBefore", consumedBefore,
                "threshold", threshold,
                "matchRate", matchRate,
                "dailyCap", dailyCap,
                "settledInWindow", settledInWindow).entrySet()) {
            requireNonNegative(entry.getValue(), entry.getKey());
        }
        if (periodDays < 1) {
            throw new IllegalArgumentException("periodDays must be positive");
        }
        BigDecimal left = moneyDown(leftVolume);
        BigDecimal right = moneyDown(rightVolume);
        BigDecimal cap = dailyCap.multiply(BigDecimal.valueOf(periodDays))
                .setScale(SCALE, RoundingMode.DOWN);
        if (left.compareTo(threshold) < 0 || right.compareTo(threshold) < 0
                || matchRate.signum() <= 0) {
            return new BinaryPayout(ZERO, ZERO, cap, "THRESHOLD_OR_RATE_BLOCKED");
        }
        BigDecimal availableMatched = left.min(right).subtract(consumedBefore)
                .max(BigDecimal.ZERO).setScale(SCALE, RoundingMode.DOWN);
        BigDecimal capRemaining = cap.subtract(settledInWindow)
                .max(BigDecimal.ZERO).setScale(SCALE, RoundingMode.DOWN);
        if (availableMatched.signum() <= 0 || capRemaining.signum() <= 0) {
            return new BinaryPayout(ZERO, ZERO, cap, "NO_CAPACITY");
        }
        BigDecimal capMatched = capRemaining.divide(matchRate, SCALE, RoundingMode.DOWN);
        BigDecimal consumed = availableMatched.min(capMatched).setScale(SCALE, RoundingMode.DOWN);
        BigDecimal amount = consumed.multiply(matchRate).setScale(SCALE, RoundingMode.DOWN);
        return new BinaryPayout(consumed, amount, cap, "PAYABLE");
    }

    static Map<Long, BigDecimal> leadership(BigDecimal poolAmount, LinkedHashMap<Long, Integer> votes) {
        requireNonNegative(poolAmount, "poolAmount");
        List<Map.Entry<Long, Integer>> eligible = votes.entrySet().stream()
                .filter(entry -> entry.getKey() != null && entry.getValue() != null && entry.getValue() > 0)
                .toList();
        long totalVotes = eligible.stream().mapToLong(Map.Entry::getValue).sum();
        if (poolAmount.signum() <= 0 || totalVotes <= 0) {
            return Map.of();
        }
        BigDecimal normalizedPool = poolAmount.setScale(SCALE, RoundingMode.DOWN);
        BigDecimal allocated = ZERO;
        Map<Long, BigDecimal> result = new LinkedHashMap<>();
        for (int index = 0; index < eligible.size(); index++) {
            Map.Entry<Long, Integer> voter = eligible.get(index);
            BigDecimal share = index == eligible.size() - 1
                    ? normalizedPool.subtract(allocated).setScale(SCALE, RoundingMode.DOWN)
                    : normalizedPool.multiply(BigDecimal.valueOf(voter.getValue()))
                            .divide(BigDecimal.valueOf(totalVotes), SCALE, RoundingMode.DOWN);
            if (share.signum() > 0) {
                result.put(voter.getKey(), share);
                allocated = allocated.add(share);
            }
        }
        return Map.copyOf(result);
    }

    static int nextRank(int currentRank, RankSnapshot snapshot, List<RankRule> orderedRules,
            boolean permanentProtection) {
        int reachable = 0;
        while (reachable < 12) {
            int target = reachable + 1;
            RankRule rule = orderedRules.stream()
                    .filter(candidate -> candidate.rank() == target)
                    .findFirst()
                    .orElse(null);
            if (rule == null || !meets(snapshot, rule)) {
                break;
            }
            reachable = target;
        }
        if (reachable > currentRank) {
            return Math.min(12, currentRank + 1);
        }
        return permanentProtection && reachable < currentRank ? currentRank : reachable;
    }

    private static boolean meets(RankSnapshot snapshot, RankRule rule) {
        if (positive(rule.selfBuyUsd()) && snapshot.selfBuyUsd().compareTo(rule.selfBuyUsd()) < 0) {
            return false;
        }
        if (rule.qualifiedDirects() > 0 && snapshot.qualifiedDirects() < rule.qualifiedDirects()) {
            return false;
        }
        if (positive(rule.teamVolumeUsd())
                && snapshot.teamVolumeUsd().compareTo(rule.teamVolumeUsd()) < 0) {
            return false;
        }
        if (rule.requiredLegCount() > 0) {
            long qualified = snapshot.directLegRanks().stream()
                    .filter(rank -> rank >= rule.requiredLegRank()).count();
            return qualified >= rule.requiredLegCount();
        }
        return true;
    }

    private static BigDecimal moneyDown(BigDecimal value) {
        return value.setScale(SCALE, RoundingMode.DOWN);
    }

    private static BigDecimal positiveOrOne(BigDecimal value) {
        return value == null || value.signum() <= 0 ? BigDecimal.ONE : value;
    }

    private static boolean positive(BigDecimal value) {
        return value != null && value.signum() > 0;
    }

    private static void requireNonNegative(BigDecimal value, String name) {
        if (value == null || value.signum() < 0) {
            throw new IllegalArgumentException(name + " must be non-negative");
        }
    }

    record NetworkLevel(
            long recipientUserId,
            int layer,
            int rank,
            boolean paused,
            BigDecimal usdtPercent,
            BigDecimal nexPerUsd,
            BigDecimal influenceScore) {
    }

    record NetworkPayout(long recipientUserId, int layer, BigDecimal usdt, BigDecimal nex) {
    }

    record BinaryPayout(BigDecimal consumedMatched, BigDecimal amountUsdt, BigDecimal capUsdt, String status) {
    }

    record RankSnapshot(
            BigDecimal selfBuyUsd,
            int qualifiedDirects,
            BigDecimal teamVolumeUsd,
            List<Integer> directLegRanks) {
    }

    record RankRule(
            int rank,
            BigDecimal selfBuyUsd,
            int qualifiedDirects,
            BigDecimal teamVolumeUsd,
            int requiredLegRank,
            int requiredLegCount) {
    }
}
