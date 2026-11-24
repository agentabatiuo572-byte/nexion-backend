package ffdd.opsconsole.growth.application;

import ffdd.opsconsole.growth.mapper.AppGrowthWheelSandboxMapper;
import ffdd.opsconsole.growth.mapper.AppGrowthWheelSandboxMapper.SandboxCommand;
import ffdd.opsconsole.growth.mapper.AppGrowthWheelSandboxMapper.SandboxMilestone;
import ffdd.opsconsole.growth.mapper.AppGrowthWheelSandboxMapper.SandboxSpin;
import ffdd.opsconsole.growth.mapper.AppGrowthWheelSandboxMapper.SandboxTicket;
import ffdd.opsconsole.growth.mapper.AppGrowthWheelSandboxMapper.SandboxTier;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.exception.BizException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * Local-only wheel implementation. Every fact is keyed by (runId,userId) and
 * is written to a sandbox table; no canonical wallet, release ledger, audit,
 * or outbox collaborator is reachable from this service.
 */
@Service
@RequiredArgsConstructor
public class AppGrowthWheelSandboxService {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final BigDecimal ONE_HUNDRED = new BigDecimal("100.0000");
    private static final Set<String> REWARD_KINDS = Set.of("uvt", "usdt", "coupon");
    private static final Set<String> MILESTONE_REWARD_TYPES = Set.of("UVT", "USDT", "SPIN", "BADGE");
    private static final Clock CLOCK = Clock.systemUTC();

    private final AppGrowthWheelSandboxMapper mapper;
    private final WheelSandboxProfile profile;

    public boolean enabled() {
        return profile.mode() == WheelSandboxProfile.Mode.SANDBOX;
    }

    public boolean unknownProfile() {
        return profile.mode() == WheelSandboxProfile.Mode.UNKNOWN;
    }

    /**
     * Quest progress is kept in the same explicitly fenced sandbox namespace
     * as the wheel. Active weekly definitions are mirrored read-only from the
     * PC-managed mission configuration; nx_user_mission is never read or written.
     */
    @Transactional(rollbackFor = Exception.class)
    public ApiResult<Map<String, Object>> questState(Long userId) {
        WheelSandboxProfile.Scope scope = scope(userId);
        ensureQuestScope(scope);
        List<AppGrowthWheelSandboxMapper.SandboxQuest> rows = mapper.listQuests(scope.runId(), userId);
        if (rows == null) throw new BizException(503, "QUEST_SANDBOX_STATE_UNAVAILABLE");
        List<Map<String, Object>> quests = rows.stream().map(row -> linked(
                "questCode", row.questCode(), "name", row.questName(), "layer", row.layer(),
                "rewardNex", row.rewardNex(), "status", row.missionStatus())).toList();
        return ApiResult.ok(linked(
                "quests", quests, "promoBanner", Map.of(), "questBonusMultiplier", BigDecimal.ONE,
                "rhythmMonth", 0, "serverCanonical", true, "source", "mock",
                "sourceEnvironment", "SANDBOX", "runId", scope.runId()));
    }

    /** Records the share proof in the existing run/user-scoped sandbox quest row. */
    @Transactional(rollbackFor = Exception.class)
    public ApiResult<Map<String, Object>> recordShareEvent(
            Long userId, String requestedRunId, String eventId, String channel,
            String surface, String idempotencyKey) {
        WheelSandboxProfile.Scope scope = scope(userId);
        if (!StringUtils.hasText(requestedRunId)
                || !scope.runId().equals(requestedRunId.trim())) {
            throw new BizException(409, "SHARE_EVENT_SCOPE_MISMATCH");
        }
        String event = reference(eventId, "SHARE_EVENT_ID_INVALID");
        reference(channel, "SHARE_CHANNEL_INVALID");
        reference(surface, "SHARE_SURFACE_INVALID");
        reference(idempotencyKey, "SHARE_IDEMPOTENCY_KEY_REQUIRED");
        ensureQuestScope(scope);
        AppGrowthWheelSandboxMapper.SandboxQuest row = mapper.lockQuest(
                scope.runId(), userId, "invite_friend");
        if (row == null) throw new BizException(409, "QUEST_NOT_CONFIGURED");
        String status = text(row.missionStatus(), "PENDING").toUpperCase(Locale.ROOT);
        if (!"PENDING".equals(status)) {
            if (event.equals(row.claimIdempotencyKey())) {
                return shareResponse(scope, event, status, true);
            }
            throw new BizException(429, "SHARE_EVENT_RATE_LIMITED");
        }
        if (mapper.completeShareQuest(scope.runId(), userId, "invite_friend", event) != 1) {
            throw new BizException(409, "SHARE_EVENT_CONFLICT");
        }
        return shareResponse(scope, event, "COMPLETED", false);
    }

