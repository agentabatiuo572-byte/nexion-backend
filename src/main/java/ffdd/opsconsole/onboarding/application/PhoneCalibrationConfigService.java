package ffdd.opsconsole.onboarding.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import ffdd.opsconsole.onboarding.mapper.OnboardingCalibrationMapper.TierRow;
import ffdd.opsconsole.shared.exception.BizException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Reuses the locked operator configuration row; A2 owns approval and its history. */
public final class PhoneCalibrationConfigService {
    public static final String KEY = "E.compute.phoneCalibration.policy";
    private static final ObjectMapper JSON = new ObjectMapper();
    private final PlatformConfigFacade config;
    private final Clock clock;
    public PhoneCalibrationConfigService(PlatformConfigFacade config, Clock clock) {
        this.config = config;
        this.clock = clock;
    }
    public record State(long revision, PhoneCalibrationPolicy current, PhoneCalibrationPolicy scheduled) { }
    /**
     * A null iosModelRules keeps the latest published iPhone rules. "rules" (the retired Android hardware
     * rules) is ignored, so an A2 ticket written before chip tiers still reads and replays as no Android rules.
     */
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties("rules")
    public record Proposal(long expectedRevision, long effectiveAt, List<java.math.BigDecimal> thresholds,
                           List<PhoneCalibrationPolicy.AndroidSocRule> androidSocRules,
                           List<PhoneCalibrationPolicy.IosModelRule> iosModelRules,
                           PhoneCalibrationPolicy.AndroidPresetRemovals androidPresetRemovals) {
        public Proposal(long expectedRevision, long effectiveAt, List<java.math.BigDecimal> thresholds,
                        List<PhoneCalibrationPolicy.AndroidSocRule> androidSocRules,
                        List<PhoneCalibrationPolicy.IosModelRule> iosModelRules) {
            this(expectedRevision, effectiveAt, thresholds, androidSocRules, iosModelRules, null);
        }
    }

    public State read() { return effective(parse(config.activeValue(KEY).orElse(null))); }
    public PhoneCalibrationPolicy activePolicy() { return read().current(); }

    public record Direction(long expectedRevision, long baselineVersion, long effectiveAt, boolean amplifies) { }

    /** Read-only contract shared by PC, A2 classification and replay; no client direction flag is trusted. */
    public Direction direction(Proposal proposal, List<TierRow> rewards) {
        State state = read();
        PhoneCalibrationPolicy next = candidate(proposal, state);
        PhoneCalibrationPolicy before = baseline(state, next);
        return new Direction(state.revision(), before == null ? 0 : before.version(), next.effectiveAt(),
                amplifies(state, next, rewards));
    }

