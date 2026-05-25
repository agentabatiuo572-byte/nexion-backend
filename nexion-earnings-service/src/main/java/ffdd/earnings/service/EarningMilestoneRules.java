package ffdd.earnings.service;

import java.math.BigDecimal;
import java.util.List;

public final class EarningMilestoneRules {
    private static final int SCALE = 6;
    private static final List<Rule> RULES = List.of(
            rule("earn-100", "First $100 earned", "100", "100"),
            rule("earn-500", "Half-grand reached", "500", "250"),
            rule("earn-1000", "Four-figure earner", "1000", "500"),
            rule("earn-5000", "Mid five-figure operator", "5000", "1500"),
            rule("earn-10000", "Top 2% of Nexion earners", "10000", "3000"));

    private EarningMilestoneRules() {
    }

    public static List<Rule> rules() {
        return RULES;
    }

    private static Rule rule(String milestoneId, String label, String thresholdUsdt, String rewardNex) {
        return new Rule(
                milestoneId,
                label,
                new BigDecimal(thresholdUsdt).setScale(SCALE),
                new BigDecimal(rewardNex).setScale(SCALE));
    }

    public record Rule(String milestoneId, String label, BigDecimal thresholdUsdt, BigDecimal rewardNex) {
    }
}