    private ApiResult<Map<String, Object>> shareResponse(
            WheelSandboxProfile.Scope scope, String eventId, String status, boolean replay) {
        return ApiResult.ok(linked("eventId", eventId, "questCode", "invite_friend",
                "status", status, "replay", replay, "serverCanonical", true,
                "source", "mock", "sourceEnvironment", "SANDBOX", "runId", scope.runId()));
    }

    @Transactional(rollbackFor = Exception.class)
    public ApiResult<Map<String, Object>> claimQuest(Long userId, String questCode, String idempotencyKey) {
        WheelSandboxProfile.Scope scope = scope(userId);
        String code = reference(questCode, "QUEST_CODE_REQUIRED");
        String key = reference(idempotencyKey, "IDEMPOTENCY_KEY_REQUIRED");
        ensureQuestScope(scope);
        AppGrowthWheelSandboxMapper.SandboxQuest row = mapper.lockQuest(scope.runId(), userId, code);
        if (row == null) return ApiResult.fail(409, "QUEST_NOT_CLAIMABLE");
        String status = text(row.missionStatus(), "PENDING").toUpperCase(Locale.ROOT);
        if ("CLAIMED".equals(status)) {
            return ApiResult.ok(linked("questId", code, "rewardNex", row.rewardNex(), "status", status,
                    "replay", true, "serverCanonical", true, "source", "mock",
                    "sourceEnvironment", "SANDBOX", "runId", scope.runId()));
        }
        if (!Set.of("COMPLETED", "CLAIMABLE").contains(status)) {
            return ApiResult.fail(409, "QUEST_NOT_CLAIMABLE");
        }
        if (mapper.claimQuest(scope.runId(), userId, code, key) != 1) {
            throw new BizException(409, "QUEST_SANDBOX_CLAIM_CONFLICT");
        }
        return ApiResult.ok(linked("questId", code, "rewardNex", row.rewardNex(), "status", "CLAIMED",
                "replay", false, "serverCanonical", true, "source", "mock",
                "sourceEnvironment", "SANDBOX", "runId", scope.runId()));
    }

    /** Called by the canonical event projector after a sandbox profile save. */
    @Transactional(rollbackFor = Exception.class)
    public QuestCompletionResult completeQuest(Long userId, String questCode) {
        WheelSandboxProfile.Scope scope = scope(userId);
        String code = reference(questCode, "QUEST_CODE_REQUIRED");
        ensureQuestScope(scope);
        AppGrowthWheelSandboxMapper.SandboxQuest row = mapper.lockQuest(scope.runId(), userId, code);
        if (row == null) throw new BizException(409, "QUEST_NOT_CONFIGURED");
        String status = text(row.missionStatus(), "PENDING").toUpperCase(Locale.ROOT);
        if (Set.of("COMPLETED", "CLAIMABLE", "CLAIMED").contains(status)) {
            return new QuestCompletionResult(code, status, true, scope.runId());
        }
        if (mapper.completeQuest(scope.runId(), userId, code) != 1) {
            throw new BizException(409, "QUEST_SANDBOX_COMPLETION_CONFLICT");
        }
        return new QuestCompletionResult(code, "COMPLETED", false, scope.runId());
    }

