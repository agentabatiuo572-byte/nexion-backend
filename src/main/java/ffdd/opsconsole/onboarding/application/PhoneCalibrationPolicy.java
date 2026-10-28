package ffdd.opsconsole.onboarding.application;

import ffdd.opsconsole.shared.exception.BizException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.Normalizer;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.random.RandomGenerator;
import java.util.stream.IntStream;

/**
 * Platform compute points, never a claim of measured physical TOPS. Android
 * matches hardware rules with a fixed value; iOS matches only the published
 * iPhone model rules, whose operator-preset range is drawn once by
 * OnboardingCalibrationService and stored, never re-drawn by a match.
 */
public record PhoneCalibrationPolicy(long version, long effectiveAt, List<BigDecimal> thresholds, List<Rule> rules,
                                     List<IosModelRule> iosModelRules) {
    public static final String HARDWARE_RULE = "HARDWARE_RULE";
    public static final String IOS_MODEL_RANGE = "IOS_MODEL_RANGE";
    public static final String OPERATOR_PRESET = "OPERATOR_PRESET";
    public static final Set<String> CATALOG_CLASSES =
            Set.of("APPLE_CURRENT_SNAPSHOT", "APPLE_RELEASED_HISTORICAL", "OPERATOR_ADDED");
    /** Names that identify no specific model: they never match and cannot be configured. */
    private static final Set<String> GENERIC_MODELS = Set.of("iphone", "apple iphone", "apple", "ios", "unknown");
    private static final BigDecimal MAX_VALUE = new BigDecimal("1000000");

    public record Rule(String id, String platform, String model, String soc, String gpu,
                       BigDecimal minMemoryGb, BigDecimal maxMemoryGb, BigDecimal computeValue, String evidence) { }
    /** One iPhone model regardless of storage, colour or regional SKU; aliases are verified SDK names. */
    public record IosModelRule(String id, String model, List<String> aliases, BigDecimal minComputeValue,
                               BigDecimal maxComputeValue, Boolean enabled, String valueSource, String catalogClass,
                               String note) {
        public IosModelRule {
            aliases = aliases == null ? List.of() : List.copyOf(aliases);
        }
    }
    public record Hardware(String platform, String model, String soc, String gpu, BigDecimal memoryGb) { }
    /** A range match carries no computeValue or tier: both come from the stored allocation. */
    public record Match(String status, String ruleId, BigDecimal computeValue, Integer tier, long ruleVersion,
                        String method, String model, BigDecimal minComputeValue, BigDecimal maxComputeValue) {
        public Match(String status, String ruleId, BigDecimal computeValue, Integer tier, long ruleVersion) {
            this(status, ruleId, computeValue, tier, ruleVersion, HARDWARE_RULE, null, null, null);
        }
        public boolean matched() { return "MATCHED".equals(status); }
        public boolean range() { return IOS_MODEL_RANGE.equals(method); }
    }

    public PhoneCalibrationPolicy {
        thresholds = thresholds == null ? List.of() : List.copyOf(thresholds);
        rules = rules == null ? List.of() : List.copyOf(rules);
        iosModelRules = iosModelRules == null ? List.of() : List.copyOf(iosModelRules);
    }

    public PhoneCalibrationPolicy(long version, long effectiveAt, List<BigDecimal> thresholds, List<Rule> rules) {
        this(version, effectiveAt, thresholds, rules, List.of());
    }

    public void validate() {
        if (version < 1 || effectiveAt < 0 || thresholds.size() != 4 || rules.size() > 500
                || iosModelRules.size() > 500) invalid();
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
        Set<String> names = new HashSet<>();
        for (IosModelRule r : iosModelRules) {
            if (r == null || r.id() == null || !r.id().matches("[A-Za-z0-9_-]{1,64}") || !ids.add(r.id())
                    || !iosName(r.model()) || r.aliases().size() > 20 || r.enabled() == null
                    || !OPERATOR_PRESET.equals(r.valueSource())
                    || (r.catalogClass() != null && !CATALOG_CLASSES.contains(r.catalogClass()))
                    || (r.note() != null && !text(r.note(), 500, true))
                    || !preset(r.minComputeValue()) || !preset(r.maxComputeValue())
                    || r.minComputeValue().compareTo(r.maxComputeValue()) > 0) invalid();
            for (String alias : r.aliases()) if (!iosName(alias)) invalid();
            // A model or alias names exactly one rule, enabled or not, so a match is never ambiguous.
            for (String name : names(r)) if (!names.add(name)) throw new BizException(422, "PHONE_CALIBRATION_IOS_MODEL_DUPLICATE");
        }
    }

    public Match match(Hardware hardware) {
        validate();
        if (hardware != null && "ios".equals(normalize(hardware.platform()))) return matchIos(hardware.model());
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

    /** Exact model or alias only: "contains Pro" style guesses and the bare "iPhone" never match. */
    private Match matchIos(String rawModel) {
        String model = normalize(rawModel);
        if (model.isEmpty() || rawModel.length() > 128 || GENERIC_MODELS.contains(model)) return pending("MODEL_MISSING");
        IosModelRule rule = iosModelRules.stream().filter(r -> names(r).contains(model)).findFirst().orElse(null);
        if (rule == null) return pending("MODEL_UNKNOWN");
        if (!rule.enabled()) return pending("MODEL_DISABLED");
        return new Match("MATCHED", rule.id(), null, null, version, IOS_MODEL_RANGE, rule.model(),
                rule.minComputeValue(), rule.maxComputeValue());
    }

    /** Every tier a value drawn from the range could land in. */
    public List<Integer> possibleTiers(Match match) {
        if (!match.matched()) return List.of();
        if (!match.range()) return List.of(match.tier());
        return IntStream.rangeClosed(tierFor(match.minComputeValue()), tierFor(match.maxComputeValue())).boxed().toList();
    }

    /** Uniform two-decimal value in the closed range; only the first successful calibration calls this. */
    public static BigDecimal draw(Match match, RandomGenerator random) {
        if (!match.matched() || !match.range()) throw new IllegalArgumentException("not a range match");
        BigDecimal min = match.minComputeValue().setScale(2, RoundingMode.UNNECESSARY);
        long steps = match.maxComputeValue().subtract(min).movePointRight(2).longValueExact();
        return min.add(BigDecimal.valueOf(random.nextLong(steps + 1), 2));
    }

    /** Whether a stored allocation is still a legal value of the range match. */
    public static boolean within(Match match, BigDecimal value) {
        return match.matched() && match.range() && value != null && value.stripTrailingZeros().scale() <= 2
                && value.compareTo(match.minComputeValue()) >= 0 && value.compareTo(match.maxComputeValue()) <= 0;
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
    private static Set<String> names(IosModelRule rule) {
        Set<String> names = new HashSet<>();
        names.add(normalize(rule.model()));
        for (String alias : rule.aliases()) names.add(normalize(alias));
        return names;
    }
    private static boolean iosName(String value) {
        return text(value, 128, false) && !GENERIC_MODELS.contains(normalize(value));
    }
    private static boolean preset(BigDecimal value) {
        return value != null && value.signum() > 0 && value.compareTo(MAX_VALUE) <= 0
                && value.stripTrailingZeros().scale() <= 2;
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
