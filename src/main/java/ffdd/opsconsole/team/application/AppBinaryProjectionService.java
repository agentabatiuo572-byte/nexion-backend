package ffdd.opsconsole.team.application;

import ffdd.opsconsole.growth.facade.GrowthRhythmSnapshot;
import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import ffdd.opsconsole.shared.seed.OpsReadTimeSeedPolicy;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.security.UserAuthEnvironment;
import ffdd.opsconsole.team.mapper.BinaryCommissionSettlementMapper;
import ffdd.opsconsole.team.mapper.BinaryCommissionSettlementMapper.AppBinaryCommissionEventRow;
import ffdd.opsconsole.team.mapper.BinaryCommissionSettlementMapper.PaidOrderVolumeCandidate;
import ffdd.opsconsole.treasury.facade.TreasuryCoverageFacade;
import ffdd.opsconsole.treasury.facade.TreasuryCoverageSnapshot;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.Clock;
import java.time.ZoneOffset;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.Arrays;
import java.util.stream.Collectors;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Authenticated App projection for F3. Every value is derived from the same
 * paid-order, assignment, H1 and platform-config facts used by settlement.
 */
@Service
@RequiredArgsConstructor
public class AppBinaryProjectionService {
    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(6);
    private static final Pattern RUN_ID = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{7,95}$");

    private final BinaryCommissionSettlementMapper mapper;
    private final PlatformConfigFacade configFacade;
    private final OpsReadTimeSeedPolicy readTimeSeedPolicy;
    private final TreasuryCoverageFacade coverageFacade;
    private final Environment environment;
    private final Clock clock;

    private static LocalDateTime storageMidnight(LocalDate utcDate) {
        return utcDate.atStartOfDay(ZoneOffset.UTC)
                .withZoneSameInstant(ffdd.opsconsole.shared.config.DateTimeFormatConfig.BUSINESS_ZONE)
                .toLocalDateTime();
    }

    public Map<String, Object> snapshot(Long userId) {
        if (userId == null || userId <= 0) throw new IllegalArgumentException("F3_APP_USER_REQUIRED");
        Scope scope = scope(userId);
        LocalDate today = LocalDate.now(clock.withZone(ZoneOffset.UTC));
        LocalDateTime monthStart = storageMidnight(today.withDayOfMonth(1));
        LocalDateTime windowEnd = storageMidnight(today.plusDays(1));

        BigDecimal threshold = positiveMoney(config("team.ui.F.binary.threshold"));
        BigDecimal matchRate = rate(config("team.ui.F.binary.matchRate"));
        boolean paused = bool(config("team.ui.F.binary.paused"));
        boolean spilloverEnabled = enabled(config("team.ui.F.binary.spillover"));
        String settlePeriod = period(config("team.ui.F.binary.settlePeriod"));
        String residualPolicy = residual(config("team.ui.F.binary.residualPolicy"));
        String gvReset = config("team.ui.F.binary.gvResetCron");

        BigDecimal trackA = ZERO;
        BigDecimal trackB = ZERO;
        LocalDateTime volumeStart = "转结".equals(residualPolicy)
                ? LocalDate.of(1970, 1, 1).atStartOfDay()
                : monthStart;
        List<PaidOrderVolumeCandidate> candidates = mapper.listPaidOrderCandidates(userId, volumeStart, windowEnd);
        if (candidates == null) throw new IllegalStateException("F3_APP_VOLUME_SOURCE_UNAVAILABLE");
        Set<String> pairResetConsumed = "每次对碰清零".equals(residualPolicy)
                ? new HashSet<>(safeList(mapper.listPairResetConsumedOrderNos(userId)))
                : Set.of();
        for (PaidOrderVolumeCandidate candidate : candidates) {
            if (candidate == null || candidate.mappedRootCount() != 1
                    || candidate.amountUsdt() == null || candidate.amountUsdt().signum() <= 0) {
                throw new IllegalStateException("F3_APP_VOLUME_SOURCE_AMBIGUOUS");
            }
            if (pairResetConsumed.contains(candidate.orderNo())) continue;
            if ("A".equals(candidate.leg())) trackA = trackA.add(candidate.amountUsdt());
            else if ("B".equals(candidate.leg())) trackB = trackB.add(candidate.amountUsdt());
            else throw new IllegalStateException("F3_APP_VOLUME_SOURCE_AMBIGUOUS");
        }
        trackA = money(trackA);
        trackB = money(trackB);
        if ("转结".equals(residualPolicy)) {
            BigDecimal consumed = money(mapper.consumedMatchedBefore(userId, today.plusDays(1)));
            trackA = trackA.subtract(consumed).max(BigDecimal.ZERO).setScale(6, RoundingMode.DOWN);
            trackB = trackB.subtract(consumed).max(BigDecimal.ZERO).setScale(6, RoundingMode.DOWN);
        }
        GrowthRhythmSnapshot rhythm = GrowthRhythmSnapshot.from(configFacade, readTimeSeedPolicy);
        if (!rhythm.reliable() || rhythm.binaryDailyCap() == null
                || rhythm.binaryDailyCap().signum() <= 0) {
            throw new IllegalStateException("F3_APP_H1_UNAVAILABLE");
        }

        int directMembers = mapper.countDirectMembers(userId);
        int trackAMembers = mapper.countAssignmentsByLeg(userId, "A");
        int trackBMembers = mapper.countAssignmentsByLeg(userId, "B");
        TreasuryCoverageSnapshot coverage = coverageFacade.snapshot();
        String blockedReason = blockedReason(
                today, paused, settlePeriod, threshold, trackA, trackB,
                directMembers, trackAMembers, trackBMembers,
                mapper.countReversalRequiredVolumes(userId), coverage);
        int days = periodDays(today, settlePeriod);
        BigDecimal periodCap = money(rhythm.binaryDailyCap().multiply(BigDecimal.valueOf(days)));
        BigDecimal estimate = money(trackA.min(trackB).multiply(matchRate)).min(periodCap);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("source", "server");
        result.put("serverCanonical", true);
        result.put("sourceEnvironment", scope.sourceEnvironment());
        result.put("runId", scope.runId());
        result.put("asOfDate", today);
        result.put("trackA", trackA);
        result.put("trackB", trackB);
        result.put("trackAMembers", trackAMembers);
        result.put("trackBMembers", trackBMembers);
        result.put("autoPlacedMembers", mapper.countAutoPlacedMembers(userId));
        result.put("matchRate", matchRate);
        result.put("threshold", threshold);
        result.put("dailyCap", money(rhythm.binaryDailyCap()));
        result.put("periodCap", periodCap);
        result.put("estimatedAmountUsdt", blockedReason.isBlank() ? estimate : ZERO);
        result.put("settlePeriod", canonicalPeriod(settlePeriod));
        result.put("residualPolicy", canonicalResidual(residualPolicy));
        result.put("spilloverEnabled", spilloverEnabled);
        result.put("gvReset", gvReset);
        result.put("paused", paused);
        result.put("blockedReason", blockedReason);
        result.put("recentMatches", recentMatches(userId));
        return result;
    }