    @Transactional(rollbackFor = Exception.class)
    public ApiResult<Map<String, Object>> state(Long userId, String eventCode) {
        WheelSandboxProfile.Scope scope = scope(userId);
        String code = reference(eventCode, "EVENT_CODE_REQUIRED");
        ensureScope(scope);
        LocalDate date = today();
        boolean free = mapper.countDailySpin(scope.runId(), userId, code, date) == 0;
        List<SandboxTier> tiers = mapper.listTiers(scope.runId(), userId);
        validateTiers(tiers);
        List<Map<String, Object>> segments = java.util.stream.IntStream.range(0, tiers.size())
                .mapToObj(i -> publicSegment(tiers.get(i), i)).toList();
        return ApiResult.ok(linked(
                "eventCode", code,
                "eventId", "SANDBOX-" + code,
                "serverDate", date.toString(),
                "nextResetAtUtc", date.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().toString(),
                "freeAvailable", free,
                "bonusTickets", mapper.countAvailableTickets(scope.runId(), userId),
                "availableSpins", (free ? 1 : 0) + mapper.countAvailableTickets(scope.runId(), userId),
                "segments", segments,
                "history", mapper.listHistory(scope.runId(), userId, code, 20).stream().map(this::publicHistory).toList(),
                "source", "mock",
                "sourceEnvironment", "SANDBOX",
                "runId", scope.runId()));
    }

    /** Points surface companion: it exposes only sandbox ticket facts. */
    @Transactional(rollbackFor = Exception.class)
    public ApiResult<Map<String, Object>> pointState(Long userId) {
        WheelSandboxProfile.Scope scope = scope(userId);
        ensureScope(scope);
        LocalDate today = ffdd.opsconsole.shared.config.BusinessCalendar.growthTeam()
                .dateAt(java.time.Instant.now());
        Map<String, Object> streak = new LinkedHashMap<>();
        Map<String, Object> raw = mapper.canonicalStreakState(userId, today);
        if (raw != null) streak.putAll(raw);
        streak.putIfAbsent("currentStreak", 0);
        streak.putIfAbsent("longestStreak", 0);
        streak.putIfAbsent("streakSavers", 0);
        Object checked = streak.get("checkedInToday");
        streak.put("checkedInToday", checked instanceof Boolean value ? value
                : checked instanceof Number number && number.intValue() != 0);
        streak.putIfAbsent("lastCheckInDate", null);
        List<Long> claimedIds = mapper.claimedMilestoneIds(scope.runId(), userId);
        Set<Long> claimed = new LinkedHashSet<>(claimedIds == null ? List.of() : claimedIds);
        List<Map<String, Object>> rules = mapper.dailyMilestoneRules(userId);
        List<Map<String, Object>> milestones = (rules == null ? List.<Map<String, Object>>of() : rules).stream()
                .map(row -> milestoneView(row, claimed)).toList();
        return ApiResult.ok(linked(
                "rewardAsset", "UVT",
                "serverDate", today.toString(),
                "nextResetAtUtc", ffdd.opsconsole.shared.config.BusinessCalendar.growthTeam()
                        .startOf(today.plusDays(1)).toString(),
                "streak", streak,
                "dailyMilestones", milestones, "earningMilestones", List.of(), "rules", List.of(),
                "powerUps", List.of(), "topStreakers", List.of(),
                "bonusTickets", mapper.countAvailableTickets(scope.runId(), userId),
                "source", "mock", "serverCanonical", true,
                "sourceEnvironment", "SANDBOX", "runId", scope.runId()));
    }

    private Map<String, Object> milestoneView(Map<String, Object> row, Set<Long> claimed) {
        Long milestoneId = ((Number) row.get("milestoneId")).longValue();
        int day = ((Number) row.get("milestoneDay")).intValue();
        int current = ((Number) row.get("currentStreak")).intValue();
        String rewardType = row.get("rewardType") == null ? "" : String.valueOf(row.get("rewardType")).trim().toUpperCase(Locale.ROOT);
        String status = claimed.contains(milestoneId) ? "CLAIMED"
                : current >= day ? "CLAIMABLE" : "LOCKED";
        return linked("milestoneId", milestoneId, "milestoneDay", day,
                "rewardType", rewardType, "rewardAmount", row.get("rewardAmount"),
                "badgeCode", row.get("badgeCode"), "status", status);
    }

