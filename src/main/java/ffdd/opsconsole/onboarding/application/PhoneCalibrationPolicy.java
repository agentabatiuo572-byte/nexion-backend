package ffdd.opsconsole.onboarding.application;

import ffdd.opsconsole.shared.exception.BizException;
import java.math.BigDecimal;
import java.text.Normalizer;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Platform compute points, never a claim of measured physical TOPS. */
public record PhoneCalibrationPolicy(long version, long effectiveAt, List<BigDecimal> thresholds, List<Rule> rules) {
    public record Rule(String id, String platform, String model, String soc, String gpu,
                       BigDecimal minMemoryGb, BigDecimal maxMemoryGb, BigDecimal computeValue, String evidence) { }
    public record Hardware(String platform, String model, String soc, String gpu, BigDecimal memoryGb) { }
    public record Match(String status, String ruleId, BigDecimal computeValue, Integer tier, long ruleVersion) { }

    public PhoneCalibrationPolicy {
        thresholds = thresholds == null ? List.of() : List.copyOf(thresholds);
        rules = rules == null ? List.of() : List.copyOf(rules);
    }

    public void validate() {
        if (version < 1 || effectiveAt < 0 || thresholds.size() != 4 || rules.size() > 500) invalid();
        BigDecimal previous = BigDecimal.ZERO;
        for (BigDecimal threshold : thresholds) {
            if (!positive(threshold) || threshold.compareTo(previous) <= 0) invalid();
            previous = threshold;
        }
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < rules.size(); i++) {
            Rule r = rules.get(i);
            if (r == null || r.id() == null || !r.id().matches("[A-Za-z0-9_-]{1,64}") || !ids.add(r.id())
                    || !Set.of("android", "ios").contains(r.platform()) || !text(r.model(), 128, true)
                    || !text(r.soc(), 128, false) || !text(r.gpu(), 256, false)
                    || !positive(r.minMemoryGb()) || !positive(r.maxMemoryGb())
                    || r.maxMemoryGb().compareTo(new BigDecimal("129")) > 0
                    || r.maxMemoryGb().compareTo(r.minMemoryGb()) <= 0
                    || !positive(r.computeValue()) || !text(r.evidence(), 500, false)) invalid();
            for (int j = 0; j < i; j++) {
                Rule other = rules.get(j);
                if (sameHardware(r, other) && normalize(r.model()).equals(normalize(other.model()))
                        && r.minMemoryGb().compareTo(other.maxMemoryGb()) < 0
                        && other.minMemoryGb().compareTo(r.maxMemoryGb()) < 0) {
                    throw new BizException(422, "PHONE_CALIBRATION_RULE_OVERLAP");
                }
            }
        }
    }

    public Match match(Hardware hardware) {
        validate();
        if (hardware == null || !text(hardware.platform(), 16, false) || !text(hardware.model(), 128, false)
                || !text(hardware.soc(), 128, false) || !text(hardware.gpu(), 256, false)
                || hardware.memoryGb() == null || hardware.memoryGb().signum() <= 0
                || hardware.memoryGb().compareTo(new BigDecimal("128")) > 0) return pending("HARDWARE_INCOMPLETE");
        List<Rule> modelRules = rules.stream().filter(r -> r.platform().equals(hardware.platform())
                && !r.model().isBlank() && normalize(r.model()).equals(normalize(hardware.model()))).toList();
        // A known model with a conflicting SoC/GPU must not fall back to a generic rule.
        List<Rule> candidates = (modelRules.isEmpty() ? rules.stream().filter(r -> r.model().isBlank()).toList() : modelRules)
                .stream().filter(r -> r.platform().equals(hardware.platform())
                        && normalize(r.soc()).equals(normalize(hardware.soc()))
                        && normalize(r.gpu()).equals(normalize(hardware.gpu()))
                        && hardware.memoryGb().compareTo(r.minMemoryGb()) >= 0
                        && hardware.memoryGb().compareTo(r.maxMemoryGb()) < 0).toList();
        if (candidates.size() != 1) return pending(candidates.isEmpty()
                ? (modelRules.isEmpty() ? "HARDWARE_UNKNOWN" : "HARDWARE_CONFLICT") : "HARDWARE_AMBIGUOUS");
        Rule rule = candidates.get(0);
        return new Match("MATCHED", rule.id(), rule.computeValue(), tierFor(rule.computeValue()), version);
    }

    public int tierFor(BigDecimal value) {
        if (!positive(value)) throw new BizException(422, "PHONE_CALIBRATION_VALUE_INVALID");
        int tier = 1;
        for (BigDecimal threshold : thresholds) if (value.compareTo(threshold) >= 0) tier++;
        return tier;
    }

    private Match pending(String reason) { return new Match(reason, null, null, null, version); }
    private static boolean sameHardware(Rule a, Rule b) {
        return a.platform().equals(b.platform()) && normalize(a.soc()).equals(normalize(b.soc()))
                && normalize(a.gpu()).equals(normalize(b.gpu()));
    }
    public static String normalize(String value) {
        return value == null ? "" : Normalizer.normalize(value, Normalizer.Form.NFKC)
                .trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }
    private static boolean text(String value, int max, boolean empty) {
        return value != null && value.length() <= max && (empty || !value.isBlank())
                && value.codePoints().noneMatch(Character::isISOControl);
    }
    private static boolean positive(BigDecimal value) {
        return value != null && value.signum() > 0 && value.compareTo(new BigDecimal("1000000")) <= 0 && value.scale() <= 3;
    }
    private static void invalid() { throw new BizException(422, "PHONE_CALIBRATION_POLICY_INVALID"); }
}
