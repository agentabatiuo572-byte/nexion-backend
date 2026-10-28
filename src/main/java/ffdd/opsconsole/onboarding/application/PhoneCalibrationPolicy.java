package ffdd.opsconsole.onboarding.application;

import ffdd.opsconsole.shared.exception.BizException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.Normalizer;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.random.RandomGenerator;
import java.util.stream.IntStream;

/**
 * Platform compute points, never a claim of measured physical TOPS. Android matches a chip tier by
 * the SoC identifiers the phone reports; iOS matches the iPhone model rules. Both carry an
 * operator-preset range that OnboardingCalibrationService draws from once and stores, never a match.
 * "rules" (the retired fixed-value Android hardware rules) is ignored when read and never written.
 */
@com.fasterxml.jackson.annotation.JsonIgnoreProperties("rules")
public record PhoneCalibrationPolicy(long version, long effectiveAt, List<BigDecimal> thresholds,
                                     List<AndroidSocRule> androidSocRules, List<IosModelRule> iosModelRules,
                                     AndroidPresetRemovals androidPresetRemovals) {
    public static final String IOS_MODEL_RANGE = "IOS_MODEL_RANGE";
    public static final String ANDROID_SOC_RANGE = "ANDROID_SOC_RANGE";
    public static final String OPERATOR_PRESET = "OPERATOR_PRESET";
    /** Calibration result fields recording the operator range; kept on the server, never sent to the App. */
    public static final String MIN_COMPUTE_VALUE = "minComputeValue";
    public static final String MAX_COMPUTE_VALUE = "maxComputeValue";
    public static final String VALUE_SOURCE = "valueSource";
    /** The Android chip a result was matched to: its name and stable key stay on the server. */
    public static final String SOC = "soc";
    public static final String SOC_KEY = "socKey";
    private static final Set<String> SERVER_ONLY_RESULT_KEYS = Set.of(MIN_COMPUTE_VALUE, MAX_COMPUTE_VALUE, VALUE_SOURCE,
            PhoneModelRisk.RISK_FLAGS, PhoneModelRisk.INSTALL_MODEL_LOCK, SOC, SOC_KEY);
    /** Raw hardware identifiers in the echoed signals; the App never needs them back. */
    private static final Set<String> SERVER_ONLY_SIGNAL_KEYS = Set.of("socIds", "soc", "gpu");
    /** An Android result also keeps which tier rule and version it came from on the server. */
    private static final Set<String> ANDROID_SERVER_ONLY_KEYS = Set.of("ruleId", "ruleVersion");
    public static final Set<String> CATALOG_CLASSES =
            Set.of("APPLE_CURRENT_SNAPSHOT", "APPLE_RELEASED_HISTORICAL", "OPERATOR_ADDED");
    /** Names that identify no specific model: they never match and cannot be configured. */
    private static final Set<String> GENERIC_MODELS = Set.of("iphone", "apple iphone", "apple", "ios", "unknown");
    /** SoC identifiers that name a vendor or nothing; they never match and cannot be configured. */
    public static final Set<String> GENERIC_SOC_IDS = Set.of("qcom", "qualcomm", "mediatek", "mtk", "samsung", "exynos",
            "google", "unisoc", "sprd", "spreadtrum", "unknown", "default", "0", "null");
    /**
     * Identifiers verified to be shared by different chips (e.g. MT6833 for Dimensity 700 and 6020). They can never be
     * published as aliases; a report carrying only such an identifier waits for verification.
     */
    public static final Set<String> AMBIGUOUS_SOC_IDS = Set.of("mt6833");
    private static final int MAX_SOC_IDS = 4;
    private static final int MAX_TIER_ALIASES = 100;
    private static final BigDecimal MAX_VALUE = new BigDecimal("1000000");

    /** One chip of an Android tier: its stable key and every verified identifier the phone may report for it. */
    public record AndroidSocChip(String socKey, String name, String manufacturer, List<String> aliases) {
        public AndroidSocChip {
            aliases = aliases == null ? List.of() : List.copyOf(aliases);
        }
    }
    /** An Android performance tier: one operator-preset range shared by all of its chips. */
    public record AndroidSocRule(String id, String name, BigDecimal minComputeValue, BigDecimal maxComputeValue,
                                 Boolean enabled, String valueSource, String note, List<AndroidSocChip> chips) {
        public AndroidSocRule {
            chips = chips == null ? List.of() : List.copyOf(chips);
        }
    }
    /** Preset chips and aliases the operator removed on purpose, so reloading the presets does not bring them back. */
    public record AndroidPresetRemovals(List<String> socKeys, List<String> aliases) {
        public static final AndroidPresetRemovals NONE = new AndroidPresetRemovals(List.of(), List.of());
        public AndroidPresetRemovals {
            socKeys = socKeys == null ? List.of() : List.copyOf(socKeys);
            aliases = aliases == null ? List.of() : List.copyOf(aliases);
        }
    }
    /**
     * One iPhone model regardless of storage, colour or regional SKU; aliases are verified SDK names.
     * The optional device facts (launch iOS, last supported major, screen scales and point sizes) feed
     * the model consistency checks of {@link PhoneModelRisk}; an empty fact skips its check.
     */
    public record IosModelRule(String id, String model, List<String> aliases, BigDecimal minComputeValue,
                               BigDecimal maxComputeValue, Boolean enabled, String valueSource, String catalogClass,
                               String note, String minOsVersion, Integer maxOsMajor, List<Integer> scales,
                               List<String> screens) {
        public IosModelRule {
            aliases = aliases == null ? List.of() : List.copyOf(aliases);
            scales = scales == null ? List.of() : List.copyOf(scales);
            screens = screens == null ? List.of() : List.copyOf(screens);
        }
        public IosModelRule(String id, String model, List<String> aliases, BigDecimal minComputeValue,
                            BigDecimal maxComputeValue, Boolean enabled, String valueSource, String catalogClass, String note) {
            this(id, model, aliases, minComputeValue, maxComputeValue, enabled, valueSource, catalogClass, note,
                    null, null, List.of(), List.of());
        }
    }
    /** socIds: every SoC identifier an Android phone reported; iOS reports only its model. */
    public record Hardware(String platform, String model, List<String> socIds) {
        public Hardware {
            socIds = socIds == null ? List.of() : socIds.stream().filter(java.util.Objects::nonNull).toList();
        }
    }
    /**
     * Every match is a range match: no computeValue or tier, both come from the stored allocation.
     * model is the iPhone model, or the chip name for Android; socKey is the Android chip's stable key.
     */
    public record Match(String status, String ruleId, BigDecimal computeValue, Integer tier, long ruleVersion,
                        String method, String model, BigDecimal minComputeValue, BigDecimal maxComputeValue, String socKey) {
        public Match(String status, String ruleId, BigDecimal computeValue, Integer tier, long ruleVersion) {
            this(status, ruleId, computeValue, tier, ruleVersion, null, null, null, null, null);
        }
        public Match(String status, String ruleId, BigDecimal computeValue, Integer tier, long ruleVersion,
                     String method, String model, BigDecimal minComputeValue, BigDecimal maxComputeValue) {
            this(status, ruleId, computeValue, tier, ruleVersion, method, model, minComputeValue, maxComputeValue, null);
        }
        public boolean matched() { return "MATCHED".equals(status); }
        public boolean range() { return IOS_MODEL_RANGE.equals(method) || ANDROID_SOC_RANGE.equals(method); }
    }

    public PhoneCalibrationPolicy {
        thresholds = thresholds == null ? List.of() : List.copyOf(thresholds);
        androidSocRules = androidSocRules == null ? List.of() : List.copyOf(androidSocRules);
        iosModelRules = iosModelRules == null ? List.of() : List.copyOf(iosModelRules);
        androidPresetRemovals = androidPresetRemovals == null ? AndroidPresetRemovals.NONE : androidPresetRemovals;
    }

    public PhoneCalibrationPolicy(long version, long effectiveAt, List<BigDecimal> thresholds,
                                  List<AndroidSocRule> androidSocRules, List<IosModelRule> iosModelRules) {
        this(version, effectiveAt, thresholds, androidSocRules, iosModelRules, AndroidPresetRemovals.NONE);
    }

    public void validate() {
        if (version < 1 || effectiveAt < 0 || thresholds.size() != 4 || androidSocRules.size() > 50
                || iosModelRules.size() > 500) invalid();
        BigDecimal previous = BigDecimal.ZERO;
        for (BigDecimal threshold : thresholds) {
            if (!positive(threshold) || threshold.compareTo(previous) <= 0) invalid();
            previous = threshold;
        }
        Set<String> ids = new HashSet<>();
        Set<String> socKeys = new HashSet<>(), socAliases = new HashSet<>();
        for (AndroidSocRule r : androidSocRules) {
            if (r == null || r.id() == null || !r.id().matches("[A-Za-z0-9_-]{1,64}") || !ids.add(r.id())
                    || !text(r.name(), 64, false) || r.enabled() == null || !OPERATOR_PRESET.equals(r.valueSource())
                    || (r.note() != null && !text(r.note(), 500, true))
                    || !preset(r.minComputeValue()) || !preset(r.maxComputeValue())
                    || r.minComputeValue().compareTo(r.maxComputeValue()) > 0 || r.chips().size() > MAX_TIER_ALIASES) invalid();
            int aliases = 0;
            for (AndroidSocChip chip : r.chips()) {
                if (chip == null || chip.socKey() == null || !chip.socKey().matches("[a-z0-9_-]{1,64}") || !socKeys.add(chip.socKey())
                        || !text(chip.name(), 128, false) || !text(chip.manufacturer(), 64, false) || chip.aliases().isEmpty()) invalid();
                for (String alias : chip.aliases()) {
                    String id = normalize(alias);
                    if (!text(alias, 128, false) || GENERIC_SOC_IDS.contains(id) || AMBIGUOUS_SOC_IDS.contains(id)) invalid();
                    // An identifier names exactly one chip, so a report can never resolve two ways.
                    if (!socAliases.add(id)) throw new BizException(422, "PHONE_CALIBRATION_SOC_ALIAS_DUPLICATE");
                }
                aliases += chip.aliases().size();
            }
            if (aliases > MAX_TIER_ALIASES) invalid();
        }
        if (androidPresetRemovals.socKeys().size() > 500 || androidPresetRemovals.aliases().size() > 2000
                || androidPresetRemovals.socKeys().stream().anyMatch(key -> !text(key, 64, false))
                || androidPresetRemovals.aliases().stream().anyMatch(alias -> !text(alias, 128, false))) invalid();
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
            if ((r.minOsVersion() != null && PhoneModelRisk.osVersion(r.minOsVersion()) == null)
                    || (r.maxOsMajor() != null && (r.maxOsMajor() < 1 || r.maxOsMajor() > 99
                        || (r.minOsVersion() != null && r.maxOsMajor() < PhoneModelRisk.osVersion(r.minOsVersion())[0])))
                    || r.scales().size() > 3 || r.scales().stream().distinct().count() != r.scales().size()
                    || r.scales().stream().anyMatch(scale -> scale == null || scale < 1 || scale > 3)
                    || r.screens().size() > 10 || r.screens().stream().anyMatch(screen -> PhoneModelRisk.screen(screen) == null)
                    // Both orientations of one size are the same screen.
                    || r.screens().stream().map(PhoneModelRisk::screen).distinct().count() != r.screens().size()) invalid();
            // A model or alias names exactly one rule, enabled or not, so a match is never ambiguous.
            for (String name : names(r)) if (!names.add(name)) throw new BizException(422, "PHONE_CALIBRATION_IOS_MODEL_DUPLICATE");
        }
    }

    public Match match(Hardware hardware) {
        validate();
        String platform = hardware == null ? "" : normalize(hardware.platform());
        if ("ios".equals(platform)) return matchIos(hardware.model());
        if ("android".equals(platform)) return matchAndroid(hardware.socIds());
        return pending("PLATFORM_UNSUPPORTED");
    }

    /**
     * Every reported identifier is resolved, so the answer never depends on their order: one chip
     * matches its tier, two different chips conflict even within one tier. Generic and known-shared
     * identifiers never decide; an unknown one does not block a verified identifier beside it.
     */
    private Match matchAndroid(List<String> socIds) {
        Set<String> reported = new java.util.LinkedHashSet<>();
        for (String raw : socIds) {
            String id = normalize(raw);
            if (!id.isEmpty() && raw.length() <= 128 && !GENERIC_SOC_IDS.contains(id)) reported.add(id);
            if (reported.size() >= MAX_SOC_IDS) break;
        }
        if (reported.isEmpty()) return pending("SOC_MISSING");
        Map<String, AndroidSocRule> tiers = new java.util.LinkedHashMap<>();
        Map<String, AndroidSocChip> chips = new java.util.LinkedHashMap<>();
        for (String id : reported) {
            if (AMBIGUOUS_SOC_IDS.contains(id)) continue;
            for (AndroidSocRule rule : androidSocRules) for (AndroidSocChip chip : rule.chips()) {
                if (chip.aliases().stream().map(PhoneCalibrationPolicy::normalize).anyMatch(id::equals)) {
                    chips.put(chip.socKey(), chip);
                    tiers.put(chip.socKey(), rule);
                }
            }
        }
        if (chips.size() > 1) return pending("SOC_CONFLICT");
        if (chips.isEmpty()) return pending("SOC_UNKNOWN");
        AndroidSocChip chip = chips.values().iterator().next();
        AndroidSocRule rule = tiers.get(chip.socKey());
        if (!rule.enabled()) return pending("SOC_DISABLED");
        return new Match("MATCHED", rule.id(), null, null, version, ANDROID_SOC_RANGE, chip.name(),
                rule.minComputeValue(), rule.maxComputeValue(), chip.socKey());
    }

    /** The Android chip with this stable key, for display; null when no tier holds it. */
    public AndroidSocChip socChip(String socKey) {
        if (socKey == null) return null;
        for (AndroidSocRule rule : androidSocRules) for (AndroidSocChip chip : rule.chips()) if (socKey.equals(chip.socKey())) return chip;
        return null;
    }

    /** The iPhone rule a match points to, for its device facts. */
    public IosModelRule iosRule(String id) {
        return id == null ? null : iosModelRules.stream().filter(rule -> id.equals(rule.id())).findFirst().orElse(null);
    }

    /**
     * The model identity of a report for the installation lock: an iPhone name and its identifier
     * resolve to the same rule through the current aliases; anything unmatched compares as written.
     */
    public String modelIdentity(String platform, String rawModel) {
        String os = normalize(platform), model = normalize(rawModel);
        if (model.isEmpty()) return null;
        if ("ios".equals(os)) {
            IosModelRule rule = iosModelRules.stream().filter(r -> names(r).contains(model)).findFirst().orElse(null);
            if (rule != null) return "ios:" + rule.id();
        }
        return os + ":" + model;
    }

    /** The rule's model name for an iPhone name or identifier this policy knows; otherwise the report itself. */
    public String displayModel(String platform, String rawModel) {
        if (rawModel == null) return null;
        String model = normalize(rawModel);
        if ("ios".equals(normalize(platform))) {
            IosModelRule rule = iosModelRules.stream().filter(r -> names(r).contains(model)).findFirst().orElse(null);
            if (rule != null) return rule.model();
        }
        return rawModel.trim();
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
    /**
     * A calibration result, or an activation receipt holding one under originalResult, as the App
     * may see it: the user's own value without the range it was drawn from, the risk flags or the
     * model lock, an Android result's chip and tier rule, the raw identifiers in the echoed signals,
     * and a held calibration's risk or chip reason generalised. Other values pass through.
     */
    public static Object appView(Object result) {
        if (!(result instanceof java.util.Map<?, ?> map)) return result;
        boolean android = ANDROID_SOC_RANGE.equals(map.get("calibrationMethod"))
                || map.get("signals") instanceof Map<?, ?> signals && "android".equals(signals.get("platform"));
        java.util.Map<String, Object> view = new java.util.LinkedHashMap<>();
        map.forEach((key, value) -> {
            String name = String.valueOf(key);
            if (SERVER_ONLY_RESULT_KEYS.contains(name) || android && ANDROID_SERVER_ONLY_KEYS.contains(name)) return;
            if ("signals".equals(name) && value instanceof Map<?, ?> signals) {
                java.util.Map<String, Object> echoed = new java.util.LinkedHashMap<>();
                signals.forEach((field, raw) -> { if (!SERVER_ONLY_SIGNAL_KEYS.contains(String.valueOf(field))) echoed.put(String.valueOf(field), raw); });
                view.put(name, echoed);
                return;
            }
            // Which model check held the calibration is not told to the user.
            Object shown = "pendingReason".equals(name) && value instanceof String reason
                    && (PhoneModelRisk.ENFORCEABLE.contains(reason) || reason.startsWith("SOC_")) ? PhoneModelRisk.APP_REASON : value;
            view.put(name, "originalResult".equals(name) ? appView(shown) : shown);
        });
        return view;
    }
    @SuppressWarnings("unchecked")
    public static java.util.Map<String, Object> appView(java.util.Map<String, Object> result) {
        return (java.util.Map<String, Object>) appView((Object) result);
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
