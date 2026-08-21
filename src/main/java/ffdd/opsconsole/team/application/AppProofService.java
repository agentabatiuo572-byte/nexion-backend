package ffdd.opsconsole.team.application;

import ffdd.opsconsole.growth.application.AppReferralRewardService;
import ffdd.opsconsole.growth.domain.AppReferralRewardView;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.team.mapper.AppProofMapper;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class AppProofService {
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final long MIN_PERCENTILE_SAMPLE = 5L;
    private final AppProofMapper mapper;
    private final AppReferralRewardService referralRewardService;
    private final AppTeamNetworkService teamNetworkService;
    private final AppProofSandboxFixtureService sandboxFixtureService;
    private final Clock clock;

    @Autowired
    public AppProofService(AppProofMapper mapper, AppReferralRewardService referralRewardService,
                           AppTeamNetworkService teamNetworkService,
                           AppProofSandboxFixtureService sandboxFixtureService) {
        this(mapper, referralRewardService, teamNetworkService, sandboxFixtureService, Clock.systemUTC());
    }

    public AppProofService(AppProofMapper mapper, AppReferralRewardService referralRewardService,
                           AppTeamNetworkService teamNetworkService, Clock clock) {
        this(mapper, referralRewardService, teamNetworkService, null, clock);
    }

    public AppProofService(AppProofMapper mapper, AppReferralRewardService referralRewardService,
                           AppTeamNetworkService teamNetworkService) {
        this(mapper, referralRewardService, teamNetworkService, null, Clock.systemUTC());
    }

    public AppProofService(AppProofMapper mapper, AppReferralRewardService referralRewardService,
                           AppTeamNetworkService teamNetworkService,
                           AppProofSandboxFixtureService sandboxFixtureService, Clock clock) {
        this.mapper = mapper;
        this.referralRewardService = referralRewardService;
        this.teamNetworkService = teamNetworkService;
        this.sandboxFixtureService = sandboxFixtureService;
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    public ApiResult<Map<String, Object>> snapshot(Long userId) {
        if (userId == null || userId <= 0) throw new BizException(403, "USER_AUTH_REQUIRED");
        AppProofMapper.UserRow user = mapper.user(userId);
        if (user == null || user.joinedAt() == null || user.referralCode() == null || user.referralCode().isBlank()) {
            return ApiResult.fail(503, "PROOF_NOT_READY");
        }
        ApiResult<AppReferralRewardView> referralResult = referralRewardService.snapshot(userId, 20);
        if (referralResult == null || referralResult.getCode() != 0 || referralResult.getData() == null) {
            return ApiResult.fail(503, "PROOF_NOT_READY");
        }
        AppReferralRewardView referral = referralResult.getData();
        Map<String, Object> network = null;
        try {
            ApiResult<Map<String, Object>> networkResult = teamNetworkService.snapshot(userId);
            if (networkResult != null && networkResult.getCode() == 0) network = networkResult.getData();
        } catch (RuntimeException ignored) {
            // Team-network projection is a secondary Proof stat. Its outage must
            // not hide the server-owned earnings/streak certificate.
        }
        String sourceEnvironment = referral.sourceEnvironment();
        String runId = referral.runId() == null ? "" : referral.runId();
        AppProofSandboxFixtureService.Fixture sandboxFixture = "SANDBOX".equals(sourceEnvironment)
                && sandboxFixtureService != null && !runId.isBlank()
                ? sandboxFixtureService.get(runId, userId) : null;
        Instant serverTime = Instant.now(clock);
        LocalDate asOfDate = serverTime.atZone(BUSINESS_ZONE).toLocalDate();
        long activeDays = Math.max(0, Duration.between(user.joinedAt().toInstant(ZoneOffset.UTC), serverTime).toDays());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("source", "server"); result.put("sourceEnvironment", sourceEnvironment); result.put("runId", runId);
        result.put("serverCanonical", true);
        result.put("generatedAt", serverTime.toString());
        result.put("serverTime", serverTime.toString());
        result.put("asOf", asOfDate.toString());
        result.put("joinedAt", user.joinedAt().atZone(ZoneOffset.UTC).toInstant().toString());
        // nx_user_device has no run dimension. Never expose canonical device
        // rows as sandbox proof until a run-scoped device projection exists.
        result.put("activeDays", activeDays);
        result.put("onlineDevices", "SANDBOX".equals(sourceEnvironment)
                ? null : Math.max(0, mapper.onlineDevices(userId)));
        BigDecimal earnings = "SANDBOX".equals(sourceEnvironment)
                ? (sandboxFixture != null && sandboxFixture.earningsTotalUsdt() != null
                    ? sandboxFixture.earningsTotalUsdt()
                    : (runId.isBlank() ? null : mapper.sandboxEarningsTotalUsdt(runId, userId)))
                : mapper.earningsTotalUsdt(userId);
        if (earnings != null && earnings.signum() < 0) earnings = null;
        result.put("earningsTotalUsdt", earnings);
        AppProofMapper.StreakRow streak = "SANDBOX".equals(sourceEnvironment)
                ? sandboxStreak(sandboxFixture) : mapper.streak(userId);
        Long currentStreak = currentStreak(streak, asOfDate);
        Long longestStreak = longestStreak(streak, currentStreak);
        Long exposedCurrentStreak = "SANDBOX".equals(sourceEnvironment) && sandboxFixture == null ? null : currentStreak;
        Long exposedLongestStreak = "SANDBOX".equals(sourceEnvironment) && sandboxFixture == null ? null : longestStreak;
        result.put("currentStreak", exposedCurrentStreak);
        result.put("longestStreak", exposedLongestStreak);
        AppProofMapper.PercentileRow population = "SANDBOX".equals(sourceEnvironment)
                ? sandboxPopulation(sandboxFixture)
                : earnings == null ? null : mapper.earningsPopulation(userId, earnings);
        result.put("topPercentile", earnings == null ? null : percentile(population));
        result.put("provenance", provenance(sourceEnvironment, runId));
        result.put("referralCode", referral.referralCode());
        result.put("referral", Map.of("invitedCount", Math.max(0, referral.invitedCount()),
                "lifetimeInviterNex", nonNegative(referral.lifetimeInviterNex())));
        Map<String, Object> team = new LinkedHashMap<>();
        team.put("totalMembers", network == null ? null : number(network.get("totalMembers")));
        team.put("activeMembers", network == null ? null : number(network.get("activeMembers")));
        result.put("team", team);
        Map<String, String> availability = new LinkedHashMap<>();
        if (earnings == null) availability.put("earnings", "UNAVAILABLE");
        if (network == null) availability.put("team", "UNAVAILABLE");
        if (result.get("onlineDevices") == null) availability.put("devices", "UNAVAILABLE");
        if (exposedCurrentStreak == null || exposedLongestStreak == null) availability.put("streak", "UNAVAILABLE");
        if (result.get("topPercentile") == null) availability.put("percentile", "UNAVAILABLE");
        availability.put("status", availability.isEmpty() ? "READY" : (earnings == null && network == null ? "EMPTY" : "PARTIAL"));
        result.put("availability", availability);
        return ApiResult.ok(result);
    }

    private Long currentStreak(AppProofMapper.StreakRow row, LocalDate asOf) {
        if (row == null) return null;
        if (row.lastCheckInDate() == null || row.lastCheckInDate().isAfter(asOf)) return 0L;
        // A missed business date breaks the current streak. The persisted
        // longest streak remains historical and is never reduced by a gap.
        long gap = Duration.between(row.lastCheckInDate().atStartOfDay(), asOf.atStartOfDay()).toDays();
        return gap <= 1 ? Math.max(0, integer(row.currentStreak())) : 0L;
    }

    private Long longestStreak(AppProofMapper.StreakRow row, Long current) {
        return row == null ? null : Math.max(current == null ? 0L : current, Math.max(0, integer(row.longestStreak())));
    }

    private AppProofMapper.StreakRow sandboxStreak(AppProofSandboxFixtureService.Fixture fixture) {
        return fixture == null ? null : new AppProofMapper.StreakRow(
                fixture.currentStreak(), fixture.longestStreak(), fixture.lastCheckInDate());
    }

    private AppProofMapper.PercentileRow sandboxPopulation(AppProofSandboxFixtureService.Fixture fixture) {
        return fixture == null ? null : new AppProofMapper.PercentileRow(
                fixture.higherCount(), fixture.populationCount());
    }

    private BigDecimal percentile(AppProofMapper.PercentileRow row) {
        if (row == null || row.populationCount() == null || row.higherCount() == null
                || row.populationCount() < MIN_PERCENTILE_SAMPLE || row.higherCount() < 0
                || row.higherCount() >= row.populationCount()) return null;
        long population = row.populationCount();
        long higher = Math.max(0L, Math.min(row.higherCount(), population - 1));
        // "Top X%" is the share strictly ahead of this user. Equal earnings
        // therefore receive the same value (competition/tie-aware rank). The
        // leader is rendered as Top 1%, never as a misleading Top 0%.
        BigDecimal value = BigDecimal.valueOf(higher).multiply(BigDecimal.valueOf(100L))
                .divide(BigDecimal.valueOf(population), 1, RoundingMode.CEILING);
        return value.signum() == 0 ? BigDecimal.ONE.setScale(1) : value;
    }

    private Map<String, Object> provenance(String environment, String runId) {
        return Map.of(
                "source", "server",
                "environment", environment,
                "runId", runId,
                "timeZone", BUSINESS_ZONE.getId(),
                "streakRule", "H5 business date; a missing date breaks current streak; longest is persisted maximum",
                "percentileRule", "USDT earnings against active production users; strict-greater tie-aware top share; unavailable below 5 samples");
    }

    private int integer(Integer value) { return value == null ? 0 : value; }

    private long number(Object value) { return value instanceof Number n ? Math.max(0, n.longValue()) : 0; }
    private BigDecimal nonNegative(BigDecimal value) { return value == null || value.signum() < 0 ? BigDecimal.ZERO : value; }
}
