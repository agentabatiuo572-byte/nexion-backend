package ffdd.opsconsole.risk.application;

import ffdd.opsconsole.risk.domain.RiskRuleView;
import ffdd.opsconsole.risk.facade.WithdrawalRiskContext;
import ffdd.opsconsole.risk.facade.WithdrawalRiskDecision;
import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/** Shared deterministic evaluator used by production D2 entry and K3 dry-run. */
@Component
public class K3WithdrawalRuleEvaluator {
    private static final Pattern AMOUNT = Pattern.compile(
            "^(?:单笔|single)\\s*(>=|>)\\s*\\$?([\\d,]+(?:\\.\\d+)?)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern VELOCITY = Pattern.compile(
            "^24h\\s*(>=|>)\\s*(\\d+)\\s*(?:笔|withdrawals?)\\s*(?:或|or)\\s*(>=|>)\\s*\\$?([\\d,]+(?:\\.\\d+)?)$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern NEW_ACCOUNT = Pattern.compile(
            "^(?:注册|registered)\\s*(<|<=)\\s*(\\d+)\\s*(?:天|days?)$", Pattern.CASE_INSENSITIVE);
    private static final Map<String, Integer> SEVERITY = Map.of(
            "pass", 0, "delay", 1, "manual", 2, "freeze", 3);
    private static final Set<String> LOW_REPUTATION = Set.of("low", "blacklist", "blacklisted");

    public WithdrawalRiskDecision evaluate(List<RiskRuleView> rules, WithdrawalRiskContext context) {
        if (rules == null) {
            throw new IllegalStateException("K3_RULE_SOURCE_UNAVAILABLE");
        }
        if (context == null) {
            throw new IllegalStateException("K3_WITHDRAWAL_CONTEXT_UNAVAILABLE");
        }
        validateActiveRules(rules);
        List<RiskRuleView> matched = rules.stream()
                .filter(this::activeSupportedAction)
                .filter(rule -> matches(rule, context))
                .toList();
        RiskRuleView primary = matched.stream().max(Comparator
                .comparingInt((RiskRuleView rule) -> SEVERITY.getOrDefault(normalize(rule.action()), 0))
                .thenComparingInt(rule -> rule.priority() == null ? 0 : rule.priority())
                .thenComparing(rule -> safe(rule.ruleId())))
                .orElse(null);
        if (primary == null) {
            return new WithdrawalRiskDecision("pass", null, null, List.of());
        }
        return new WithdrawalRiskDecision(
                normalize(primary.action()), primary.ruleId(), primary.dimension(), matched);
    }

    public boolean requiresThirdParty(List<RiskRuleView> rules) {
        if (rules == null) {
            throw new IllegalStateException("K3_RULE_SOURCE_UNAVAILABLE");
        }
        validateActiveRules(rules);
        return rules.stream()
                .filter(this::activeSupportedAction)
                .filter(rule -> isAddressDimension(safe(rule.dimension()).toLowerCase(Locale.ROOT)))
                .map(rule -> K3AddressReputationRuleConfig.parse(core(rule.conditionText())).orElseThrow())
                .anyMatch(K3AddressReputationRuleConfig::usesThirdParty);
    }

    private void validateActiveRules(List<RiskRuleView> rules) {
        if (rules.stream().anyMatch(rule -> active(rule) && !validActiveRule(rule))) {
            throw new IllegalStateException("K3_ACTIVE_RULE_INVALID");
        }
    }

    private boolean activeSupportedAction(RiskRuleView rule) {
        return active(rule)
                && Set.of("delay", "manual", "freeze").contains(normalize(rule.action()));
    }

    private boolean active(RiskRuleView rule) {
        return rule != null && "active".equalsIgnoreCase(safe(rule.state()));
    }

