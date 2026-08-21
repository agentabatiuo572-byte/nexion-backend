package ffdd.opsconsole.market.application;

import ffdd.opsconsole.finance.application.FundsSandboxProfileGuard;
import ffdd.opsconsole.market.mapper.AppMarketSandboxMapper;
import ffdd.opsconsole.shared.exception.BizException;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.core.env.Environment;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/** Explicit, volatile Genesis holder fixture rail for local acceptance only. */
@Service
@RequiredArgsConstructor
public class AppGenesisSandboxFixtureService {
    private final AppMarketSandboxMapper mapper;
    private final Environment environment;
    private static final Map<String, FixtureSet> FIXTURES = new ConcurrentHashMap<>();

    public void replace(String runId, Long actorUserId, List<HolderSpec> requested) {
        requireScope(runId, actorUserId);
        if (requested == null || requested.isEmpty() || requested.size() > 100) {
            throw new BizException(422, "GENESIS_SANDBOX_FIXTURE_INVALID");
        }
        Map<Long, Integer> counts = new LinkedHashMap<>();
        for (HolderSpec holder : requested) {
            if (holder == null || holder.userId() == null || holder.userId() <= 0
                    || holder.holdings() == null || holder.holdings() < 0 || holder.holdings() > 100
                    || counts.put(holder.userId(), holder.holdings()) != null) {
                throw new BizException(422, "GENESIS_SANDBOX_FIXTURE_INVALID");
            }
            if (!Integer.valueOf(1).equals(mapper.userSandbox(holder.userId()))) {
                throw new BizException(403, "GENESIS_SANDBOX_USER_REQUIRED");
            }
        }
        if (!counts.containsKey(actorUserId)) {
            throw new BizException(403, "GENESIS_SANDBOX_FIXTURE_ACTOR_REQUIRED");
        }
        FIXTURES.put(runId, new FixtureSet(Map.copyOf(counts), LocalDateTime.now()));
    }

    public void clear(String runId, Long actorUserId) {
        requireScope(runId, actorUserId);
        FIXTURES.remove(runId);
    }

    public static boolean hasFixture(String runId) { return FIXTURES.containsKey(runId); }

    public static List<AppMarketSandboxMapper.HoldingView> holdings(String runId, Long userId) {
        FixtureSet fixture = FIXTURES.get(runId);
        Integer count = fixture == null ? null : fixture.holderCounts().get(userId);
        if (count == null || count <= 0) return List.of();
        List<AppMarketSandboxMapper.HoldingView> result = new ArrayList<>(count);
        String prefix = fixturePrefix(runId);
        for (int index = 1; index <= count; index++) {
            String holdingNo = prefix + "-" + userId + "-" + index;
            String orderNo = prefix + "-ORDER-" + userId + "-" + index;
            result.add(new AppMarketSandboxMapper.HoldingView((long) index, runId, holdingNo, orderNo,
                    userId, "GENESIS-SANDBOX", BigDecimal.ZERO.setScale(6), "ACTIVE", null,
                    fixture.createdAt(), null));
        }
        return List.copyOf(result);
    }

    public static long totalHoldings(String runId) {
        FixtureSet fixture = FIXTURES.get(runId);
        return fixture == null ? 0 : fixture.holderCounts().values().stream().mapToLong(Integer::longValue).sum();
    }

    public static Integer priorityRank(String runId, Long userId) {
        FixtureSet fixture = FIXTURES.get(runId);
        if (fixture == null || !fixture.holderCounts().containsKey(userId)
                || fixture.holderCounts().get(userId) <= 0) return null;
        int mine = fixture.holderCounts().get(userId);
        long higher = fixture.holderCounts().values().stream().filter(value -> value > mine).count();
        return Math.toIntExact(higher + 1);
    }

    public static long activeHolderCount(String runId) {
        FixtureSet fixture = FIXTURES.get(runId);
        return fixture == null ? 0 : fixture.holderCounts().values().stream().filter(value -> value > 0).count();
    }

    private void requireScope(String runId, Long actorUserId) {
        String current = environment == null ? "" : environment.getProperty("NEXION_ACCEPTANCE_RUN_ID", "").trim();
        String[] profiles = environment == null ? new String[0] : environment.getActiveProfiles();
        if (!FundsSandboxProfileGuard.isStrictIsolatedProfile(profiles)
                || profiles.length != 1 || !"dev".equalsIgnoreCase(profiles[0])
                || !StringUtils.hasText(runId) || !runId.equals(current)
                || !runId.matches("[A-Za-z0-9][A-Za-z0-9._-]{7,95}") || actorUserId == null || actorUserId <= 0) {
            throw new BizException(403, "GENESIS_SANDBOX_FIXTURE_SCOPE_INVALID");
        }
        if (!Integer.valueOf(1).equals(mapper.userSandbox(actorUserId))) {
            throw new BizException(403, "GENESIS_SANDBOX_USER_REQUIRED");
        }
    }

    private static String fixturePrefix(String runId) {
        return "G4-FIX-" + Integer.toUnsignedString(runId.hashCode(), 36).toUpperCase();
    }

    private record FixtureSet(Map<Long, Integer> holderCounts, LocalDateTime createdAt) { }
    public record HolderSpec(Long userId, Integer holdings) { }
}
