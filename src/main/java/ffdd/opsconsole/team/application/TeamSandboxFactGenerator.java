package ffdd.opsconsole.team.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Deterministic server-owned facts for the explicit acceptance sandbox.
 *
 * <p>The sandbox has no production projection to read from. Facts are therefore
 * derived from the authenticated account and the server's acceptance RunID,
 * never from browser state. A digest (rather than random/time state) makes
 * refreshes and process restarts return the same snapshot while changing either
 * scope component produces a different namespace.</p>
 */
public final class TeamSandboxFactGenerator {
    private static final Instant EPOCH = Instant.parse("2026-01-01T00:00:00Z");
    private static final int ROW_COUNT = 12;

    private TeamSandboxFactGenerator() { }

    static Map<String, Object> leaderboard(String runId, long userId, String period, long page, long pageSize) {
        Scope scope = scope(runId, userId, period);
        List<Map<String, Object>> all = new ArrayList<>();
        for (int i = 0; i < ROW_COUNT; i++) {
            int rank = i + 1;
            BigDecimal earned = amount(scope.digest(), "leaderboard:" + rank, 25, 275);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("rank", rank);
            row.put("handle", "S" + scope.digest().substring(i, i + 6) + "***");
            row.put("flag", "🌐"); row.put("cc", "--");
            row.put("directs", 1 + number(scope.digest(), "directs:" + rank, 0, 6));
            row.put("teamSize", 4 + number(scope.digest(), "team:" + rank, 0, 36));
            row.put("earnedUSDT", earned); row.put("delta", 0);
            row.put("vRank", number(scope.digest(), "rank:" + rank, 0, 6));
            row.put("hasDevice", number(scope.digest(), "device:" + rank, 0, 2) == 1);
            all.add(row);
        }
        int from = pageStart(page, pageSize, all.size());
        int to = (int) Math.min(all.size(), (long) from + pageSize);
        Map<String, Object> result = provenance(scope);
        result.put("period", period); result.put("rows", List.copyOf(all.subList(from, to)));
        result.put("myRank", 1 + number(scope.digest(), "my-rank", 0, ROW_COUNT));
        result.put("gapToNext", BigDecimal.ZERO); result.put("poolUsd", BigDecimal.ZERO);
        result.put("topN", all.size()); result.put("page", page); result.put("pageSize", pageSize);
        result.put("totalRows", all.size()); result.put("generatedAt", scope.generatedAt());
        return result;
    }

    static Map<String, Object> commissions(String runId, long userId) {
        Scope scope = scope(runId, userId, "commissions");
        List<Map<String, Object>> events = new ArrayList<>();
        for (int i = 1; i <= 3; i++) {
            long ts = scope.timestamp("commission:" + i);
            Map<String, Object> event = new LinkedHashMap<>();
            event.put("id", "SB-CM-" + scope.digest().substring(i, i + 10));
            event.put("kind", i == 3 ? "binary" : "unilevel");
            event.put("sourceUserName", "Sandbox " + scope.digest().substring(i + 3, i + 7));
            event.put("layer", i); event.put("orderId", "SB-ORD-" + i);
            event.put("orderAmountUSD", amount(scope.digest(), "order:" + i, 50, 250));
            event.put("amountUSDT", amount(scope.digest(), "usdt:" + i, 1, 20));
            event.put("amountNEX", amount(scope.digest(), "nex:" + i, 0, 100));
            event.put("ts", ts); event.put("unlockAt", ts + (i * 86400_000L));
            event.put("status", "SIMULATED");
            event.put("settlementState", "SIMULATED"); event.put("withdrawable", false); events.add(event);
        }
        Map<String, Object> result = provenance(scope); result.put("events", events);
        result.put("generatedAt", scope.generatedAt()); return result;
    }

