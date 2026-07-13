package ffdd.opsconsole.janus.domain;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Component;

@Component
public class JanusRuleEvaluator {
    public record Result(boolean passed, int passedLeaves, int totalLeaves, List<String> trace) {}

    public Result evaluate(JsonNode ruleTree, JanusDeviceView device) {
        List<String> trace = new ArrayList<>();
        Counter counter = new Counter();
        boolean passed = evaluateNode(ruleTree, device, trace, counter);
        return new Result(passed, counter.passed, counter.total, trace);
    }

    private boolean evaluateNode(JsonNode node, JanusDeviceView device, List<String> trace, Counter counter) {
        if (node == null || node.isNull()) return false;
        if (node.has("rules")) {
            String mode = node.path("mode").asText("ALL").toUpperCase(Locale.ROOT);
            if ("WEIGHTED_SCORE".equals(mode)) {
                return weighted(node, device, trace, counter) >= node.path("threshold").asDouble(1);
            }
            List<Boolean> results = new ArrayList<>();
            node.path("rules").forEach(child -> results.add(evaluateNode(child, device, trace, counter)));
            long hit = results.stream().filter(Boolean::booleanValue).count();
            return switch (mode) {
                case "ANY" -> hit > 0;
                case "NOT" -> results.stream().noneMatch(Boolean::booleanValue);
                case "N_OF_M" -> hit >= Math.max(1, node.path("required").asInt(1));
                default -> !results.isEmpty() && hit == results.size();
            };
        }
        String field = node.path("field").asText();
        String op = node.path("op").asText("=");
        Object actual = fieldValue(device, field);
        boolean passed = compare(actual, op, node.get("value"));
        counter.total++;
        if (passed) counter.passed++;
        trace.add((node.path("label").asText(field)) + ":" + (passed ? "PASS" : "FAIL"));
        return passed;
    }

    private double weighted(JsonNode group, JanusDeviceView device, List<String> trace, Counter counter) {
        double score = 0;
        for (JsonNode child : group.path("rules")) {
            boolean passed = evaluateNode(child, device, trace, counter);
            if (passed) score += child.path("weight").asDouble(1);
        }
        return score;
    }

    private Object fieldValue(JanusDeviceView d, String field) {
        return switch (field) {
            case "installDays" -> d.installDays();
            case "maturityScore" -> d.maturityScore();
            case "recommendationScore" -> d.recommendationScore();
            case "environmentRiskScore" -> d.environmentRiskScore();
            case "inviteCode" -> d.inviteCode();
            case "channel" -> d.channel();
            case "activated" -> d.activated();
            case "status" -> d.status();
            case "appOpenCount", "sessionCount", "foregroundDurationSeconds", "repeatStreakDays",
                 "benchmarkViewed", "optimizeDone", "marketViewed", "walletViewed" -> scalar(d.maturity(), field);
            case "isHeadless", "automationSignalCount", "fpBlocklistHit", "screenAnomaly", "timezoneMismatch",
                 "languageMismatch" -> scalar(d.environment(), field);
            default -> null;
        };
    }

    private Object scalar(JsonNode object, String field) {
        JsonNode value = object == null ? null : object.get(field);
        if (value == null || value.isNull()) return null;
        if (value.isBoolean()) return value.booleanValue();
        if (value.isNumber()) return value.doubleValue();
        return value.asText();
    }

    private boolean compare(Object actual, String op, JsonNode expected) {
        if (actual == null || expected == null || expected.isNull()) return false;
        if ("in".equals(op) || "notIn".equals(op)) {
            boolean contained = expected.isArray() && contains(expected, actual);
            return "in".equals(op) ? contained : !contained;
        }
        if ("between".equals(op)) {
            if (!expected.isArray() || expected.size() < 2) return false;
            double value = number(actual);
            return value >= expected.get(0).asDouble() && value <= expected.get(1).asDouble();
        }
        if ("contains".equals(op)) return String.valueOf(actual).contains(expected.asText());
        if (actual instanceof Number || expected.isNumber()) {
            double left = number(actual);
            double right = expected.asDouble();
            return switch (op) {
                case "!=" -> left != right;
                case ">" -> left > right;
                case ">=" -> left >= right;
                case "<" -> left < right;
                case "<=" -> left <= right;
                default -> left == right;
            };
        }
        boolean equal = String.valueOf(actual).equalsIgnoreCase(expected.asText());
        return "!=".equals(op) ? !equal : equal;
    }

    private boolean contains(JsonNode values, Object actual) {
        for (JsonNode value : values) {
            if (String.valueOf(actual).equalsIgnoreCase(value.asText())) return true;
        }
        return false;
    }

    private double number(Object value) {
        if (value instanceof Number number) return number.doubleValue();
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return Double.NaN;
        }
    }

    private static final class Counter {
        int passed;
        int total;
    }
}