    public boolean amplifies(State state, PhoneCalibrationPolicy next, List<TierRow> rewards) {
        if (rewards.size() != 5) throw new BizException(503, "ONBOARDING_CALIBRATION_CONFIG_UNAVAILABLE");
        for (int i = 0; i < rewards.size(); i++) {
            TierRow row = rewards.get(i);
            if (row.tier() == null || row.tier() != i + 1 || row.baseRateUsdt() == null || row.baseRateNex() == null
                    || row.baseRateUsdt().signum() <= 0 || row.baseRateNex().signum() <= 0
                    || i > 0 && (row.baseRateUsdt().compareTo(rewards.get(i - 1).baseRateUsdt()) < 0
                        || row.baseRateNex().compareTo(rewards.get(i - 1).baseRateNex()) < 0)) {
                throw new BizException(503, "ONBOARDING_CALIBRATION_CONFIG_UNAVAILABLE");
            }
        }
        PhoneCalibrationPolicy before = baseline(state, next);
        PhoneModelRisk.Settings risk = new PhoneModelRiskConfig(config, clock).read();
        Map<String, PhoneCalibrationPolicy.Match> oldMatches = matches(before), nextMatches = matches(next);
        Map<String, String> oldChips = new HashMap<>(), nextChips = new HashMap<>();
        if (before != null) for (var rule : before.androidSocRules()) for (var chip : rule.chips()) {
            for (String alias : chip.aliases()) oldChips.put(PhoneCalibrationPolicy.normalize(alias), chip.socKey());
        }
        for (var rule : next.androidSocRules()) for (var chip : rule.chips()) for (String alias : chip.aliases()) {
            nextChips.put(PhoneCalibrationPolicy.normalize(alias), chip.socKey());
        }
        // A removed alias is ignored by matchAndroid; beside another chip it can resolve an old SOC_CONFLICT.
        Set<String> removedChips = new HashSet<>();
        oldChips.forEach((alias, chip) -> { if (!nextChips.containsKey(alias)) removedChips.add(chip); });
        for (var rule : next.androidSocRules()) if (rule.enabled()) for (var chip : rule.chips()) {
            Set<String> previousChips = new HashSet<>(removedChips);
            for (String alias : chip.aliases()) {
                String previous = oldChips.get(PhoneCalibrationPolicy.normalize(alias));
                if (previous != null) previousChips.add(previous);
            }
            if (previousChips.size() > 1) return true;
        }
        Set<String> compared = new HashSet<>();
        for (var entry : nextMatches.entrySet()) {
            var afterMatch = entry.getValue();
            var beforeMatch = oldMatches.get(entry.getKey());
            if (beforeMatch == null) return true;
            String pair = afterMatch.method() + ":" + beforeMatch.ruleId() + ":" + afterMatch.ruleId();
            if (!compared.add(pair)) continue;
            if (PhoneCalibrationPolicy.IOS_MODEL_RANGE.equals(afterMatch.method())) {
                var oldRule = before.iosRule(beforeMatch.ruleId());
                var newRule = next.iosRule(afterMatch.ruleId());
                if (risk.osVersion() == PhoneModelRisk.Mode.ENFORCE
                        && (osMinimum(newRule.minOsVersion()) < osMinimum(oldRule.minOsVersion())
                            || (newRule.maxOsMajor() == null ? 99 : newRule.maxOsMajor())
                                > (oldRule.maxOsMajor() == null ? 99 : oldRule.maxOsMajor()))) return true;
                if (risk.screenScale() == PhoneModelRisk.Mode.ENFORCE && !oldRule.scales().isEmpty()
                        && (newRule.scales().isEmpty() || !oldRule.scales().containsAll(newRule.scales()))) return true;
            }
            // Retained values are not drawn again: compare their actual rewards at each new tier boundary.
            BigDecimal retainedMin = beforeMatch.minComputeValue().max(afterMatch.minComputeValue());
            BigDecimal retainedMax = beforeMatch.maxComputeValue().min(afterMatch.maxComputeValue());
            List<BigDecimal> retainedBoundaries = new ArrayList<>(next.thresholds());
            retainedBoundaries.add(retainedMin);
            for (BigDecimal boundary : retainedBoundaries) {
                BigDecimal value = boundary.setScale(2, RoundingMode.CEILING);
                if (value.compareTo(retainedMin) >= 0 && value.compareTo(retainedMax) <= 0
                        && rewardIncreases(rewards, before.tierFor(value), next.tierFor(value))) return true;
            }
            boolean allocationKeyChanged = PhoneCalibrationPolicy.ANDROID_SOC_RANGE.equals(afterMatch.method())
                    ? !beforeMatch.ruleId().equals(afterMatch.ruleId())
                    : !PhoneCalibrationPolicy.normalize(beforeMatch.model()).equals(PhoneCalibrationPolicy.normalize(afterMatch.model()));
            BigDecimal redrawnFrom = allocationKeyChanged || beforeMatch.minComputeValue().compareTo(afterMatch.minComputeValue()) < 0
                    ? beforeMatch.minComputeValue() : beforeMatch.maxComputeValue().compareTo(afterMatch.maxComputeValue()) > 0
                        ? beforeMatch.minComputeValue().max(afterMatch.maxComputeValue().add(new BigDecimal("0.01"))) : null;
            if (redrawnFrom != null && rewardIncreases(rewards, before.tierFor(redrawnFrom), next.tierFor(afterMatch.maxComputeValue()))) return true;
            long beforeCount = rangeCount(beforeMatch), afterCount = rangeCount(afterMatch);
            for (int i = 1; i < rewards.size(); i++) {
                TierRow lower = rewards.get(i - 1), upper = rewards.get(i);
                if (upper.baseRateUsdt().compareTo(lower.baseRateUsdt()) == 0
                        && upper.baseRateNex().compareTo(lower.baseRateNex()) == 0) continue;
                // draw() is uniform over inclusive 0.01 steps; tierFor() promotes at >= threshold.
                if (tailCount(afterMatch, next.thresholds().get(i - 1)) * beforeCount
                        > tailCount(beforeMatch, before.thresholds().get(i - 1)) * afterCount) return true;
            }
        }
        return false;
    }