    @Transactional(rollbackFor = Exception.class)
    public ApiResult<Map<String, Object>> spin(Long userId, String eventCode, String idempotencyKey) {
        WheelSandboxProfile.Scope scope = scope(userId);
        String code = reference(eventCode, "EVENT_CODE_REQUIRED");
        String key = key(idempotencyKey);
        ensureScope(scope);
        if (mapper.lockScope(scope.runId(), userId) == null) {
            throw new BizException(409, "WHEEL_SANDBOX_SCOPE_UNAVAILABLE");
        }
        LocalDate date = today();
        String requestHash = sha256(code + "|" + date);
        SandboxCommand existing = mapper.lockCommand(scope.runId(), userId, "WHEEL_SPIN", key);
        if (existing != null) {
            if (!requestHash.equals(existing.spinHash())) throw new BizException(409, "WHEEL_SANDBOX_IDEMPOTENCY_CONFLICT");
            if (!StringUtils.hasText(existing.spinNo())) throw new BizException(409, "WHEEL_SANDBOX_REPLAY_UNAVAILABLE");
            SandboxSpin replay = mapper.findSpin(scope.runId(), userId, existing.spinNo());
            if (replay == null) throw new BizException(409, "WHEEL_SANDBOX_REPLAY_UNAVAILABLE");
            return spinResponse(scope, code, replay, true);
        }

        String sourceType;
        String sourceId;
        SandboxTicket ticket = null;
        if (mapper.countDailySpin(scope.runId(), userId, code, date) == 0) {
            sourceType = "DAILY";
            sourceId = date.toString();
        } else {
            ticket = mapper.lockAvailableTicket(scope.runId(), userId);
            if (ticket == null) return ApiResult.fail(409, "WHEEL_DAILY_LIMIT_REACHED");
            sourceType = "BONUS";
            sourceId = ticket.ticketId();
        }
        List<SandboxTier> tiers = mapper.lockTiers(scope.runId(), userId);
        validateTiers(tiers);
        SandboxTier selected = draw(tiers);
        // Sandbox rewards are ledger-only. The guard is still scoped and read
        // so a test can exercise guard changes without touching production.
        mapper.lockGuardValue(scope.runId(), userId, "kill");
        String spinNo = "SBX-SPIN-" + UUID.randomUUID().toString().replace("-", "").toUpperCase(Locale.ROOT);
        if (mapper.insertCommand(scope.runId(), userId, "WHEEL_SPIN", key, requestHash, spinNo) != 1) {
            SandboxCommand winner = mapper.lockCommand(scope.runId(), userId, "WHEEL_SPIN", key);
            if (winner == null || !requestHash.equals(winner.spinHash())) {
                throw new BizException(409, "WHEEL_SANDBOX_IDEMPOTENCY_CONFLICT");
            }
            SandboxSpin replay = mapper.findSpin(scope.runId(), userId, winner.spinNo());
            if (replay == null) throw new BizException(409, "WHEEL_SANDBOX_REPLAY_UNAVAILABLE");
            return spinResponse(scope, code, replay, true);
        }
        if (mapper.insertSpin(scope.runId(), userId, spinNo, code, date, sourceType, sourceId,
                selected, false, "NONE") != 1) throw new BizException(409, "WHEEL_SANDBOX_SPIN_CONFLICT");
        if (ticket != null && mapper.consumeTicket(scope.runId(), userId, ticket.ticketId(), code, date) != 1) {
            throw new BizException(409, "WHEEL_SANDBOX_TICKET_CONFLICT");
        }
        creditLedger(scope, spinNo, selected.rewardKind(), selected.rewardAmount());
        SandboxSpin spin = mapper.findSpin(scope.runId(), userId, spinNo);
        if (spin == null) throw new BizException(409, "WHEEL_SANDBOX_SPIN_UNAVAILABLE");
        return spinResponse(scope, code, spin, false);
    }