    static Map<String, Object> unilevel(String runId, long userId, String period) {
        Scope scope = scope(runId, userId, period);
        List<Map<String, Object>> events = new ArrayList<>();
        BigDecimal directUsdt = BigDecimal.ZERO, directNex = BigDecimal.ZERO;
        BigDecimal extendedUsdt = BigDecimal.ZERO, extendedNex = BigDecimal.ZERO;
        for (int i = 1; i <= 4; i++) {
            int layer = i <= 2 ? 1 : 2;
            BigDecimal usdt = amount(scope.digest(), "unilevel-usdt:" + i, 1, 15);
            BigDecimal nex = amount(scope.digest(), "unilevel-nex:" + i, 0, 90);
            if (layer == 1) { directUsdt = directUsdt.add(usdt); directNex = directNex.add(nex); }
            else { extendedUsdt = extendedUsdt.add(usdt); extendedNex = extendedNex.add(nex); }
            long ts = scope.timestamp("unilevel:" + i);
            Map<String, Object> event = new LinkedHashMap<>();
            event.put("id", "SB-UL-" + scope.digest().substring(i, i + 10)); event.put("source", "network");
            event.put("sourceUserName", "Sandbox " + scope.digest().substring(i + 4, i + 8));
            event.put("cycle", "SB-" + scope.digest().substring(0, 6)); event.put("layer", layer);
            event.put("orderId", "SB-ORD-" + i); event.put("orderAmountUSD", amount(scope.digest(), "ul-order:" + i, 50, 250));
            event.put("amountUSDT", usdt); event.put("amountNEX", nex); event.put("currency", "USDT");
            event.put("ts", ts); event.put("unlockAt", ts + 86400_000L); event.put("status", "SIMULATED");
            event.put("settlementState", "SIMULATED"); event.put("withdrawable", false); events.add(event);
        }
        Map<String, Object> result = provenance(scope); result.put("period", period); result.put("events", events);
        result.put("split", Map.of("direct", Map.of("amountUSDT", directUsdt, "amountNEX", directNex, "count", 2),
                "extended", Map.of("amountUSDT", extendedUsdt, "amountNEX", extendedNex, "count", 2)));
        result.put("generatedAt", scope.generatedAt()); return result;
    }

    static Map<String, Object> leadershipPool(String runId, long userId) {
        Scope scope = scope(runId, userId, "leadership-pool");
        List<Map<String, Object>> distribution = new ArrayList<>();
        int totalVotes = 0;
        for (int rank = 0; rank <= 12; rank++) {
            int people = number(scope.digest(), "people:" + rank, rank == 0 ? 1 : 0, 5);
            int votes = 1 << Math.min(rank, 8); totalVotes += people * votes;
            distribution.add(Map.of("vRank", rank, "people", people, "votes", votes));
        }
        int myRank = number(scope.digest(), "pool-rank", 0, 12);
        int myVotes = 1 << Math.min(myRank, 8);
        BigDecimal pool = amount(scope.digest(), "pool", 100, 500);
        BigDecimal share = totalVotes == 0 ? BigDecimal.ZERO : BigDecimal.valueOf(myVotes)
                .divide(BigDecimal.valueOf(totalVotes), 12, RoundingMode.DOWN);
        Map<String, Object> result = provenance(scope);
        result.put("currentWeekPoolUSDT", pool); result.put("myRank", myRank); result.put("myVotes", myVotes);
        result.put("totalVotes", totalVotes); result.put("mySharePct", share);
        result.put("projectedPayoutUSDT", pool.multiply(share)); result.put("distribution", distribution);
        result.put("history", List.of()); result.put("nextPayoutAt", EPOCH.plus(365, ChronoUnit.DAYS).toString());
        return result;
    }

