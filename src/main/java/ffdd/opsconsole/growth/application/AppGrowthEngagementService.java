package ffdd.opsconsole.growth.application;

import ffdd.opsconsole.growth.facade.VoucherGrantFacade;
import ffdd.opsconsole.growth.facade.GrowthRhythmFacade;
import ffdd.opsconsole.growth.facade.GrowthRhythmSnapshot;
import ffdd.opsconsole.growth.facade.VoucherGrantFacade.VoucherGrantCommand;
import ffdd.opsconsole.growth.facade.VoucherGrantFacade.VoucherGrantResult;
import ffdd.opsconsole.growth.mapper.AppGrowthEngagementMapper;
import ffdd.opsconsole.growth.mapper.AppGrowthEngagementMapper.Attribution;
import ffdd.opsconsole.growth.mapper.AppGrowthEngagementMapper.DailyMilestone;
import ffdd.opsconsole.growth.mapper.AppGrowthEngagementMapper.EarningMilestone;
import ffdd.opsconsole.growth.mapper.AppGrowthEngagementMapper.EventReward;
import ffdd.opsconsole.growth.mapper.AppGrowthEngagementMapper.QuestReward;
import ffdd.opsconsole.growth.mapper.AppGrowthEngagementMapper.StreakState;
import ffdd.opsconsole.growth.mapper.AppGrowthEngagementMapper.VoucherClaimDefinition;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import ffdd.opsconsole.shared.outbox.EventOutboxService;
import ffdd.opsconsole.treasury.facade.TreasuryCoverageFacade;
import ffdd.opsconsole.treasury.facade.TreasuryCoverageSnapshot;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/** Real user claim/join/check-in/milestone commands for H3-H7. */
@Service
@RequiredArgsConstructor
public class AppGrowthEngagementService {
    private static final SecureRandom RANDOM = new SecureRandom();

    private final AppGrowthEngagementMapper mapper;
    private final VoucherGrantFacade voucherGrantFacade;
    private final GrowthRhythmFacade growthRhythmFacade;
    private final TreasuryCoverageFacade coverageFacade;
    private final AdminIdempotencyService idempotencyService;
    private final AuditLogService auditLogService;
    private final EventOutboxService outboxService;

    public ApiResult<Map<String, Object>> pointState(Long userId) {
        requireReadableUser(userId);
        Map<String, Object> streak = new LinkedHashMap<>(mapper.pointState(userId));
        Object checkedInToday = streak.get("checkedInToday");
        streak.put("checkedInToday", checkedInToday instanceof Boolean value
                ? value
                : checkedInToday instanceof Number number && number.intValue() != 0);
        streak.putIfAbsent("lastCheckInDate", null);
        return ApiResult.ok(linked(
                "streak", streak,
                "dailyMilestones", safeList(mapper.dailyMilestoneState(userId)),
                "earningMilestones", safeList(mapper.earningMilestoneState(userId)),
                "source", "nx_user_streak + nx_daily_check_in + milestone ledgers"));
    }

    public ApiResult<Map<String, Object>> voucherState(Long userId) {
        requireReadableUser(userId);
        Attribution attribution = attribution(userId);
        List<Map<String, Object>> rows = safeList(mapper.voucherState(userId, System.currentTimeMillis()));
        List<Map<String, Object>> vouchers = rows.stream().map(row -> {
            Map<String, Object> item = new LinkedHashMap<>(row);
            boolean audienceEligible = !"new".equalsIgnoreCase(String.valueOf(row.get("audience")))
                    || attribution.accountAgeMonths() == 0;
            item.put("claimable", audienceEligible && "UNCLAIMED".equalsIgnoreCase(String.valueOf(row.get("grantStatus"))));
            item.put("audienceEligible", audienceEligible);
            return item;
        }).toList();
        return ApiResult.ok(linked("vouchers", vouchers, "source", "nx_growth_voucher + nx_growth_voucher_grant"));
    }

