package ffdd.opsconsole.risk.application;

import ffdd.opsconsole.risk.domain.RiskScoreContributionView;
import ffdd.opsconsole.risk.domain.RiskScoreModelView;
import ffdd.opsconsole.risk.domain.RiskScoreRawInput;
import ffdd.opsconsole.shared.exception.BizException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/** Deterministic five-dimension scorer shared by individual and batch recomputation. */
@Component
public class K4RiskScorer {
    private static final List<String> DIMENSIONS = List.of(
            "multiAccount", "arbitrage", "withdrawVelocity", "accountAge", "anomalyBehavior");

    /** Versioned default sub-score mapping. Persisted into every K4 model snapshot. */
    public static final Map<String, Integer> DEFAULT_MAPPINGS;

    static {
        Map<String, Integer> values = new LinkedHashMap<>();
        values.put("multiAccount.mediumMin", 2);
        values.put("multiAccount.highMin", 4);
        values.put("multiAccount.mediumScore", 40);
        values.put("multiAccount.highScore", 80);
        values.put("multiAccount.fraudScore", 100);
        values.put("arbitrage.singleScore", 30);
        values.put("arbitrage.repeatMin", 2);
        values.put("arbitrage.repeatScore", 70);
        values.put("arbitrage.severeScore", 100);
        values.put("withdraw.baselineMultiplierPct", 200);
        values.put("withdraw.baselineScore", 50);
        values.put("withdraw.highFrequency24h", 5);
        values.put("withdraw.largeAmountUsd", 5000);
        values.put("withdraw.highScore", 90);
        values.put("account.matureDays", 180);
        values.put("account.newDays", 7);
        values.put("account.middleScore", 30);
        values.put("account.newLargeScore", 70);
        values.put("anomaly.lowScore", 40);
        values.put("anomaly.tamperScore", 100);
        DEFAULT_MAPPINGS = Map.copyOf(values);
    }

    public ScoreResult score(RiskScoreRawInput input, RiskScoreModelView model) {
        if (!isCurrentModelSnapshot(model)) {
            throw new BizException(500, "K4_MODEL_SNAPSHOT_INVALID");
        }
        List<RiskScoreContributionView> contributions = new ArrayList<>(DIMENSIONS.size());
        contributions.add(contribution(
                "multiAccount", "多账户", multiAccountScore(input, model),
                "关联簇账户数 " + value(input.multiAccountClusterSize())
                        + (Boolean.TRUE.equals(input.multiAccountFraud()) ? "，已判定欺诈团伙" : ""), model));
        contributions.add(contribution(
                "arbitrage", "套利与刷量", arbitrageScore(input, model),
                "套利信号 " + value(input.arbitrageSignals()) + " 条"
                        + (Boolean.TRUE.equals(input.severeArbitrage()) ? "，含高危处置" : ""), model));
        contributions.add(contribution(
                "withdrawVelocity", "提现速度", withdrawalScore(input, model),
                "24 小时 " + value(input.withdrawalCount24h()) + " 笔 / $"
                        + money(input.withdrawalAmount24h()) + "；7 天 "
                        + value(input.withdrawalCount7d()) + " 笔 / $" + money(input.withdrawalAmount7d())
                        + "；历史日均 " + decimal(input.withdrawalBaselineDailyCount()) + " 笔 / $"
                        + money(input.withdrawalBaselineDailyAmount()), model));
        contributions.add(contribution(
                "accountAge", "账户年龄", accountAgeScore(input, model),
                "账户年龄 " + value(input.accountAgeDays()) + " 天", model));
        contributions.add(contribution(
                "anomalyBehavior", "异常行为", anomalyScore(input, model),
                "异常信号 " + value(input.anomalySignals()) + " 条"
                        + (Boolean.TRUE.equals(input.tamperDetected()) ? "，命中篡改拦截" : ""), model));
        int score = contributions.stream().mapToInt(value -> value.points() == null ? 0 : value.points()).sum();
        return new ScoreResult(Math.max(0, Math.min(100, score)), List.copyOf(contributions));
    }