    private boolean validActiveRule(RiskRuleView rule) {
        if (rule == null || !Set.of("delay", "manual", "freeze").contains(normalize(rule.action()))
                || rule.priority() == null || rule.priority() < 1 || rule.priority() > 100) {
            return false;
        }
        String condition = core(rule.conditionText());
        String dimension = safe(rule.dimension()).toLowerCase(Locale.ROOT);
        if (isDimension(dimension, "金额", "amount", "largeamountusdt")) {
            return AMOUNT.matcher(condition).matches();
        }
        if (isDimension(dimension, "速度", "velocity", "velocity24h")) {
            return VELOCITY.matcher(condition).matches();
        }
        if (isDimension(dimension, "新账户", "accountage", "newaccount", "newaccountprotectdays")) {
            return NEW_ACCOUNT.matcher(condition).matches();
        }
        return isAddressDimension(dimension)
                && K3AddressReputationRuleConfig.parse(condition).isPresent();
    }

    private boolean matches(RiskRuleView rule, WithdrawalRiskContext context) {
        String condition = core(rule.conditionText());
        String dimension = safe(rule.dimension()).toLowerCase(Locale.ROOT);
        if (isDimension(dimension, "金额", "amount", "largeamountusdt")) {
            Matcher matcher = AMOUNT.matcher(condition);
            return matcher.matches() && compare(context.amountUsdt(), decimal(matcher.group(2)), matcher.group(1));
        }
        if (isDimension(dimension, "速度", "velocity", "velocity24h")) {
            Matcher matcher = VELOCITY.matcher(condition);
            return matcher.matches()
                    && (compare(integer(context.withdrawalCount24h()), decimal(matcher.group(2)), matcher.group(1))
                    || compare(context.withdrawalSum24h(), decimal(matcher.group(4)), matcher.group(3)));
        }
        if (isDimension(dimension, "新账户", "accountage", "newaccount", "newaccountprotectdays")) {
            Matcher matcher = NEW_ACCOUNT.matcher(condition);
            return matcher.matches()
                    && compare(integer(context.accountAgeDays()), decimal(matcher.group(2)), matcher.group(1));
        }
        if (isAddressDimension(dimension)) {
            K3AddressReputationRuleConfig config = K3AddressReputationRuleConfig.parse(condition)
                    .orElseThrow(() -> new IllegalStateException("K3_ACTIVE_RULE_INVALID"));
            boolean internalLow = LOW_REPUTATION.contains(normalize(context.addressReputation()));
            if (!config.usesThirdParty()) {
                return internalLow;
            }
            BigDecimal thirdPartyScore = context.thirdPartyAddressReputationScore();
            if (thirdPartyScore == null) {
                throw new IllegalStateException("K3_ADDRESS_REPUTATION_UNAVAILABLE");
            }
            if (thirdPartyScore.compareTo(BigDecimal.ZERO) < 0
                    || thirdPartyScore.compareTo(BigDecimal.ONE) > 0) {
                throw new IllegalStateException("K3_ADDRESS_REPUTATION_INVALID");
            }
            boolean thirdPartyLow = thirdPartyScore.compareTo(config.lowThreshold()) < 0;
            return switch (config.source()) {
                case INTERNAL -> internalLow;
                case THIRD_PARTY -> thirdPartyLow;
                case COMBINED -> internalLow || thirdPartyLow;
            };
        }
        return false;
    }

    private boolean isAddressDimension(String dimension) {
        return isDimension(dimension, "地址信誉", "address", "addressreputation", "addressreputationsource");
    }

    private boolean isDimension(String actual, String... candidates) {
        for (String candidate : candidates) {
            if (actual.equals(candidate) || actual.contains(candidate)) return true;
        }
        return false;
    }

    private boolean compare(BigDecimal actual, BigDecimal threshold, String operator) {
        if (actual == null || threshold == null) return false;
        int compared = actual.compareTo(threshold);
        return switch (operator) {
            case ">" -> compared > 0;
            case ">=" -> compared >= 0;
            case "<" -> compared < 0;
            case "<=" -> compared <= 0;
            default -> false;
        };
    }

    private BigDecimal integer(Integer value) {
        return value == null ? null : BigDecimal.valueOf(value);
    }

    private BigDecimal decimal(String value) {
        try {
            return new BigDecimal(safe(value).replace(",", ""));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private String core(String value) {
        String text = safe(value).replace('，', ',');
        int arrow = text.indexOf("->");
        if (arrow < 0) arrow = text.indexOf('→');
        return (arrow < 0 ? text : text.substring(0, arrow)).trim();
    }

    private String normalize(String value) {
        return safe(value).trim().toLowerCase(Locale.ROOT);
    }

    private String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