    @Transactional(rollbackFor = Exception.class)
    public ApiResult<Map<String, Object>> claimQuest(Long userId, String questCode, String idempotencyKey) {
        requireUser(userId);
        String code = reference(questCode, "QUEST_CODE_REQUIRED");
        return executeOnce("QUEST_CLAIM", userId, idempotencyKey, code, () -> {
            QuestReward reward = mapper.lockClaimableQuest(userId, code);
            if (reward == null) return ApiResult.fail(409, "QUEST_NOT_CLAIMABLE");
            if (mapper.claimQuest(userId, reward.missionId()) != 1) throw conflict("QUEST_CLAIM_CONFLICT");
            GrowthRhythmSnapshot rhythm = growthRhythmFacade.snapshot();
            if (rhythm == null || rhythm.currentMonth() <= 0) throw conflict("H1_RHYTHM_UNAVAILABLE");
            BigDecimal multiplier = positiveOrOne(rhythm.questBonusMultiplier());
            BigDecimal amount = positive(reward.rewardNex()).multiply(multiplier)
                    .setScale(6, RoundingMode.DOWN);
            creditNex(userId, "QUEST:" + code + ":" + userId, "QUEST_REWARD", amount, "H3 quest claim");
            Map<String, Object> detail = linked(
                    "layer", reward.layer(), "rewardNex", amount, "multiplier", multiplier,
                    "rhythmMonth", rhythm.currentMonth());
            audit("H3_QUEST_CLAIMED", "USER_MISSION", code, code, userId, detail);
            publish("MISSION", code, "quest.claimed", userId, attribution(userId), detail);
            return ApiResult.ok(linked("questId", code, "rewardNex", amount, "status", "CLAIMED"));
        });
    }

    @Transactional(rollbackFor = Exception.class)
    public ApiResult<Map<String, Object>> joinEvent(Long userId, String eventCode, String idempotencyKey) {
        requireUser(userId);
        String code = reference(eventCode, "EVENT_CODE_REQUIRED");
        return executeOnce("EVENT_JOIN", userId, idempotencyKey, code, () -> {
            EventReward event = mapper.lockOpenEvent(code);
            if (event == null) return ApiResult.fail(409, "EVENT_NOT_OPEN");
            if (mapper.joinEvent(userId, event) != 1) return ApiResult.fail(409, "EVENT_ALREADY_JOINED");
            Map<String, Object> detail = linked("campaignId", code);
            audit("H4_EVENT_JOINED", "USER_EVENT_QUEST", code, code, userId, detail);
            publish("EVENT_QUEST", code, "event.joined", userId, attribution(userId), detail);
            return ApiResult.ok(linked("eventId", code, "status", "JOINED"));
        });
    }

    @Transactional(rollbackFor = Exception.class)
    public ApiResult<Map<String, Object>> claimEvent(Long userId, String eventCode, String idempotencyKey) {
        requireUser(userId);
        String code = reference(eventCode, "EVENT_CODE_REQUIRED");
        return executeOnce("EVENT_CLAIM", userId, idempotencyKey, code, () -> {
            EventReward reward = mapper.lockClaimableEvent(userId, code);
            if (reward == null) return ApiResult.fail(409, "EVENT_NOT_CLAIMABLE");
            if (mapper.claimEvent(userId, code) != 1) throw conflict("EVENT_CLAIM_CONFLICT");
            String rewardType = reward.rewardType() == null ? "" : reward.rewardType().trim().toUpperCase(Locale.ROOT);
            BigDecimal amount = reward.rewardAmount() == null ? BigDecimal.ZERO : reward.rewardAmount();
            if ("NEX".equals(rewardType)) {
                amount = positive(amount);
                creditNex(userId, "EVENT:" + code + ":" + userId, "EVENT_REWARD", amount, "H4 event reward");
            } else if ("BADGE".equals(rewardType) && StringUtils.hasText(reward.badgeCode())) {
                grantBadge(userId, reward.badgeCode(), "EVENT_BADGE_GRANT_CONFLICT");
            } else {
                throw conflict("REWARD_TYPE_UNSUPPORTED");
            }
            Map<String, Object> detail = linked(
                    "campaignId", code, "rewardType", rewardType, "rewardAmount", amount,
                    "badgeCode", "BADGE".equals(rewardType) ? reward.badgeCode() : "NONE");
            audit("H4_EVENT_CLAIMED", "USER_EVENT_QUEST", code, code, userId, detail);
            publish("EVENT_QUEST", code, "event.claimed", userId, attribution(userId), detail);
            return ApiResult.ok(linked(
                    "eventId", code, "rewardType", rewardType, "rewardAmount", amount,
                    "badgeCode", "BADGE".equals(rewardType) ? reward.badgeCode() : null));
        });
    }