    private List<Map<String, Object>> recentMatches(Long userId) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (AppBinaryCommissionEventRow row : mapper.listRecentBinaryCommissionEvents(userId, 20)) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", row.id());
            item.put("amountUsdt", money(row.amountUsdt()));
            item.put("status", String.valueOf(row.status()).toUpperCase(Locale.ROOT));
            item.put("createdAt", row.createdAt());
            item.put("unlockAt", row.unlockAt());
            result.add(item);
        }
        return result;
    }

    private String blockedReason(
            LocalDate today,
            boolean paused,
            String settlePeriod,
            BigDecimal threshold,
            BigDecimal trackA,
            BigDecimal trackB,
            int directMembers,
            int trackAMembers,
            int trackBMembers,
            int reversalRequired,
            TreasuryCoverageSnapshot coverage) {
        if (paused) return "F3_BINARY_PAUSED";
        if (reversalRequired > 0) return "BINARY_REFUND_REVERSAL_REQUIRED";
        if (coverage == null || !coverage.reliable()
                || coverage.coverageRatio() == null || coverage.redlinePct() == null) {
            return "B1_COVERAGE_UNRELIABLE";
        }
        if (coverage.coverageRatio().compareTo(coverage.redlinePct()) < 0) {
            return "COVERAGE_BELOW_REDLINE";
        }
        if (directMembers < 2 || trackAMembers + trackBMembers != directMembers
                || trackAMembers < 1 || trackBMembers < 1) {
            return "BINARY_LEG_ASSIGNMENT_INCOMPLETE";
        }
        if (trackA.compareTo(threshold) < 0 || trackB.compareTo(threshold) < 0) {
            return "BINARY_THRESHOLD_NOT_MET";
        }
        if (!due(today, settlePeriod)) return "F3_SETTLEMENT_NOT_DUE";
        return "";
    }

    private boolean due(LocalDate date, String settlePeriod) {
        return switch (settlePeriod) {
            case "每日" -> true;
            case "每周" -> date.getDayOfWeek() == DayOfWeek.SUNDAY;
            case "每月" -> date.getDayOfMonth() == date.lengthOfMonth();
            default -> false;
        };
    }

    private int periodDays(LocalDate date, String settlePeriod) {
        return switch (settlePeriod) {
            case "每日" -> 1;
            case "每周" -> 7;
            case "每月" -> date.lengthOfMonth();
            default -> throw new IllegalStateException("F3_APP_SETTLE_PERIOD_INVALID");
        };
    }

    private String canonicalPeriod(String value) {
        return switch (value) {
            case "每日" -> "daily";
            case "每周" -> "weekly";
            case "每月" -> "monthly";
            default -> throw new IllegalStateException("F3_APP_SETTLE_PERIOD_INVALID");
        };
    }

    private String canonicalResidual(String value) {
        return switch (value) {
            case "每月清零" -> "monthlyClear";
            case "每次对碰清零" -> "perPairClear";
            case "转结" -> "carryForward";
            default -> throw new IllegalStateException("F3_APP_RESIDUAL_POLICY_INVALID");
        };
    }

    private String config(String key) {
        return configFacade.activeValue(key)
                .map(String::trim)
                .filter(StringUtils::hasText)
                .orElseThrow(() -> new IllegalStateException("F3_APP_CONFIG_MISSING:" + key));
    }

    private BigDecimal positiveMoney(String raw) {
        try {
            BigDecimal value = new BigDecimal(raw.replace("$", "").replace(",", "").trim());
            if (value.signum() <= 0) throw new NumberFormatException();
            return money(value);
        } catch (RuntimeException ex) {
            throw new IllegalStateException("F3_APP_MONEY_CONFIG_INVALID");
        }
    }

    private BigDecimal rate(String raw) {
        try {
            String value = raw.trim();
            boolean percent = value.endsWith("%");
            if (percent) value = value.substring(0, value.length() - 1).trim();
            BigDecimal parsed = new BigDecimal(value);
            if (percent || parsed.compareTo(BigDecimal.ONE) > 0) {
                parsed = parsed.divide(new BigDecimal("100"), 12, RoundingMode.HALF_UP);
            }
            if (parsed.signum() <= 0 || parsed.compareTo(BigDecimal.ONE) > 0) {
                throw new NumberFormatException();
            }
            return parsed.stripTrailingZeros();
        } catch (RuntimeException ex) {
            throw new IllegalStateException("F3_APP_MATCH_RATE_INVALID");
        }
    }

    private boolean bool(String raw) {
        return switch (raw.toLowerCase(Locale.ROOT)) {
            case "true", "1", "on" -> true;
            case "false", "0", "off" -> false;
            default -> throw new IllegalStateException("F3_APP_PAUSED_INVALID");
        };
    }

    private boolean enabled(String raw) {
        return switch (raw.toLowerCase(Locale.ROOT)) {
            case "true", "1", "on", "已启用" -> true;
            case "false", "0", "off", "已关闭" -> false;
            default -> throw new IllegalStateException("F3_APP_SPILLOVER_INVALID");
        };
    }

    private String period(String raw) {
        if (!List.of("每日", "每周", "每月").contains(raw)) {
            throw new IllegalStateException("F3_APP_SETTLE_PERIOD_INVALID");
        }
        return raw;
    }

    private String residual(String raw) {
        if (!List.of("每月清零", "每次对碰清零", "转结").contains(raw)) {
            throw new IllegalStateException("F3_APP_RESIDUAL_POLICY_INVALID");
        }
        return raw;
    }

    private BigDecimal money(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value).setScale(6, RoundingMode.DOWN);
    }

    private <T> List<T> safeList(List<T> values) {
        return values == null ? List.of() : values;
    }

    private Scope scope(Long userId) {
        Integer sandbox = mapper.userSandbox(userId);
        if (sandbox == null) throw new BizException(403, "F3_APP_USER_REQUIRED");
        Set<String> profiles = environment == null ? Set.of() : Arrays.stream(environment.getActiveProfiles())
                .map(value -> value == null ? "" : value.trim().toLowerCase(Locale.ROOT))
                .filter(value -> !value.isBlank()).collect(Collectors.toSet());
        boolean development = false;
        boolean isolated = profiles.size() == 1 && "test".equals(profiles.iterator().next());
        boolean production = profiles.isEmpty() || profiles.size() == 1 && Set.of("dev", "prod").contains(profiles.iterator().next());
        if (!development && !isolated && !production) throw new BizException(503, "F3_APP_PROFILE_INVALID");
        if (isolated) {
            if (!Integer.valueOf(1).equals(sandbox)) throw new BizException(403, "F3_APP_SANDBOX_USER_REQUIRED");
            String runId = environment.getProperty("NEXION_ACCEPTANCE_RUN_ID", "").trim();
            if (!RUN_ID.matcher(runId).matches()) throw new BizException(503, "F3_APP_RUN_ID_REQUIRED");
            return new Scope(UserAuthEnvironment.SANDBOX.name(), runId);
        }
        if (!Integer.valueOf(0).equals(sandbox)) throw new BizException(403, "F3_APP_PRODUCTION_USER_REQUIRED");
        return new Scope(UserAuthEnvironment.PRODUCTION.name(), null);
    }

    private record Scope(String sourceEnvironment, String runId) { }
}