    /** Sandbox milestone claims use the real H5 rule/streak and write only scoped sandbox facts. */
    @Transactional(rollbackFor = Exception.class)
    public ApiResult<Map<String, Object>> claimMilestone(Long userId, Long milestoneId, String idempotencyKey) {
        WheelSandboxProfile.Scope scope = scope(userId);
        if (milestoneId == null || milestoneId <= 0) return ApiResult.fail(422, "MILESTONE_ID_REQUIRED");
        String key = key(idempotencyKey);
        ensureScope(scope);
        if (mapper.lockScope(scope.runId(), userId) == null) {
            throw new BizException(409, "WHEEL_SANDBOX_SCOPE_UNAVAILABLE");
        }
        String sourcePrefix = "MILESTONE:" + milestoneId + ":";
        String hash = sha256(String.valueOf(milestoneId));
        SandboxCommand existing = mapper.lockCommand(scope.runId(), userId, "DAILY_MILESTONE_CLAIM", key);
        if (existing != null) {
            if (!hash.equals(existing.spinHash())) throw new BizException(409, "WHEEL_SANDBOX_IDEMPOTENCY_CONFLICT");
            return replayMilestone(scope, milestoneId, existing);
        }
        if (milestoneClaimCount(scope, milestoneId) > 0) {
            return ApiResult.fail(409, "DAILY_MILESTONE_NOT_CLAIMABLE");
        }
        SandboxMilestone milestone = mapper.lockClaimableMilestone(userId, milestoneId);
        if (milestone == null) return ApiResult.fail(409, "DAILY_MILESTONE_NOT_CLAIMABLE");
        String guardKey = milestoneKey(milestoneId);
        if (mapper.lockCommand(scope.runId(), userId, "DAILY_MILESTONE_STATE", guardKey) != null) {
            return ApiResult.fail(409, "DAILY_MILESTONE_NOT_CLAIMABLE");
        }
        String rewardType = normalizeMilestoneRewardType(milestone.rewardType());
        BigDecimal amount = milestone.rewardAmount() == null ? BigDecimal.ZERO : milestone.rewardAmount();
        int tickets = 0;
        if ("UVT".equals(rewardType) || "USDT".equals(rewardType)) {
            if (amount == null || amount.signum() <= 0) throw new BizException(409, "REWARD_AMOUNT_INVALID");
        } else if ("SPIN".equals(rewardType)) {
            if (amount == null || amount.stripTrailingZeros().scale() > 0 || amount.signum() <= 0) {
                throw new BizException(409, "REWARD_AMOUNT_INVALID");
            }
            tickets = amount.intValueExact();
        } else if (!StringUtils.hasText(milestone.badgeCode())) {
            throw new BizException(409, "DAILY_MILESTONE_BADGE_NOT_CONFIGURED");
        }
        String facts = "LEDGER:" + milestone.milestoneDay() + ":" + tickets;
        if (mapper.insertCommand(scope.runId(), userId, "DAILY_MILESTONE_CLAIM", key, hash, facts) != 1) {
            throw new BizException(409, "WHEEL_SANDBOX_IDEMPOTENCY_CONFLICT");
        }
        if (mapper.insertCommand(scope.runId(), userId, "DAILY_MILESTONE_STATE", guardKey, hash, facts) != 1) {
            throw new BizException(409, "DAILY_MILESTONE_NOT_CLAIMABLE");
        }
        if ("UVT".equals(rewardType) || "USDT".equals(rewardType)) {
            creditMilestoneLedger(scope, milestoneId, rewardType, amount, milestone.badgeCode());
        } else if ("SPIN".equals(rewardType)) {
            creditMilestoneLedger(scope, milestoneId, "SPIN", BigDecimal.valueOf(tickets), milestone.badgeCode());
            for (int index = 1; index <= tickets; index++) {
                String ticketId = "SBX-TICKET-" + UUID.randomUUID().toString().replace("-", "").toUpperCase(Locale.ROOT);
                if (mapper.insertTicket(scope.runId(), userId, ticketId, "DAILY_MILESTONE", sourcePrefix + index) != 1) {
                    throw new BizException(409, "WHEEL_SANDBOX_TICKET_CONFLICT");
                }
            }
        } else {
            creditMilestoneLedger(scope, milestoneId, "BADGE", amount, milestone.badgeCode());
        }
        return milestoneResponse(scope, milestone.milestoneId(), milestone.milestoneDay(),
                rewardType, amount, tickets, milestone.badgeCode(), false);
    }