    @Transactional(rollbackFor = Exception.class)
    public ApiResult<Map<String, Object>> checkIn(Long userId, String idempotencyKey) {
        requireUser(userId);
        LocalDate today = LocalDate.now();
        return executeOnce("DAILY_CHECK_IN", userId, idempotencyKey, today, () -> {
            Long missionId = mapper.dailyMissionId();
            if (missionId == null) throw conflict("DAILY_MISSION_NOT_CONFIGURED");
            mapper.ensureUserStreak(userId);
            StreakState before = mapper.lockStreak(userId);
            if (before == null) throw conflict("USER_STREAK_UNAVAILABLE");
            int streak = today.minusDays(1).equals(before.lastCheckInDate())
                    ? Math.max(0, before.currentStreak()) + 1 : 1;
            if (today.equals(before.lastCheckInDate())) return ApiResult.fail(409, "DAILY_ALREADY_CLAIMED");
            int base = positiveInt(mapper.checkInRule("baseline"), 1);
            BigDecimal multiplier = drawLuckyMultiplier();
            int luckyReward = BigDecimal.valueOf(base).multiply(multiplier)
                    .setScale(0, RoundingMode.DOWN).intValueExact();
            int streakBonus = streak % 7 == 0 ? positiveInt(mapper.checkInRule("bonus7"), 5) : 0;
            int reward = Math.addExact(luckyReward, streakBonus);
            if (mapper.insertCheckIn(userId, missionId, today, base, multiplier,
                    luckyReward - base, streakBonus, reward) != 1) {
                throw conflict("DAILY_CHECK_IN_CONFLICT");
            }
            if (mapper.updateStreak(userId, streak, today) != 1) throw conflict("DAILY_STREAK_CONFLICT");
            creditPoints(userId, "DAILY:" + userId + ":" + today, "DAILY_CHECK_IN", reward);
            Map<String, Object> detail = linked(
                    "checkInDate", today.toString(), "basePoints", base, "rewardPoints", reward,
                    "streakBonusPoints", streakBonus, "multiplier", multiplier, "streakDays", streak);
            audit("H5_DAILY_CHECKED_IN", "DAILY_CHECK_IN", today.toString(), today.toString(), userId, detail);
            Attribution attribution = attribution(userId);
            publish("DAILY_CHECK_IN", userId + ":" + today, "daily.checkin",
                    userId, attribution, detail);
            if (multiplier.compareTo(BigDecimal.ONE) > 0) {
                publish("DAILY_CHECK_IN", userId + ":" + today, "daily.lucky_triggered",
                        userId, attribution, linked("multiplier", multiplier));
            }
            return ApiResult.ok(detail);
        });
    }