    public static boolean isCurrentModelSnapshot(RiskScoreModelView model) {
        return model != null
                && model.weights() != null
                && model.weights().keySet().equals(Set.copyOf(DIMENSIONS))
                && model.weights().values().stream().noneMatch(java.util.Objects::isNull)
                && model.weights().values().stream().allMatch(value -> value >= 0 && value <= 100)
                && model.weights().values().stream().mapToInt(Integer::intValue).sum() == 100
                && model.inputSources() != null
                && model.inputSources().keySet().equals(Set.copyOf(DIMENSIONS))
                && model.inputSources().values().stream().noneMatch(java.util.Objects::isNull)
                && validMappings(model.scoreMappings())
                && model.bandLowMax() != null && model.bandHighMin() != null
                && model.bandLowMax() >= 0 && model.bandHighMin() <= 100
                && model.bandLowMax() < model.bandHighMin()
                && model.autoEscalateScore() != null
                && model.autoEscalateScore() >= 70 && model.autoEscalateScore() <= 100
                && model.autoEscalateScore() >= model.bandHighMin();
    }

    private static boolean validMappings(Map<String, Integer> mappings) {
        if (mappings == null || !mappings.keySet().equals(DEFAULT_MAPPINGS.keySet())
                || mappings.values().stream().anyMatch(java.util.Objects::isNull)) {
            return false;
        }
        for (Map.Entry<String, Integer> entry : mappings.entrySet()) {
            int value = entry.getValue();
            if (entry.getKey().endsWith("Score") && (value < 0 || value > 100)) return false;
            if ("withdraw.largeAmountUsd".equals(entry.getKey()) && (value < 1 || value > 1_000_000)) return false;
            if ("withdraw.baselineMultiplierPct".equals(entry.getKey()) && (value < 100 || value > 1_000)) return false;
            if (Set.of("multiAccount.mediumMin", "multiAccount.highMin", "withdraw.highFrequency24h")
                    .contains(entry.getKey()) && (value < 1 || value > 100)) return false;
            if ("arbitrage.repeatMin".equals(entry.getKey()) && (value < 2 || value > 100)) return false;
            if (Set.of("account.newDays", "account.matureDays").contains(entry.getKey())
                    && (value < 1 || value > 10_000)) return false;
        }
        return mappings.get("multiAccount.mediumMin") < mappings.get("multiAccount.highMin")
                && mappings.get("account.newDays") < mappings.get("account.matureDays")
                && nonDecreasing(mappings, "multiAccount.mediumScore", "multiAccount.highScore", "multiAccount.fraudScore")
                && nonDecreasing(mappings, "arbitrage.singleScore", "arbitrage.repeatScore", "arbitrage.severeScore")
                && nonDecreasing(mappings, "withdraw.baselineScore", "withdraw.highScore")
                && nonDecreasing(mappings, "account.middleScore", "account.newLargeScore")
                && nonDecreasing(mappings, "anomaly.lowScore", "anomaly.tamperScore");
    }

    private static boolean nonDecreasing(Map<String, Integer> mappings, String... keys) {
        for (int index = 1; index < keys.length; index++) {
            if (mappings.get(keys[index - 1]) > mappings.get(keys[index])) return false;
        }
        return true;
    }

    private RiskScoreContributionView contribution(
            String key, String name, int subScore, String evidence, RiskScoreModelView model) {
        int weight = model.weights().getOrDefault(key, 0);
        boolean enabled = model.inputSources().getOrDefault(key, false);
        int points = enabled
                ? BigDecimal.valueOf(subScore)
                        .multiply(BigDecimal.valueOf(weight))
                        .divide(BigDecimal.valueOf(100), 0, RoundingMode.HALF_UP)
                        .intValue()
                : 0;
        String sourceState = enabled ? evidence : evidence + "；该输入来源已停用，本次贡献记 0";
        return new RiskScoreContributionView(
                key, name, subScore > 0, sourceState, subScore, weight, points);
    }

