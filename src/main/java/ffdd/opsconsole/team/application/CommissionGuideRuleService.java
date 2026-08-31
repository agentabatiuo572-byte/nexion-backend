package ffdd.opsconsole.team.application;

import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.security.UserAuthEnvironment;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * Public, configuration-only commission facts for the App "how it works" UI.
 * This service deliberately does not query accounts, commission events, or settlement readiness.
 */
@Service
@RequiredArgsConstructor
public class CommissionGuideRuleService {
    private static final Pattern RUN_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{7,95}");
    private static final Pattern DEPTH_GATE_LAYER = Pattern.compile("L?([1-7])", Pattern.CASE_INSENSITIVE);
    private static final Pattern DEPTH_GATE_RANK = Pattern.compile("V([0-9]|1[0-2])", Pattern.CASE_INSENSITIVE);
    private static final Pattern PHASE = Pattern.compile("P[1-6]", Pattern.CASE_INSENSITIVE);
    private static final BigDecimal ONE_HUNDRED = new BigDecimal("100");

    static final String COOLING_DAYS_KEY = "commission/cooling-days";
    static final String DEPTH_GATE_LAYER_KEY = "team.ui.F.unilevel.depthGate";
    static final String DEPTH_GATE_RANK_KEY = "team.ui.F.unilevel.depthGateRank";
    static final String MERGE_EXIT_MAX_PCT_KEY = "team.ui.F.unilevel.mergeExitMaxPct";

    private final PlatformConfigFacade configFacade;
    private final LeadershipPoolConfigGuard leadershipConfigGuard;

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Map<String, Object> guide(Environment environment) {
        UserAuthEnvironment audience = UserAuthEnvironment.resolve(environment)
                .orElseThrow(() -> new BizException(503, "COMMISSION_GUIDE_CONFIG_PROFILE_INVALID"));
        String runId = sandboxRunId(environment, audience);

        Map<String, Object> guide = new LinkedHashMap<>();
        guide.put("source", "server");
        guide.put("serverCanonical", true);
        guide.put("sourceEnvironment", audience == UserAuthEnvironment.SANDBOX ? "SANDBOX" : "PRODUCTION");
        guide.put("runId", runId);
        guide.put("coolingDays", coolingDays());
        guide.put("network", network());
        guide.put("binary", binary());
        guide.put("leadership", leadership());
        // No peer/genesis event generator is deployed. Other categories' generators and
        // F5 labels alone are not proof that peer/genesis generation exists.
        guide.put("capabilities", Map.of("peer", false, "genesis", false));
        return guide;
    }

    private String sandboxRunId(Environment environment, UserAuthEnvironment audience) {
        if (audience != UserAuthEnvironment.SANDBOX) return null;
        String runId = environment.getProperty("NEXION_ACCEPTANCE_RUN_ID", "").trim();
        if (!RUN_ID.matcher(runId).matches()) {
            throw new BizException(503, "COMMISSION_GUIDE_SANDBOX_RUN_ID_REQUIRED");
        }
        return runId;
    }

    private Integer coolingDays() {
        return active(COOLING_DAYS_KEY).flatMap(this::nonNegativeInteger).orElse(null);
    }

    private Map<String, Object> network() {
        Integer depthGateLayer = active(DEPTH_GATE_LAYER_KEY).flatMap(this::depthGateLayer).orElse(null);
        Integer depthGateRank = active(DEPTH_GATE_RANK_KEY).flatMap(this::depthGateRank).orElse(null);
        BigDecimal exitCapRate = active(MERGE_EXIT_MAX_PCT_KEY).flatMap(this::percentPointsAsRate).orElse(null);
        Map<String, Object> network = new LinkedHashMap<>();
        network.put("depthGateLayer", depthGateLayer);
        network.put("depthGateRank", depthGateRank);
        network.put("exitCapRate", exitCapRate);
        return network;
    }

    private Map<String, Object> binary() {
        BigDecimal threshold = active(BinarySettlementPolicyProvider.THRESHOLD_KEY).flatMap(this::positiveMoney).orElse(null);
        BigDecimal matchRate = active(BinarySettlementPolicyProvider.MATCH_RATE_KEY).flatMap(this::positiveRate).orElse(null);
        Boolean paused = active(BinarySettlementPolicyProvider.PAUSED_KEY).flatMap(this::strictBoolean).orElse(null);
        boolean spilloverValid = active(BinarySettlementPolicyProvider.SPILLOVER_KEY).flatMap(this::strictEnabled).isPresent();
        String settlePeriod = active(BinarySettlementPolicyProvider.SETTLE_PERIOD_KEY).flatMap(this::settlePeriod).orElse(null);
        String residualPolicy = active(BinarySettlementPolicyProvider.RESIDUAL_POLICY_KEY).flatMap(this::residualPolicy).orElse(null);
        boolean gvResetConfigured = active(BinarySettlementPolicyProvider.GV_RESET_KEY).filter(StringUtils::hasText).isPresent();
        BigDecimal dailyCap = currentMonthBinaryCap();
        if (threshold == null || matchRate == null || paused == null || !spilloverValid
                || settlePeriod == null || residualPolicy == null || !gvResetConfigured || dailyCap == null) {
            return null;
        }
        return Map.of(
                "threshold", threshold,
                "matchRate", matchRate,
                "dailyCap", dailyCap,
                "settlePeriod", settlePeriod,
                "residualPolicy", residualPolicy,
                "paused", paused);
    }