    @Transactional(rollbackFor = Exception.class)
    public ApiResult<Map<String, Object>> claimDailyMilestone(
            Long userId, Long milestoneId, String idempotencyKey) {
        requireUser(userId);
        if (milestoneId == null || milestoneId <= 0) return ApiResult.fail(422, "MILESTONE_ID_REQUIRED");
        return executeOnce("DAILY_MILESTONE_CLAIM", userId, idempotencyKey, milestoneId, () -> {
            DailyMilestone row = mapper.lockClaimableDailyMilestone(userId, milestoneId);
            if (row == null) return ApiResult.fail(409, "DAILY_MILESTONE_NOT_CLAIMABLE");
            if (mapper.claimDailyMilestone(userId, row) < 1) throw conflict("DAILY_MILESTONE_CLAIM_CONFLICT");
            String rewardType = normalizeDailyRewardType(row.rewardType());
            BigDecimal amount = row.rewardAmount() == null ? BigDecimal.ZERO : row.rewardAmount();
            String bizNo = "DAILY-MS:" + userId + ":" + row.milestoneId();
            int tickets = 0;
            switch (rewardType) {
                case "POINTS" -> creditPoints(userId, bizNo, "DAILY_MILESTONE", positiveWholeNumber(amount));
                case "NEX" -> creditNex(userId, bizNo, "DAILY_MILESTONE", positive(amount),
                        "H5 daily milestone claim");
                case "USDT" -> creditUsdt(userId, bizNo, "DAILY_MILESTONE", positive(amount),
                        "H5 daily milestone claim");
                case "SPIN" -> tickets = issueSpinTickets(userId, row, positiveWholeNumber(amount));
                case "BADGE" -> {
                    if (!StringUtils.hasText(row.badgeCode())) throw conflict("DAILY_MILESTONE_BADGE_NOT_CONFIGURED");
                    grantBadge(userId, row.badgeCode(), "DAILY_MILESTONE_BADGE_GRANT_CONFLICT");
                }
                default -> throw conflict("REWARD_TYPE_UNSUPPORTED");
            }
            Map<String, Object> detail = linked(
                    "day", row.milestoneDay(), "rewardType", rewardType, "amount", amount);
            audit("H5_DAILY_MILESTONE_CLAIMED", "USER_STREAK_MILESTONE",
                    String.valueOf(row.milestoneId()), String.valueOf(row.milestoneId()), userId, detail);
            Attribution attribution = attribution(userId);
            publish("DAILY_MILESTONE", String.valueOf(row.milestoneId()), "daily.milestone_claimed",
                    userId, attribution, detail);
            if (tickets > 0) {
                publish("DAILY_MILESTONE", String.valueOf(row.milestoneId()), "daily.spin_awarded",
                        userId, attribution, linked("milestoneId", row.milestoneId(), "tickets", tickets));
            }
            return ApiResult.ok(linked(
                    "milestoneId", row.milestoneId(), "milestoneDay", row.milestoneDay(),
                    "rewardType", rewardType, "rewardAmount", amount, "badgeCode", row.badgeCode(),
                    "spinTickets", tickets));
        });
    }

    @Transactional(rollbackFor = Exception.class)
    public ApiResult<Map<String, Object>> evaluateEarningMilestones(Long userId, String idempotencyKey) {
        requireUser(userId);
        return executeOnce("EARNING_MILESTONE_EVALUATE", userId, idempotencyKey, "eligible-rules", () -> {
            List<EarningMilestone> eligible = mapper.lockEligibleEarningMilestones(userId);
            List<Map<String, Object>> fired = new ArrayList<>();
            Attribution attribution = attribution(userId);
            List<EarningMilestone> ordered = eligible == null ? List.of() : eligible;
            // H6 deliberately advances one rung per authoritative evaluation tick.
            for (EarningMilestone row : ordered.stream().limit(1).toList()) {
                BigDecimal reward = positive(row.rewardNex());
                String eventNo = "EMS-" + UUID.randomUUID().toString().replace("-", "").toUpperCase(Locale.ROOT);
                if (mapper.insertEarningMilestone(userId, row, eventNo) != 1) {
                    throw conflict("EARNING_MILESTONE_CONFLICT");
                }
                creditNex(userId, "EARNING-MS:" + userId + ":" + row.milestoneId(),
                        "EARNING_MILESTONE", reward, "H5 earning milestone fired");
                Map<String, Object> detail = linked(
                        "milestoneId", row.milestoneId(), "thresholdUsd", row.thresholdUsdt(),
                        "rewardNex", reward, "lifetimeEarningsUsd", row.lifetimeEarningsUsdt());
                audit("H5_EARNING_MILESTONE_FIRED", "EARNING_MILESTONE",
                        row.milestoneId(), eventNo, userId, detail);
                publish("EARNING_MILESTONE", eventNo, "milestone.fired", userId, attribution, detail);
                fired.add(detail);
            }
            return ApiResult.ok(linked("fired", fired, "count", fired.size()));
        });
    }