    /** Validated policies have globally unique iOS names and Android aliases, exactly as match() requires. */
    private static Map<String, PhoneCalibrationPolicy.Match> matches(PhoneCalibrationPolicy policy) {
        Map<String, PhoneCalibrationPolicy.Match> result = new HashMap<>();
        if (policy == null) return result;
        for (var rule : policy.iosModelRules()) if (rule.enabled()) {
            var match = new PhoneCalibrationPolicy.Match("MATCHED", rule.id(), null, null, policy.version(),
                    PhoneCalibrationPolicy.IOS_MODEL_RANGE, rule.model(), rule.minComputeValue(), rule.maxComputeValue());
            result.put("ios:" + PhoneCalibrationPolicy.normalize(rule.model()), match);
            for (String alias : rule.aliases()) result.put("ios:" + PhoneCalibrationPolicy.normalize(alias), match);
        }
        for (var rule : policy.androidSocRules()) if (rule.enabled()) for (var chip : rule.chips()) {
            var match = new PhoneCalibrationPolicy.Match("MATCHED", rule.id(), null, null, policy.version(),
                    PhoneCalibrationPolicy.ANDROID_SOC_RANGE, chip.name(), rule.minComputeValue(), rule.maxComputeValue(), chip.socKey());
            for (String alias : chip.aliases()) result.put("android:" + PhoneCalibrationPolicy.normalize(alias), match);
        }
        return result;
    }

    private static PhoneCalibrationPolicy baseline(State state, PhoneCalibrationPolicy next) {
        return state.scheduled() != null && state.scheduled().effectiveAt() <= next.effectiveAt()
                ? state.scheduled() : state.current();
    }

    private static int osMinimum(String value) {
        int[] version = PhoneModelRisk.osVersion(value);
        return version == null ? 0 : version[0] * 100 + version[1];
    }

    private static long rangeCount(PhoneCalibrationPolicy.Match match) {
        return match.maxComputeValue().subtract(match.minComputeValue()).movePointRight(2).longValueExact() + 1;
    }

    private static boolean rewardIncreases(List<TierRow> rewards, int beforeTier, int nextTier) {
        TierRow before = rewards.get(beforeTier - 1), next = rewards.get(nextTier - 1);
        return next.baseRateUsdt().compareTo(before.baseRateUsdt()) > 0 || next.baseRateNex().compareTo(before.baseRateNex()) > 0;
    }

    private static long tailCount(PhoneCalibrationPolicy.Match match, BigDecimal threshold) {
        long first = Math.max(match.minComputeValue().movePointRight(2).longValueExact(),
                threshold.movePointRight(2).setScale(0, RoundingMode.CEILING).longValueExact());
        return Math.max(0, match.maxComputeValue().movePointRight(2).longValueExact() - first + 1);
    }