    private int multiAccountScore(RiskScoreRawInput input, RiskScoreModelView model) {
        if (Boolean.TRUE.equals(input.multiAccountFraud())) return mapping(model, "multiAccount.fraudScore");
        int size = value(input.multiAccountClusterSize());
        if (size >= mapping(model, "multiAccount.highMin")) return mapping(model, "multiAccount.highScore");
        if (size >= mapping(model, "multiAccount.mediumMin")) return mapping(model, "multiAccount.mediumScore");
        return 0;
    }

    private int arbitrageScore(RiskScoreRawInput input, RiskScoreModelView model) {
        if (Boolean.TRUE.equals(input.severeArbitrage())) return mapping(model, "arbitrage.severeScore");
        int signals = value(input.arbitrageSignals());
        if (signals >= mapping(model, "arbitrage.repeatMin")) return mapping(model, "arbitrage.repeatScore");
        return signals == 1 ? mapping(model, "arbitrage.singleScore") : 0;
    }

    private int withdrawalScore(RiskScoreRawInput input, RiskScoreModelView model) {
        int count = value(input.withdrawalCount24h());
        BigDecimal maxAmount = nonNegative(input.maxWithdrawal24h());
        if (count >= mapping(model, "withdraw.highFrequency24h")
                && maxAmount.compareTo(BigDecimal.valueOf(mapping(model, "withdraw.largeAmountUsd"))) >= 0) {
            return mapping(model, "withdraw.highScore");
        }
        BigDecimal multiplier = BigDecimal.valueOf(mapping(model, "withdraw.baselineMultiplierPct"))
                .divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP);
        BigDecimal currentDailyCount = BigDecimal.valueOf(value(input.withdrawalCount7d()))
                .divide(BigDecimal.valueOf(7), 4, RoundingMode.HALF_UP);
        BigDecimal currentDailyAmount = nonNegative(input.withdrawalAmount7d())
                .divide(BigDecimal.valueOf(7), 4, RoundingMode.HALF_UP);
        BigDecimal baselineCount = nonNegative(input.withdrawalBaselineDailyCount());
        BigDecimal baselineAmount = nonNegative(input.withdrawalBaselineDailyAmount());
        boolean countDoubled = baselineCount.signum() > 0
                && currentDailyCount.compareTo(baselineCount.multiply(multiplier)) >= 0;
        boolean amountDoubled = baselineAmount.signum() > 0
                && currentDailyAmount.compareTo(baselineAmount.multiply(multiplier)) >= 0;
        if (countDoubled || amountDoubled) return mapping(model, "withdraw.baselineScore");
        return 0;
    }

    private int accountAgeScore(RiskScoreRawInput input, RiskScoreModelView model) {
        int days = value(input.accountAgeDays());
        if (days >= mapping(model, "account.matureDays")) return 0;
        if (days >= mapping(model, "account.newDays")) return mapping(model, "account.middleScore");
        boolean largeAction = nonNegative(input.maxWithdrawal24h())
                .compareTo(BigDecimal.valueOf(mapping(model, "withdraw.largeAmountUsd"))) >= 0;
        return largeAction ? mapping(model, "account.newLargeScore") : mapping(model, "account.middleScore");
    }

    private int anomalyScore(RiskScoreRawInput input, RiskScoreModelView model) {
        if (Boolean.TRUE.equals(input.tamperDetected())) return mapping(model, "anomaly.tamperScore");
        return value(input.anomalySignals()) > 0 ? mapping(model, "anomaly.lowScore") : 0;
    }

    private int mapping(RiskScoreModelView model, String key) {
        Integer fallback = DEFAULT_MAPPINGS.get(key);
        return model.scoreMappings() == null ? fallback : model.scoreMappings().getOrDefault(key, fallback);
    }

    private static int value(Integer value) {
        return value == null ? 0 : Math.max(0, value);
    }

    private static String text(String value, String fallback) {
        return org.springframework.util.StringUtils.hasText(value) ? value.trim() : fallback;
    }

    private static String money(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value).setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private static String decimal(BigDecimal value) {
        return nonNegative(value).setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private static BigDecimal nonNegative(BigDecimal value) {
        return value == null || value.signum() < 0 ? BigDecimal.ZERO : value;
    }

    public record ScoreResult(int score, List<RiskScoreContributionView> contributions) {
    }
}