    @Transactional(rollbackFor = Exception.class)
    public ApiResult<Map<String, Object>> claimVoucher(
            Long userId, String voucherId, String surface, String idempotencyKey) {
        requireUser(userId);
        String code = reference(voucherId, "VOUCHER_ID_REQUIRED");
        String normalizedSurface = StringUtils.hasText(surface) ? surface.trim().toLowerCase(Locale.ROOT) : "home";
        if (!Set.of("home", "store", "me", "earn").contains(normalizedSurface)) {
            return ApiResult.fail(422, "VOUCHER_SURFACE_INVALID");
        }
        return executeOnce("VOUCHER_CLAIM", userId, idempotencyKey,
                linked("voucherId", code, "surface", normalizedSurface), () -> {
            Attribution attribution = attribution(userId);
            VoucherClaimDefinition definition = mapper.lockUserClaimableVoucher(
                    code, normalizedSurface, System.currentTimeMillis());
            if (definition == null) return ApiResult.fail(409, "VOUCHER_NOT_CLAIMABLE_FROM_SURFACE");
            if ("new".equalsIgnoreCase(definition.audience()) && attribution.accountAgeMonths() > 0) {
                return ApiResult.fail(409, "VOUCHER_AUDIENCE_NOT_ELIGIBLE");
            }
            String sourceId = userId + ":" + code;
            VoucherGrantResult grant = voucherGrantFacade.grant(new VoucherGrantCommand(
                    userId, code, "user-claim:" + sourceId, "USER_CLAIM", sourceId,
                    "user:" + userId, "User claimed voucher from " + normalizedSurface));
            if (!grant.replayed()) {
                Map<String, Object> eventDetail = linked(
                        "voucherId", code, "surface", normalizedSurface, "audience", definition.audience());
                audit("H7_VOUCHER_CLAIMED", "USER_VOUCHER_GRANT", grant.grantId(), sourceId, userId,
                        linked("grantId", grant.grantId(), "voucherId", code,
                                "surface", normalizedSurface, "audience", definition.audience()));
                publish("VOUCHER_GRANT", grant.grantId(), "voucher.claimed", userId, attribution, eventDetail);
            }
            return ApiResult.ok(linked(
                    "voucherId", code, "grantId", grant.grantId(), "status", "AVAILABLE",
                    "replay", grant.replayed()));
        });
    }

    private void requireUser(Long userId) {
        if (userId == null || userId <= 0 || mapper.lockActiveUser(userId) == null) {
            throw new BizException(404, "USER_NOT_FOUND_OR_INACTIVE");
        }
    }

    private void requireReadableUser(Long userId) {
        if (userId == null || userId <= 0 || mapper.findActiveUser(userId) == null) {
            throw new BizException(404, "USER_NOT_FOUND_OR_INACTIVE");
        }
    }

    private List<Map<String, Object>> safeList(List<Map<String, Object>> value) {
        return value == null ? List.of() : value;
    }