    public PhoneCalibrationPolicy candidate(Proposal proposal, State state) {
        if (proposal == null || proposal.expectedRevision() != state.revision()) {
            throw new BizException(409, "PHONE_CALIBRATION_VERSION_CONFLICT");
        }
        if (proposal.effectiveAt() < 0 || proposal.effectiveAt() > clock.millis() + 366L * 86400000) {
            throw new BizException(422, "PHONE_CALIBRATION_EFFECTIVE_TIME_INVALID");
        }
        PhoneCalibrationPolicy latest = state.scheduled() != null ? state.scheduled() : state.current();
        var iosModelRules = proposal.iosModelRules() != null ? proposal.iosModelRules()
                : latest == null ? List.<PhoneCalibrationPolicy.IosModelRule>of() : latest.iosModelRules();
        PhoneCalibrationPolicy policy = new PhoneCalibrationPolicy(state.revision() + 1,
                Math.max(clock.millis(), proposal.effectiveAt()), proposal.thresholds(), proposal.androidSocRules(),
                iosModelRules, proposal.androidPresetRemovals());
        policy.validate();
        return policy;
    }

    /** Must run in the caller's A2 replay transaction, not as a standalone public write. */
    public State publish(Proposal proposal) {
        config.insertAdminValueIfMissing(KEY, encode(new State(0, null, null)), "JSON", "e6_compute", "手机校准规则");
        State before = effective(parse(config.activeValueForUpdate(KEY)
                .orElseThrow(() -> new BizException(503, "PHONE_CALIBRATION_CONFIG_UNAVAILABLE"))));
        PhoneCalibrationPolicy next = candidate(proposal, before);
        // Do not silently discard another already-approved future publication.
        if (before.scheduled() != null) throw new BizException(409, "PHONE_CALIBRATION_PUBLICATION_PENDING");
        State after = next.effectiveAt() <= clock.millis()
                ? new State(next.version(), next, null) : new State(next.version(), before.current(), next);
        config.upsertAdminValue(KEY, encode(after), "JSON", "e6_compute", "手机校准规则 v" + next.version());
        State saved = read();
        if (!saved.equals(effective(after))) throw new BizException(503, "PHONE_CALIBRATION_READBACK_FAILED");
        return saved;
    }

    private State effective(State state) {
        return state.scheduled() != null && state.scheduled().effectiveAt() <= clock.millis()
                ? new State(state.revision(), state.scheduled(), null) : state;
    }
    private State parse(String value) {
        if (value == null) return new State(0, null, null);
        try {
            var tree = JSON.readTree(value);
            if (tree == null || !tree.isObject() || !tree.has("revision") || !tree.has("current") || !tree.has("scheduled")) {
                throw new IllegalArgumentException();
            }
            State state = JSON.readValue(value, State.class);
            if (state == null || state.revision() < 0) throw new IllegalArgumentException();
            if (state.current() != null) state.current().validate();
            if (state.scheduled() != null) state.scheduled().validate();
            long latest = Math.max(state.current() == null ? 0 : state.current().version(),
                    state.scheduled() == null ? 0 : state.scheduled().version());
            if (latest != state.revision() || (state.scheduled() != null && state.current() != null
                    && state.scheduled().version() <= state.current().version())) throw new IllegalArgumentException();
            return state;
        } catch (Exception invalid) {
            throw new BizException(503, "PHONE_CALIBRATION_CONFIG_UNAVAILABLE");
        }
    }
    private String encode(State value) {
        try {
            String json = JSON.writeValueAsString(value);
            // nx_config_item uses TEXT; reject oversize atomically instead of truncating rules.
            if (json.getBytes(StandardCharsets.UTF_8).length > 60000) throw new BizException(422, "PHONE_CALIBRATION_RULE_LIMIT");
            return json;
        } catch (BizException exception) { throw exception; }
        catch (Exception exception) { throw new BizException(503, "PHONE_CALIBRATION_CONFIG_UNAVAILABLE"); }
    }
}