    private ApiResult<Map<String, Object>> replayMilestone(WheelSandboxProfile.Scope scope,
                                                            Long milestoneId, SandboxCommand command) {
        String stored = command.spinNo();
        if (stored != null && stored.matches("\\d+")) {
            // Commands created by the old preview stored only a ticket count.
            return legacyMilestoneResponse(scope, milestoneId, parseCount(stored), true);
        }
        if (stored != null && stored.startsWith("LEDGER:")) {
            String[] parts = stored.split(":");
            if (parts.length != 3) throw new BizException(409, "WHEEL_SANDBOX_REPLAY_UNAVAILABLE");
            Map<String, Object> fact = mapper.milestoneRewardFact(scope.runId(), scope.userId(), milestoneId);
            if (fact == null) throw new BizException(409, "WHEEL_SANDBOX_REPLAY_UNAVAILABLE");
            String type = String.valueOf(fact.get("asset")).toUpperCase(Locale.ROOT);
            String bizNo = String.valueOf(fact.get("bizNo"));
            String prefix = "MILESTONE:" + milestoneId + ":";
            String badge = bizNo.length() > prefix.length() ? bizNo.substring(prefix.length()) : null;
            return milestoneResponse(scope, milestoneId, Integer.parseInt(parts[1]), type,
                    (BigDecimal) fact.get("amount"), Integer.parseInt(parts[2]), badge, true);
        }
        throw new BizException(409, "WHEEL_SANDBOX_REPLAY_UNAVAILABLE");
    }

    private int milestoneClaimCount(WheelSandboxProfile.Scope scope, Long milestoneId) {
        return mapper.claimedMilestoneCommands(scope.runId(), scope.userId(), milestoneId)
                + mapper.claimedMilestoneTickets(scope.runId(), scope.userId(), milestoneId)
                + mapper.claimedMilestoneRewards(scope.runId(), scope.userId(), milestoneId);
    }

    private String milestoneKey(Long milestoneId) {
        return "MS:" + milestoneId;
    }

    private String normalizeMilestoneRewardType(Object raw) {
        String value = raw == null ? "" : String.valueOf(raw).trim().toUpperCase(Locale.ROOT);
        if (!MILESTONE_REWARD_TYPES.contains(value)) throw new BizException(409, "REWARD_TYPE_UNSUPPORTED");
        return value;
    }

    private void creditMilestoneLedger(WheelSandboxProfile.Scope scope, Long milestoneId,
                                       String asset, BigDecimal amount, String badgeCode) {
        BigDecimal before = mapper.rewardBalance(scope.runId(), scope.userId(), asset);
        if (before == null) before = BigDecimal.ZERO;
        String bizNo = "MILESTONE:" + milestoneId;
        if (StringUtils.hasText(badgeCode)) bizNo += ":" + badgeCode;
        if (mapper.insertReward(scope.runId(), scope.userId(), bizNo,
                asset, amount, before.add(amount)) != 1) {
            throw new BizException(409, "WHEEL_SANDBOX_REWARD_LEDGER_CONFLICT");
        }
    }

    private WheelSandboxProfile.Scope scope(Long userId) {
        profile.requireKnownRuntime();
        return profile.requireSandbox(userId);
    }

    private void ensureScope(WheelSandboxProfile.Scope scope) {
        if (mapper.findSandboxUser(scope.userId()) == null) {
            throw new BizException(403, "WHEEL_SANDBOX_USER_REQUIRED");
        }
        mapper.ensureScope(scope.runId(), scope.userId());
        // Fixed local fixture; it never reads the canonical tier/guard pool.
        mapper.retirePointTiers(scope.runId(), scope.userId());
        mapper.ensureTier(scope.runId(), scope.userId(), "comfort-nex-5", "+5 UVT", new BigDecimal("38.0000"), "uvt", new BigDecimal("5"), 10);
        mapper.ensureTier(scope.runId(), scope.userId(), "uvt-10", "+10 UVT", new BigDecimal("24.0000"), "uvt", new BigDecimal("10"), 20);
        mapper.ensureTier(scope.runId(), scope.userId(), "nex-30", "+30 UVT", new BigDecimal("33.0000"), "uvt", new BigDecimal("30"), 30);
        mapper.ensureTier(scope.runId(), scope.userId(), "usdt-1", "$1 USDT", new BigDecimal("5.0000"), "usdt", new BigDecimal("1"), 40);
        mapper.ensureGuard(scope.runId(), scope.userId(), "kill", "off");
        mapper.ensureGuard(scope.runId(), scope.userId(), "budget", "0");
        mapper.ensureGuard(scope.runId(), scope.userId(), "cap", "sandbox");
    }