    private Attribution attribution(Long userId) {
        Attribution value = mapper.attribution(userId);
        if (value == null || value.accountAgeMonths() == null || !StringUtils.hasText(value.cohort())) {
            throw conflict("USER_EVENT_ATTRIBUTION_UNAVAILABLE");
        }
        String phase = value.phase() == null ? "P1" : value.phase().trim().toUpperCase(Locale.ROOT);
        if (phase.matches("[1-6]")) phase = "P" + phase;
        if (!phase.matches("P[1-6]")) phase = "P1";
        return new Attribution(phase, value.accountAgeMonths(), value.cohort());
    }

    private void creditNex(Long userId, String bizNo, String bizType, BigDecimal amount, String remark) {
        if (amount.signum() <= 0) throw conflict("REWARD_AMOUNT_INVALID");
        requireHealthyCoverage();
        BigDecimal before = mapper.lockWalletNex(userId);
        if (before == null) throw conflict("USER_WALLET_NOT_FOUND");
        if (mapper.creditWalletNex(userId, amount) != 1) throw conflict("USER_WALLET_CONFLICT");
        if (mapper.insertNexLedger(userId, bizNo, bizType, amount, before.add(amount), remark) != 1) {
            throw conflict("REWARD_LEDGER_CONFLICT");
        }
    }

    private void creditUsdt(Long userId, String bizNo, String bizType, BigDecimal amount, String remark) {
        if (amount.signum() <= 0) throw conflict("REWARD_AMOUNT_INVALID");
        requireHealthyCoverage();
        BigDecimal before = mapper.lockWalletUsdt(userId);
        if (before == null) throw conflict("USER_WALLET_NOT_FOUND");
        if (mapper.creditWalletUsdt(userId, amount) != 1) throw conflict("USER_WALLET_CONFLICT");
        if (mapper.insertUsdtLedger(userId, bizNo, bizType, amount, before.add(amount), remark) != 1) {
            throw conflict("REWARD_LEDGER_CONFLICT");
        }
    }

    private void creditPoints(Long userId, String bizNo, String bizType, int points) {
        if (points <= 0) throw conflict("REWARD_AMOUNT_INVALID");
        Integer before = mapper.currentPointsBalance(userId);
        int balance = before == null ? 0 : before;
        if (mapper.insertPointsLedger(userId, bizNo, bizType, points, Math.addExact(balance, points)) != 1) {
            throw conflict("REWARD_LEDGER_CONFLICT");
        }
    }

    private int issueSpinTickets(Long userId, DailyMilestone row, int count) {
        for (int index = 1; index <= count; index++) {
            String ticketId = "SPIN-" + UUID.randomUUID().toString().replace("-", "").toUpperCase(Locale.ROOT);
            String sourceId = row.milestoneId() + ":" + index;
            if (mapper.insertSpinTicket(ticketId, userId, "DAILY_MILESTONE", sourceId) != 1) {
                throw conflict("DAILY_SPIN_TICKET_CONFLICT");
            }
        }
        return count;
    }

    private void grantBadge(Long userId, String badgeCode, String conflictMessage) {
        if (mapper.unlockAchievement(userId, badgeCode) == 1) return;
        if (mapper.lockUserAchievement(userId, badgeCode) == null) throw conflict(conflictMessage);
    }

    private void requireHealthyCoverage() {
        TreasuryCoverageSnapshot snapshot = coverageFacade.snapshot();
        if (snapshot == null || !snapshot.reliable() || snapshot.coverageRatio() == null
                || snapshot.redlinePct() == null || snapshot.coverageRatio().signum() <= 0
                || snapshot.redlinePct().signum() <= 0) {
            throw new BizException(422, "B1_COVERAGE_DATA_UNAVAILABLE");
        }
        if (snapshot.coverageRatio().compareTo(snapshot.redlinePct()) < 0) {
            throw new BizException(422, "B1_COVERAGE_BELOW_REDLINE");
        }
    }