    private BigDecimal currentMonthBinaryCap() {
        Integer totalMonths = active(BinarySettlementPolicyProvider.H1_TOTAL_MONTHS_KEY)
                .flatMap(this::positiveInteger).orElse(null);
        Integer currentMonth = active(BinarySettlementPolicyProvider.H1_CURRENT_MONTH_KEY)
                .flatMap(this::positiveInteger).orElse(null);
        boolean phaseValid = active(BinarySettlementPolicyProvider.H1_PHASE_KEY)
                .map(value -> PHASE.matcher(value.trim()).matches()).orElse(false);
        if (totalMonths == null || currentMonth == null || currentMonth > totalMonths || !phaseValid) return null;
        return active("growth.phase.month." + currentMonth + ".binaryDailyCap").flatMap(this::positiveMoney).orElse(null);
    }

    private Map<String, Object> leadership() {
        try {
            LeadershipPoolConfigGuard.SettlementConfig config = leadershipConfigGuard.requireValid();
            return Map.of(
                    "rate", config.injectRate(),
                    "minRank", config.unlockRank(),
                    "monthlyCap", config.monthlyCap());
        } catch (LeadershipPoolConfigGuard.ConfigUnavailableException unavailable) {
            return null;
        }
    }

    private Optional<String> active(String key) {
        return configFacade.activeValue(key).map(String::trim).filter(StringUtils::hasText);
    }

    private Optional<Integer> nonNegativeInteger(String raw) {
        try {
            int value = Integer.parseInt(raw.trim());
            return value >= 0 ? Optional.of(value) : Optional.empty();
        } catch (NumberFormatException ignored) {
            return Optional.empty();
        }
    }

    private Optional<Integer> positiveInteger(String raw) {
        try {
            int value = new BigDecimal(raw.trim()).intValueExact();
            return value > 0 ? Optional.of(value) : Optional.empty();
        } catch (RuntimeException invalid) { return Optional.empty(); }
    }

    private Optional<Integer> depthGateLayer(String raw) {
        Matcher matcher = DEPTH_GATE_LAYER.matcher(raw.trim());
        return matcher.matches() ? Optional.of(Integer.parseInt(matcher.group(1))) : Optional.empty();
    }

    private Optional<Integer> depthGateRank(String raw) {
        Matcher matcher = DEPTH_GATE_RANK.matcher(raw.trim());
        return matcher.matches() ? Optional.of(Integer.parseInt(matcher.group(1))) : Optional.empty();
    }

    private Optional<BigDecimal> percentPointsAsRate(String raw) {
        try {
            String value = raw.trim();
            if (value.endsWith("%")) value = value.substring(0, value.length() - 1).trim();
            BigDecimal percentPoints = new BigDecimal(value);
            if (percentPoints.signum() < 0 || percentPoints.compareTo(ONE_HUNDRED) > 0) return Optional.empty();
            return Optional.of(percentPoints.divide(ONE_HUNDRED, 12, RoundingMode.HALF_UP).stripTrailingZeros());
        } catch (RuntimeException ignored) {
            return Optional.empty();
        }
    }

    private Optional<BigDecimal> positiveMoney(String raw) {
        try {
            BigDecimal value = new BigDecimal(raw.replace("$", "").replace(",", "").trim());
            // Match F3's exact six-decimal validation; do not round an invalid threshold/cap.
            value.setScale(6, RoundingMode.UNNECESSARY);
            return value.signum() > 0 ? Optional.of(value) : Optional.empty();
        } catch (RuntimeException ignored) {
            return Optional.empty();
        }
    }

    private Optional<BigDecimal> positiveRate(String raw) {
        try {
            String value = raw.trim();
            boolean percent = value.endsWith("%");
            if (percent) value = value.substring(0, value.length() - 1).trim();
            BigDecimal rate = new BigDecimal(value);
            if (percent || rate.compareTo(BigDecimal.ONE) > 0) rate = rate.divide(ONE_HUNDRED, 12, RoundingMode.HALF_UP);
            return rate.signum() > 0 && rate.compareTo(BigDecimal.ONE) <= 0
                    ? Optional.of(rate.stripTrailingZeros()) : Optional.empty();
        } catch (RuntimeException ignored) {
            return Optional.empty();
        }
    }

    private Optional<Boolean> strictBoolean(String raw) {
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "true", "1", "on" -> Optional.of(true);
            case "false", "0", "off" -> Optional.of(false);
            default -> Optional.empty();
        };
    }

    private Optional<Boolean> strictEnabled(String raw) {
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "true", "1", "on", "已启用" -> Optional.of(true);
            case "false", "0", "off", "已关闭" -> Optional.of(false);
            default -> Optional.empty();
        };
    }

    private Optional<String> settlePeriod(String raw) {
        return switch (raw.trim()) {
            case "每日" -> Optional.of("daily");
            case "每周" -> Optional.of("weekly");
            case "每月" -> Optional.of("monthly");
            default -> Optional.empty();
        };
    }

    private Optional<String> residualPolicy(String raw) {
        return switch (raw.trim()) {
            case "每月清零" -> Optional.of("monthlyClear");
            case "每次对碰清零" -> Optional.of("perPairClear");
            case "转结" -> Optional.of("carryForward");
            default -> Optional.empty();
        };
    }
}