    private void ensureQuestScope(WheelSandboxProfile.Scope scope) {
        if (mapper.findSandboxUser(scope.userId()) == null) {
            throw new BizException(403, "QUEST_SANDBOX_USER_REQUIRED");
        }
        mapper.ensureScope(scope.runId(), scope.userId());
        mapper.ensureQuest(scope.runId(), scope.userId(), "bind_bank_card", "Bind bank card", "DAY_ONE", new BigDecimal("50"));
        mapper.ensureQuest(scope.runId(), scope.userId(), "visit_earn", "Visit Earn", "DAY_ONE", new BigDecimal("30"));
        mapper.ensureQuest(scope.runId(), scope.userId(), "visit_store", "Visit Store", "DAY_ONE", new BigDecimal("50"));
        mapper.ensureQuest(scope.runId(), scope.userId(), "view_product_roi", "View product ROI", "DAY_ONE", new BigDecimal("100"));
        mapper.ensureQuest(scope.runId(), scope.userId(), "setup_profile", "Set up profile", "DAY_ONE", new BigDecimal("80"));
        mapper.ensureQuest(scope.runId(), scope.userId(), "invite_friend", "Invite a friend", "DAY_ONE", new BigDecimal("200"));
        if (mapper.countActiveWeeklyCodeCollisions(scope.runId(), scope.userId()) > 0) {
            throw new BizException(409, "QUEST_SANDBOX_CODE_COLLISION");
        }
        mapper.deactivateInactiveWeeklyQuests(scope.runId(), scope.userId());
        mapper.syncActiveWeeklyQuests(scope.runId(), scope.userId());
    }

    private void validateTiers(List<SandboxTier> tiers) {
        if (tiers == null || tiers.size() < 2 || tiers.size() > 12) throw new BizException(409, "WHEEL_TIER_COUNT_INVALID");
        BigDecimal total = BigDecimal.ZERO;
        for (SandboxTier tier : tiers) {
            if (tier == null || !StringUtils.hasText(tier.tierName()) || !REWARD_KINDS.contains(tier.rewardKind())
                    || tier.rewardAmount() == null || tier.rewardAmount().signum() <= 0
                    || tier.probabilityPct() == null || tier.probabilityPct().signum() < 0) {
                throw new BizException(409, "WHEEL_TIER_CONFIGURATION_INVALID");
            }
            total = total.add(tier.probabilityPct());
        }
        if (total.compareTo(ONE_HUNDRED) != 0) throw new BizException(409, "WHEEL_PROBABILITY_SUM_INVALID");
    }

    private SandboxTier draw(List<SandboxTier> tiers) {
        BigDecimal draw = BigDecimal.valueOf(RANDOM.nextDouble(100d));
        BigDecimal cumulative = BigDecimal.ZERO;
        for (SandboxTier tier : tiers) {
            cumulative = cumulative.add(tier.probabilityPct());
            if (draw.compareTo(cumulative) < 0) return tier;
        }
        return tiers.get(tiers.size() - 1);
    }

    private void creditLedger(WheelSandboxProfile.Scope scope, String spinNo, String kind, BigDecimal amount) {
        String asset = kind == null ? "UNKNOWN" : kind.trim().toUpperCase(Locale.ROOT);
        BigDecimal before = mapper.rewardBalance(scope.runId(), scope.userId(), asset);
        if (before == null) before = BigDecimal.ZERO;
        if (mapper.insertReward(scope.runId(), scope.userId(), "WHEEL:" + spinNo, asset, amount, before.add(amount)) != 1) {
            throw new BizException(409, "WHEEL_SANDBOX_REWARD_LEDGER_CONFLICT");
        }
    }