    static Map<String, Object> network(String runId, long userId) {
        Scope scope = scope(runId, userId, "network");
        List<Map<String, Object>> members = new ArrayList<>();
        BigDecimal monthVolume = BigDecimal.ZERO;
        int direct = 0;
        int active = 0;
        for (int i = 1; i <= 14; i++) {
            int layer = 1 + (i - 1) % 4;
            BigDecimal volume = amount(scope.digest(), "network-volume:" + i, 25, 240);
            monthVolume = monthVolume.add(volume);
            if (layer == 1) direct++;
            String status = i % 7 == 0 ? "OFFLINE" : "ACTIVE";
            if ("ACTIVE".equals(status)) active++;
            Map<String, Object> member = new LinkedHashMap<>();
            member.put("id", "SB-NET-" + scope.digest().substring(i, i + 10));
            member.put("name", "Sandbox member " + i);
            member.put("avatarUrl", null);
            member.put("vRank", number(scope.digest(), "network-rank:" + i, 0, 5));
            member.put("layer", layer);
            member.put("leg", i % 2 == 0 ? "B" : "A");
            member.put("joinedAt", EPOCH.plus(number(scope.digest(), "network-joined:" + i, 1, 60), ChronoUnit.DAYS)
                    .atOffset(ZoneOffset.UTC).toInstant().toString());
            member.put("monthVolumeUsdt", volume);
            member.put("lifetimeVolumeUsdt", null);
            member.put("status", status);
            member.put("region", i % 2 == 0 ? "Sandbox-A" : "Sandbox-B");
            members.add(member);
        }
        Map<String, Object> result = provenance(scope);
        result.put("totalMembers", members.size());
        result.put("directMembers", direct);
        result.put("activeMembers", active);
        result.put("monthVolumeUsdt", monthVolume);
        result.put("lifetimeVolumeUsdt", null);
        result.put("members", members);
        result.put("generatedAt", scope.generatedAt());
        return result;
    }

    public static Map<String, Object> currentRank(String runId, long userId) {
        Scope scope = scope(runId, userId, "rank");
        int rank = number(scope.digest(), "current-rank", 0, 12);
        Map<String, Object> progress = new LinkedHashMap<>();
        progress.put("selfBuyUSD", amount(scope.digest(), "self-buy", 0, 2000));
        progress.put("directRefs", number(scope.digest(), "direct-refs", 0, 12));
        progress.put("teamVolumeUSD", amount(scope.digest(), "team-volume", 0, 10000));
        Map<String, Object> counts = new LinkedHashMap<>();
        for (int i = 0; i <= Math.min(rank, 4); i++) counts.put(String.valueOf(i), number(scope.digest(), "downline:" + i, 0, 4));
        progress.put("vDownlineCounts", counts);
        Map<String, Object> result = provenance(scope); result.put("source", "server sandbox VRank facts");
        result.put("rankCode", "V" + rank); result.put("progress", progress); return result;
    }

    private static Map<String, Object> provenance(Scope scope) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("source", "server"); result.put("sourceEnvironment", "SANDBOX"); result.put("runId", scope.runId());
        result.put("serverCanonical", true); result.put("factStatus", "SIMULATED");
        result.put("withdrawable", false); result.put("payoutStatus", "NON_WITHDRAWABLE");
        return result;
    }

    private static int pageStart(long page, long pageSize, int total) {
        if (page <= 1) return 0;
        if (page > Long.MAX_VALUE / pageSize) return total;
        long offset = (page - 1L) * pageSize;
        return offset >= total ? total : (int) offset;
    }

    private static BigDecimal amount(String digest, String key, int min, int max) {
        return BigDecimal.valueOf(min + number(digest, key, 0, Math.max(1, max - min))).setScale(2, RoundingMode.DOWN);
    }

    private static int number(String digest, String key, int min, int maxExclusive) {
        if (maxExclusive <= min) return min;
        int value = Integer.parseUnsignedInt(digest(digest + ":" + key).substring(0, 8), 16);
        return min + Math.floorMod(value, maxExclusive - min);
    }

    private static String digest(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }

    private static Scope scope(String runId, long userId, String subject) {
        return new Scope(runId, userId, digest(runId + ":" + userId + ":" + subject));
    }

    private record Scope(String runId, long userId, String digest) {
        String generatedAt() { return EPOCH.plus(number(digest, "generated-at", 0, 86_400), ChronoUnit.SECONDS).toString(); }
        long timestamp(String subject) { return Instant.parse(generatedAt()).plus(number(digest, subject, 0, 14), ChronoUnit.DAYS).toEpochMilli(); }
    }
}