    private BigDecimal drawLuckyMultiplier() {
        double p2 = boundedProbability(mapper.checkInRule("p2"));
        double p15 = boundedProbability(mapper.checkInRule("p15"));
        if (p2 + p15 > 100d) throw new BizException(409, "DAILY_LUCKY_PROBABILITY_INVALID");
        double draw = RANDOM.nextDouble() * 100d;
        if (draw < p2) return new BigDecimal("2.0");
        if (draw < p2 + p15) return new BigDecimal("1.5");
        return BigDecimal.ONE;
    }

    private double boundedProbability(String value) {
        if (!StringUtils.hasText(value)) return 0d;
        try {
            double parsed = Double.parseDouble(value.trim());
            return Math.max(0d, Math.min(100d, parsed));
        } catch (NumberFormatException ex) {
            throw new BizException(409, "DAILY_LUCKY_PROBABILITY_INVALID");
        }
    }

    private int positiveInt(String value, int fallback) {
        try {
            int parsed = StringUtils.hasText(value) ? Integer.parseInt(value.trim()) : fallback;
            if (parsed <= 0) throw new NumberFormatException();
            return parsed;
        } catch (NumberFormatException ex) {
            throw new BizException(409, "DAILY_BASE_REWARD_INVALID");
        }
    }

    private BigDecimal positive(BigDecimal value) {
        if (value == null || value.signum() <= 0) throw conflict("REWARD_AMOUNT_INVALID");
        return value;
    }

    private BigDecimal positiveOrOne(BigDecimal value) {
        return value == null || value.signum() <= 0 ? BigDecimal.ONE : value;
    }

    private int positiveWholeNumber(BigDecimal value) {
        BigDecimal amount = positive(value).stripTrailingZeros();
        if (amount.scale() > 0) throw conflict("REWARD_AMOUNT_MUST_BE_WHOLE_NUMBER");
        try {
            return amount.intValueExact();
        } catch (ArithmeticException ex) {
            throw conflict("REWARD_AMOUNT_INVALID");
        }
    }

    private String normalizeDailyRewardType(String rewardType) {
        String normalized = StringUtils.hasText(rewardType)
                ? rewardType.trim().toUpperCase(Locale.ROOT) : "";
        if (!Set.of("POINTS", "USDT", "NEX", "SPIN", "BADGE").contains(normalized)) {
            throw conflict("REWARD_TYPE_UNSUPPORTED");
        }
        return normalized;
    }

    private String reference(String value, String error) {
        if (!StringUtils.hasText(value) || value.length() > 64 || !value.matches("^[A-Za-z0-9._:-]+$")) {
            throw new BizException(422, error);
        }
        return value.trim();
    }

    private void audit(
            String action, String resourceType, String resourceId, String bizNo,
            Long userId, Map<String, Object> detail) {
        auditLogService.recordRequired(AuditLogWriteRequest.builder()
                .action(action).resourceType(resourceType).resourceId(resourceId).bizNo(bizNo)
                .userId(userId).actorId(userId).actorType("USER").actorUsername("user:" + userId)
                .result("SUCCESS").riskLevel("HIGH").detail(detail).build());
    }

    private void publish(
            String aggregateType, String aggregateId, String eventName, Long userId,
            Attribution attribution, Map<String, Object> detail) {
        outboxService.publishUserEvent(
                aggregateType, aggregateId, eventName, userId, attribution.phase(),
                attribution.accountAgeMonths(), attribution.cohort(), detail);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private ApiResult<Map<String, Object>> executeOnce(
            String operation, Long userId, String idempotencyKey, Object request,
            Supplier<ApiResult<Map<String, Object>>> action) {
        String scope = "APP:" + operation + ":USER:" + userId;
        return (ApiResult<Map<String, Object>>) (ApiResult) idempotencyService.execute(
                scope, idempotencyKey, sha256(String.valueOf(request)), ApiResult.class, (Supplier) action);
    }

    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    private BizException conflict(String message) {
        return new BizException(409, message);
    }

    private Map<String, Object> linked(Object... values) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < values.length; i += 2) result.put(String.valueOf(values[i]), values[i + 1]);
        return result;
    }
}