    private ApiResult<Map<String, Object>> spinResponse(WheelSandboxProfile.Scope scope, String code,
                                                          SandboxSpin spin, boolean replay) {
        return ApiResult.ok(linked("spinId", spin.spinId(), "eventId", "SANDBOX-" + code,
                "spinDate", String.valueOf(spin.spinDate()), "sourceType", spin.sourceType(),
                "tierId", spin.tierName(), "rewardType", spin.rewardKind().toUpperCase(Locale.ROOT),
                "rewardAmount", spin.rewardAmount(), "rewardName", spin.rewardName(),
                "downgraded", Boolean.TRUE.equals(spin.downgraded()), "downgradeReason", spin.downgradeReason(),
                "replay", replay, "source", "mock", "sourceEnvironment", "SANDBOX", "runId", scope.runId()));
    }

    private ApiResult<Map<String, Object>> milestoneResponse(WheelSandboxProfile.Scope scope,
                                                               Long milestoneId, Integer milestoneDay,
                                                               String rewardType, BigDecimal rewardAmount,
                                                               int tickets, String badgeCode, boolean replay) {
        return ApiResult.ok(linked("milestoneId", milestoneId,
                "milestoneDay", milestoneDay, "rewardType", rewardType,
                "rewardAmount", rewardAmount, "badgeCode", badgeCode,
                "spinTickets", tickets, "status", "CLAIMED", "replay", replay,
                "source", "mock", "serverCanonical", true,
                "sourceEnvironment", "SANDBOX",
                "runId", scope.runId()));
    }

    private ApiResult<Map<String, Object>> legacyMilestoneResponse(WheelSandboxProfile.Scope scope,
                                                                    Long milestoneId, int tickets, boolean replay) {
        return ApiResult.ok(linked("milestoneId", milestoneId, "rewardType", "SPIN", "spinTickets", tickets,
                "status", "CLAIMED", "replay", replay, "source", "mock", "serverCanonical", true,
                "sourceEnvironment", "SANDBOX", "runId", scope.runId()));
    }

    private Map<String, Object> publicSegment(SandboxTier tier, int order) {
        return linked("tierId", tier.tierName(), "rewardType", tier.rewardKind().toUpperCase(Locale.ROOT),
                "rewardAmount", tier.rewardAmount(), "rewardName", tier.rewardName(), "realOutflow", false,
                "displayOrder", order);
    }

    private Map<String, Object> publicHistory(Map<String, Object> row) {
        return linked("spinId", row.get("spinId"), "spinDate", row.get("spinDate"),
                "sourceType", row.get("sourceType"), "tierId", row.get("tierName"),
                "rewardType", String.valueOf(row.get("rewardKind")).toUpperCase(Locale.ROOT),
                "rewardAmount", row.get("rewardAmount"), "rewardName", row.get("rewardName"),
                "downgraded", row.get("downgraded"), "downgradeReason", row.get("downgradeReason"),
                "awardedAt", row.get("awardedAt"));
    }

    private LocalDate today() { return LocalDate.now(CLOCK.withZone(ZoneOffset.UTC)); }
    private int parseCount(String value) { try { return Math.max(0, Integer.parseInt(value)); } catch (Exception ex) { throw new BizException(409, "WHEEL_SANDBOX_REPLAY_UNAVAILABLE"); } }
    private String key(String value) { if (!StringUtils.hasText(value) || value.length() > 128) throw new BizException(422, "IDEMPOTENCY_KEY_REQUIRED"); return value.trim(); }
    private String reference(String value, String error) { if (!StringUtils.hasText(value) || value.length() > 64 || !value.matches("^[A-Za-z0-9._:-]+$")) throw new BizException(422, error); return value.trim(); }
    private String text(String value, String fallback) { return StringUtils.hasText(value) ? value.trim() : fallback; }
    private String sha256(String value) { try { return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); } catch (NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); } }
    private Map<String, Object> linked(Object... values) { Map<String, Object> result = new LinkedHashMap<>(); for (int i = 0; i < values.length; i += 2) result.put(String.valueOf(values[i]), values[i + 1]); return result; }

    public record QuestCompletionResult(String questCode, String status, boolean replay, String runId) { }
}
