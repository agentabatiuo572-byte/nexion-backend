package ffdd.opsconsole.growth.application;

import ffdd.opsconsole.growth.facade.VoucherGrantFacade;
import ffdd.opsconsole.growth.facade.GrowthRhythmFacade;
import ffdd.opsconsole.growth.facade.GrowthRhythmSnapshot;
import ffdd.opsconsole.growth.facade.VoucherGrantFacade.VoucherGrantCommand;
import ffdd.opsconsole.growth.facade.VoucherGrantFacade.VoucherGrantResult;
import ffdd.opsconsole.growth.mapper.AppGrowthEngagementMapper;
import ffdd.opsconsole.growth.mapper.AppGrowthEngagementMapper.Attribution;
import ffdd.opsconsole.growth.mapper.AppGrowthEngagementMapper.DailyMilestone;
import ffdd.opsconsole.growth.mapper.AppGrowthEngagementMapper.DayOneQuestState;
import ffdd.opsconsole.growth.mapper.AppGrowthEngagementMapper.EarningMilestone;
import ffdd.opsconsole.growth.mapper.AppGrowthEngagementMapper.EventReward;
import ffdd.opsconsole.growth.mapper.AppGrowthEngagementMapper.QuestReward;
import ffdd.opsconsole.growth.mapper.AppGrowthEngagementMapper.StreakState;
import ffdd.opsconsole.growth.mapper.AppGrowthEngagementMapper.StreakPowerUp;
import ffdd.opsconsole.growth.mapper.AppGrowthEngagementMapper.VoucherClaimDefinition;
import ffdd.opsconsole.finance.application.EarningsReleaseService;
import ffdd.opsconsole.finance.application.FundsSandboxProfileGuard;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import ffdd.opsconsole.shared.outbox.EventOutboxService;
import ffdd.opsconsole.shared.security.UserAuthEnvironment;
import ffdd.opsconsole.treasury.facade.TreasuryCoverageFacade;
import ffdd.opsconsole.treasury.facade.TreasuryCoverageSnapshot;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/** Real user claim/join/check-in/milestone commands for H3-H7. */
@Service
@RequiredArgsConstructor
public class AppGrowthEngagementService {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final ZoneId H5_BUSINESS_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final Set<String> SHARE_CHANNELS = Set.of(
            "telegram", "zalo", "whatsapp", "messenger", "sms", "x",
            "copy", "poster", "system", "code", "link");
    private static final Set<String> SHARE_SURFACES = Set.of(
            "team_hero", "poster_sheet", "share_sheet", "proof");

    private final AppGrowthEngagementMapper mapper;
    private final VoucherGrantFacade voucherGrantFacade;
    private final GrowthRhythmFacade growthRhythmFacade;
    private final TreasuryCoverageFacade coverageFacade;
    private final AdminIdempotencyService idempotencyService;
    private final AuditLogService auditLogService;
    private final EventOutboxService outboxService;
    private final EarningsReleaseService earningsReleaseService;

    private final AppGrowthWheelSandboxService sandboxService;
    private final QuestCompletionFactConsumer questFactConsumer;

    /** The run-fenced projection is absent from production-only deployments.
     * Optional is constructor-resolved by Spring and avoids mutable field injection. */
    private final Optional<AppGrowthVoucherSandboxService> voucherSandboxService;
    private final Environment environment;

    public ApiResult<Map<String, Object>> questState(Long userId) {
        return questState(userId, "en");
    }

    public ApiResult<Map<String, Object>> questState(Long userId, String locale) {
        if (sandboxService != null) {
            if (sandboxService.enabled()) return sandboxService.questState(userId);
            if (sandboxService.unknownProfile()) throw new BizException(503, "WHEEL_RUNTIME_PROFILE_UNSUPPORTED");
        }
        requireReadableUser(userId);
        GrowthRhythmSnapshot rhythm = growthRhythmFacade.snapshot();
        if (rhythm == null || rhythm.currentMonth() <= 0 || rhythm.questBonusMultiplier() == null) {
            throw conflict("H1_RHYTHM_UNAVAILABLE");
        }
        Map<String, Object> promo = mapper.questPromoBanner();
        List<Map<String, Object>> rawQuests = safeList(mapper.questState(userId, contentLocale(locale)));
        BigDecimal dayOneReward = rawQuests.stream()
                .filter(row -> "DAY_ONE".equalsIgnoreCase(String.valueOf(row.get("layer"))))
                .map(row -> DayOneTriRewardPolicy.effectiveDayOneReward(
                        row.get("triReward") == null ? null : String.valueOf(row.get("triReward")),
                        numberValue(row.get("accountAgeHours"), 0L),
                        numberValue(row.get("eligibilityHours"), 72L)))
                .findFirst().orElse(BigDecimal.ZERO);
        List<Map<String, Object>> quests = rawQuests.stream()
                .map(this::projectQuestRewardPolicy).toList();
        return ApiResult.ok(productionResponse(linked(
                "quests", quests,
                "dayOneRewardNex", dayOneReward,
                "promoBanner", promo == null ? Map.of() : new LinkedHashMap<>(promo),
                "questBonusMultiplier", positiveOrOne(rhythm.questBonusMultiplier()),
                "rhythmMonth", rhythm.currentMonth(),
                "serverCanonical", true, "sourceEnvironment", "PRODUCTION", "runId", "",
                "source", "nx_mission + nx_user_mission + nx_growth_promo_banner + H1 rhythm")));
    }

    public ApiResult<Map<String, Object>> eventState(Long userId) {
        return eventState(userId, "en");
    }

    public ApiResult<Map<String, Object>> eventState(Long userId, String locale) {
        requireCanonicalEngagementRuntime();
        requireReadableUser(userId);
        List<Map<String, Object>> events = safeList(mapper.eventState(userId, contentLocale(locale)));
        long featuredOngoing = events.stream()
                .filter(row -> Boolean.TRUE.equals(row.get("featured"))
                        || row.get("featured") instanceof Number number && number.intValue() == 1)
                .filter(row -> "ongoing".equals(String.valueOf(row.get("state"))))
                .count();
        if (featuredOngoing > 1) {
            throw conflict("EVENT_FEATURED_UNIQUE_VIOLATION");
        }
        return ApiResult.ok(linked(
                "events", events,
                "serverTimeUtc", java.time.Instant.now().toString(),
                "source", "nx_event_quest + nx_user_event_quest"));
    }

    private String contentLocale(String locale) {
        String normalized = locale == null ? "" : locale.trim().toLowerCase(Locale.ROOT);
        return Set.of("en", "zh", "vi").contains(normalized) ? normalized : "en";
    }

    public ApiResult<Map<String, Object>> pointState(Long userId) {
        if (sandboxService != null) {
            if (sandboxService.enabled()) return sandboxService.pointState(userId);
            if (sandboxService.unknownProfile()) throw new BizException(503, "WHEEL_RUNTIME_PROFILE_UNSUPPORTED");
        }
        requireReadableUser(userId);
        LocalDate today = LocalDate.now(H5_BUSINESS_ZONE);
        Map<String, Object> rawStreak = mapper.pointState(userId, today);
        Map<String, Object> streak = rawStreak == null
                ? new LinkedHashMap<>()
                : new LinkedHashMap<>(rawStreak);
        Object checkedInToday = streak.get("checkedInToday");
        streak.put("checkedInToday", checkedInToday instanceof Boolean value
                ? value
                : checkedInToday instanceof Number number && number.intValue() != 0);
        streak.putIfAbsent("lastCheckInDate", null);
        return ApiResult.ok(productionResponse(linked(
                "rewardAsset", "NEX",
                "serverDate", today.toString(),
                "nextResetAtUtc", ZonedDateTime.of(
                        today.plusDays(1), java.time.LocalTime.MIDNIGHT, H5_BUSINESS_ZONE)
                        .toInstant().toString(),
                "streak", streak,
                "dailyMilestones", safeList(mapper.dailyMilestoneState(userId)),
                "earningMilestones", safeList(mapper.earningMilestoneState(userId)),
                "badgeAchievements", safeList(mapper.achievementState(userId)),
                "rules", safeList(mapper.checkInRuleState()),
                "powerUps", safeList(mapper.streakPowerUpState(userId)),
                "topStreakers", safeList(mapper.topStreakers()),
                "source", "nx_user_streak + nx_daily_check_in + NEX wallet + milestone ledgers + nx_achievement")));
    }

    public ApiResult<Map<String, Object>> voucherState(Long userId) {
        return voucherState(userId, null);
    }

    public ApiResult<Map<String, Object>> voucherState(Long userId, String requestedRunId) {
        if (voucherSandboxService.isPresent()) {
            if (voucherSandboxService.get().enabled()) return voucherSandboxService.get().voucherState(userId, requestedRunId);
            if (voucherSandboxService.get().unknownProfile()) {
                throw new BizException(503, "WHEEL_RUNTIME_PROFILE_UNSUPPORTED");
            }
        }
        requireCanonicalEngagementRuntime();
        requireReadableUser(userId);
        Attribution attribution = attribution(userId);
        long nowMillis = System.currentTimeMillis();
        List<Map<String, Object>> rows = safeList(mapper.voucherState(userId, nowMillis));
        List<Map<String, Object>> vouchers = rows.stream().map(row -> {
            Map<String, Object> item = new LinkedHashMap<>(row);
            boolean audienceEligible = !"new".equalsIgnoreCase(String.valueOf(row.get("audience")))
                    || attribution.accountAgeMonths() == 0;
            String grantStatus = String.valueOf(row.getOrDefault("grantStatus", "UNCLAIMED")).toUpperCase(Locale.ROOT);
            long endAt = row.get("endAt") instanceof Number number ? number.longValue() : 0L;
            if ("AVAILABLE".equals(grantStatus) && endAt > 0 && endAt < nowMillis) {
                grantStatus = "EXPIRED";
            }
            boolean definitionOpen = !truthy(row.get("definitionDeleted"))
                    && "active".equalsIgnoreCase(String.valueOf(row.get("definitionStatus")));
            long lastSeenAt = numberValue(row.get("popupLastSeenAt"));
            long delayMs = boundedCadence(row, "popupDelayMs", 1300L, 0L, 60_000L);
            long cooldownHours = boundedCadence(row, "popupCooldownHours", 24L, 0L, 720L);
            long maxPerSession = boundedCadence(row, "popupMaxPerSession", 1L, 1L, 10L);
            long nextEligibleAt = lastSeenAt > 0 ? safeNextEligibleAt(lastSeenAt, cooldownHours) : 0L;
            boolean cadenceEnabled = truthy(row.get("popupCadenceEnabled"));
            item.put("popupDelayMs", delayMs);
            item.put("popupCooldownHours", cooldownHours);
            item.put("popupMaxPerSession", maxPerSession);
            item.put("grantStatus", grantStatus);
            item.put("claimable", audienceEligible && definitionOpen && "UNCLAIMED".equals(grantStatus));
            item.put("audienceEligible", audienceEligible);
            item.put("nextEligibleAt", nextEligibleAt);
            item.put("popupEligible", cadenceEnabled && truthy(row.get("popupEnabled"))
                    && audienceEligible && definitionOpen && "UNCLAIMED".equals(grantStatus)
                    && (nextEligibleAt == 0L || nextEligibleAt <= nowMillis));
            return item;
        }).toList();
        return ApiResult.ok(linked("vouchers", vouchers, "source", "nx_growth_voucher + nx_growth_voucher_grant",
                "serverCanonical", true,
                "provenance", linked("source", "nx_growth_voucher", "sourceEnvironment", "PRODUCTION", "runId", "")));
    }

    @Transactional
    public ApiResult<Map<String, Object>> markVoucherPopupSeen(Long userId, String voucherId) {
        return markVoucherPopupSeen(userId, voucherId, null);
    }

    @Transactional
    public ApiResult<Map<String, Object>> markVoucherPopupSeen(
            Long userId, String voucherId, String requestedRunId) {
        if (voucherSandboxService.isPresent()) {
            if (voucherSandboxService.get().enabled()) {
                return voucherSandboxService.get().markVoucherPopupSeen(userId, voucherId, requestedRunId);
            }
            if (voucherSandboxService.get().unknownProfile()) {
                throw new BizException(503, "WHEEL_RUNTIME_PROFILE_UNSUPPORTED");
            }
        }
        requireUser(userId);
        String code = reference(voucherId, "VOUCHER_ID_REQUIRED");
        if (mapper.markVoucherPopupSeen(userId, code, System.currentTimeMillis()) != 1) {
            return ApiResult.fail(409, "VOUCHER_POPUP_STATE_CONFLICT");
        }
        return voucherState(userId, requestedRunId);
    }

    @Transactional(rollbackFor = Exception.class)
    public ApiResult<Map<String, Object>> claimQuest(
            Long userId, String questCode, String requestedInstanceKey, String idempotencyKey) {
        if (sandboxService != null) {
            if (sandboxService.enabled()) return sandboxService.claimQuest(userId, questCode, idempotencyKey);
            if (sandboxService.unknownProfile()) throw new BizException(503, "WHEEL_RUNTIME_PROFILE_UNSUPPORTED");
        }
        requireUser(userId);
        String code = reference(questCode, "QUEST_CODE_REQUIRED");
        String instanceKey = reference(requestedInstanceKey, "QUEST_INSTANCE_KEY_REQUIRED");
        return executeOnce("QUEST_CLAIM", userId, idempotencyKey, code + "|" + instanceKey, () -> {
            QuestReward reward = mapper.lockClaimableQuest(userId, code);
            if (reward == null) return ApiResult.fail(409, "QUEST_NOT_CLAIMABLE");
            if (!instanceKey.equals(reward.instanceKey())) throw conflict("QUEST_INSTANCE_MISMATCH");
            boolean dayOne = "DAY_ONE".equalsIgnoreCase(reward.layer());
            if (dayOne) {
                List<DayOneQuestState> group = Optional.ofNullable(
                        mapper.lockDayOneGroup(userId, reward.instanceKey())).orElseGet(List::of);
                if (group.size() != 6 || group.stream().anyMatch(row ->
                        !Set.of("COMPLETED", "CLAIMABLE").contains(row.missionStatus()))) {
                    return ApiResult.fail(409, "DAY_ONE_GROUP_NOT_CLAIMABLE");
                }
                if (mapper.claimDayOneGroup(userId, reward.instanceKey()) != group.size()) {
                    throw conflict("QUEST_CLAIM_CONFLICT");
                }
            } else if (mapper.claimQuest(userId, reward.missionId(), reward.instanceKey()) != 1) {
                throw conflict("QUEST_CLAIM_CONFLICT");
            }
            GrowthRhythmSnapshot rhythm = growthRhythmFacade.snapshot();
            if (rhythm == null || rhythm.currentMonth() <= 0) throw conflict("H1_RHYTHM_UNAVAILABLE");
            BigDecimal multiplier = positiveOrOne(rhythm.questBonusMultiplier());
            BigDecimal baseReward = dayOne
                    ? DayOneTriRewardPolicy.effectiveDayOneReward(reward.triReward(),
                            reward.accountAgeHours() == null ? 0L : reward.accountAgeHours(),
                            reward.eligibilityHours() == null ? 72L : reward.eligibilityHours())
                    : reward.rewardNex();
            BigDecimal amount = positive(baseReward).multiply(multiplier)
                    .setScale(6, RoundingMode.DOWN);
            String rewardReference = dayOne ? "DAY_ONE" : code;
            creditNex(userId, "QUEST:" + rewardReference + ":" + userId + ":" + reward.instanceKey(),
                    "QUEST_REWARD", amount, "H3 quest claim");
            Map<String, Object> detail = linked(
                    "layer", reward.layer(), "rewardNex", amount, "multiplier", multiplier,
                    "rhythmMonth", rhythm.currentMonth(), "instanceKey", reward.instanceKey());
            audit("H3_QUEST_CLAIMED", "USER_MISSION", code, code, userId, detail);
            publish("MISSION", code, "quest.claimed", userId, attribution(userId), detail);
            return ApiResult.ok(linked("questId", code, "rewardNex", amount, "status", "CLAIMED",
                    "instanceKey", reward.instanceKey(),
                    "serverCanonical", true, "sourceEnvironment", "PRODUCTION", "runId", ""));
        });
    }

    private Map<String, Object> projectQuestRewardPolicy(Map<String, Object> source) {
        Map<String, Object> projected = new LinkedHashMap<>(source);
        if ("DAY_ONE".equalsIgnoreCase(String.valueOf(source.get("layer")))) {
            projected.put("rewardNex", BigDecimal.ZERO);
        }
        projected.remove("triReward");
        projected.remove("accountAgeHours");
        projected.remove("eligibilityHours");
        return projected;
    }

    private long numberValue(Object value, long fallback) {
        return value instanceof Number number ? number.longValue() : fallback;
    }

    /** Records a successful client share as a server-owned H3 fact. */
    @Transactional(rollbackFor = Exception.class)
    public ApiResult<Map<String, Object>> recordShareEvent(
            Long userId, ShareEventRequest request, String idempotencyKey) {
        if (request == null) throw new BizException(422, "SHARE_EVENT_REQUIRED");
        String eventId = reference(request.eventId(), "SHARE_EVENT_ID_INVALID");
        String channel = allowedShareReference(request.channel(), SHARE_CHANNELS, "SHARE_CHANNEL_INVALID");
        String surface = allowedShareReference(request.surface(), SHARE_SURFACES, "SHARE_SURFACE_INVALID");
        String sourceEnvironment = request.sourceEnvironment() == null
                ? "" : request.sourceEnvironment().trim().toUpperCase(Locale.ROOT);
        String runId = request.runId() == null ? "" : request.runId().trim();
        if (sandboxService != null) {
            if (sandboxService.enabled()) {
                if (!"SANDBOX".equals(sourceEnvironment) || !validRunId(runId)) {
                    throw new BizException(409, "SHARE_EVENT_SCOPE_MISMATCH");
                }
                return sandboxService.recordShareEvent(userId, runId, eventId, channel, surface,
                        reference(idempotencyKey, "SHARE_IDEMPOTENCY_KEY_REQUIRED"));
            }
            if (sandboxService.unknownProfile()) {
                throw new BizException(503, "WHEEL_RUNTIME_PROFILE_UNSUPPORTED");
            }
        }
        requireUser(userId);
        if (!"PRODUCTION".equals(sourceEnvironment) || !runId.isEmpty()) {
            throw new BizException(409, "SHARE_EVENT_SCOPE_MISMATCH");
        }
        if (questFactConsumer == null) throw new BizException(503, "SHARE_EVENT_UNAVAILABLE");
        String key = reference(idempotencyKey, "SHARE_IDEMPOTENCY_KEY_REQUIRED");
        String requestHash = eventId + "|" + channel + "|" + surface + "|PRODUCTION|";
        return executeOnce("SHARE_EVENT:PRODUCTION", userId, key, requestHash, () -> {
            QuestCompletionFactConsumer.CompletionResult completion = questFactConsumer.consume(
                    new QuestCompletionFactConsumer.QuestCompletionCommand(
                            "SHARE", eventId, userId, "invite_friend"));
            Map<String, Object> detail = linked(
                    "eventId", eventId, "channel", channel, "surface", surface,
                    "questCode", completion.questCode(), "replay", completion.replay());
            if (!completion.replay()) {
                audit("H3_SHARE_EVENT_RECORDED", "SHARE_EVENT", eventId,
                        "SHARE:" + eventId, userId, detail);
                publish("REFERRAL", eventId, "referral.invite_sent", userId, attribution(userId), detail);
            }
            return ApiResult.ok(productionResponse(linked(
                    "eventId", eventId, "questCode", completion.questCode(),
                    "status", completion.status(), "replay", completion.replay(),
                    "source", "nx_growth_quest_completion_fact")));
        });
    }

    @Transactional(rollbackFor = Exception.class)
    public ApiResult<Map<String, Object>> joinEvent(Long userId, String eventCode, String idempotencyKey) {
        requireCanonicalEngagementRuntime();
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
        requireCanonicalEngagementRuntime();
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
            } else if ("USDT".equals(rewardType)) {
                amount = positive(amount);
                creditUsdt(userId, "EVENT:" + code + ":" + userId, "EVENT_REWARD", amount, "H4 event reward");
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
        requireCanonicalEngagementRuntime();
        requireUser(userId);
        LocalDate today = LocalDate.now(H5_BUSINESS_ZONE);
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
            BigDecimal rewardNex = BigDecimal.valueOf(reward).setScale(6);
            creditNex(userId, "DAILY:" + userId + ":" + today, "DAILY_CHECK_IN",
                    rewardNex, "H5 daily check-in");
            Map<String, Object> detail = linked(
                    "checkInDate", today.toString(),
                    "baseNex", BigDecimal.valueOf(base).setScale(6),
                    "rewardNex", rewardNex,
                    "streakBonusNex", BigDecimal.valueOf(streakBonus).setScale(6),
                    "multiplier", multiplier, "streakDays", streak);
            audit("H5_DAILY_CHECKED_IN", "DAILY_CHECK_IN", today.toString(), today.toString(), userId, detail);
            Attribution attribution = attribution(userId);
            publish("DAILY_CHECK_IN", userId + ":" + today, "daily.checkin",
                    userId, attribution, detail);
            if (multiplier.compareTo(BigDecimal.ONE) > 0) {
                publish("DAILY_CHECK_IN", userId + ":" + today, "daily.lucky_triggered",
                        userId, attribution, linked("multiplier", multiplier));
            }
            return ApiResult.ok(productionResponse(detail));
        });
    }

    @Transactional(rollbackFor = Exception.class)
    public ApiResult<Map<String, Object>> useStreakSaver(Long userId, String idempotencyKey) {
        requireCanonicalEngagementRuntime();
        requireUser(userId);
        LocalDate today = LocalDate.now(H5_BUSINESS_ZONE);
        return executeOnce("DAILY_STREAK_SAVER", userId, idempotencyKey, today, () -> {
            mapper.ensureUserStreak(userId);
            StreakState before = mapper.lockStreak(userId);
            if (before == null) throw conflict("USER_STREAK_UNAVAILABLE");
            if (before.streakSavers() == null || before.streakSavers() <= 0) {
                return ApiResult.fail(409, "DAILY_STREAK_SAVER_EMPTY");
            }
            if (before.lastCheckInDate() == null
                    || !before.lastCheckInDate().isBefore(today.minusDays(1))) {
                return ApiResult.fail(409, "DAILY_STREAK_NOT_BROKEN");
            }
            int restored = Math.max(1, Math.min(30, Math.max(0, before.longestStreak())));
            LocalDate effectiveLast = today.minusDays(1);
            if (mapper.useStreakSaver(userId, restored, effectiveLast) != 1) {
                throw conflict("DAILY_STREAK_SAVER_CONFLICT");
            }
            Map<String, Object> detail = linked(
                    "restoredStreak", restored,
                    "streakSavers", before.streakSavers() - 1,
                    "effectiveLastCheckInDate", effectiveLast.toString());
            audit("H5_STREAK_SAVER_USED", "USER_STREAK", String.valueOf(userId),
                    "STREAK-SAVER:" + userId + ":" + today, userId, detail);
            publish("USER_STREAK", String.valueOf(userId), "daily.streak_restored",
                    userId, attribution(userId), detail);
            return ApiResult.ok(productionResponse(detail));
        });
    }

    @Transactional(rollbackFor = Exception.class)
    public ApiResult<Map<String, Object>> activateStreakPowerUp(
            Long userId, Long powerUpId, String idempotencyKey) {
        requireCanonicalEngagementRuntime();
        requireUser(userId);
        if (powerUpId == null || powerUpId <= 0) {
            return ApiResult.fail(422, "POWER_UP_ID_REQUIRED");
        }
        return executeOnce("DAILY_POWER_UP_ACTIVATE", userId, idempotencyKey, powerUpId, () -> {
            StreakPowerUp row = mapper.lockActivatablePowerUp(userId, powerUpId);
            if (row == null) return ApiResult.fail(409, "DAILY_POWER_UP_NOT_ACTIVATABLE");
            if (mapper.activatePowerUp(userId, row) != 1) {
                throw conflict("DAILY_POWER_UP_ACTIVATION_CONFLICT");
            }
            if (StringUtils.hasText(row.badgeCode())) {
                grantBadge(userId, row.badgeCode(), "DAILY_POWER_UP_BADGE_GRANT_CONFLICT");
            }
            Map<String, Object> detail = linked(
                    "powerUpId", row.powerUpId(),
                    "powerUpCode", row.powerUpCode(),
                    "badgeCode", row.badgeCode(),
                    "status", "ACTIVATED");
            String sourceId = userId + ":" + row.powerUpCode();
            audit("H5_POWER_UP_ACTIVATED", "USER_STREAK_POWER_UP", sourceId,
                    sourceId, userId, detail);
            publish("USER_STREAK_POWER_UP", sourceId, "daily.power_up_activated",
                    userId, attribution(userId), detail);
            return ApiResult.ok(productionResponse(detail));
        });
    }

    @Transactional(rollbackFor = Exception.class)
    public ApiResult<Map<String, Object>> claimDailyMilestone(
            Long userId, Long milestoneId, String idempotencyKey) {
        if (sandboxService != null) {
            if (sandboxService.enabled()) return sandboxService.claimMilestone(userId, milestoneId, idempotencyKey);
            if (sandboxService.unknownProfile()) throw new BizException(503, "WHEEL_RUNTIME_PROFILE_UNSUPPORTED");
        }
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
            return ApiResult.ok(productionResponse(linked(
                    "milestoneId", row.milestoneId(), "milestoneDay", row.milestoneDay(),
                    "rewardType", rewardType, "rewardAmount", amount, "badgeCode", row.badgeCode(),
                    "spinTickets", tickets)));
        });
    }

    @Transactional(rollbackFor = Exception.class)
    public ApiResult<Map<String, Object>> evaluateEarningMilestones(Long userId, String idempotencyKey) {
        return evaluateEarningMilestones(userId, idempotencyKey, null);
    }

    @Transactional(rollbackFor = Exception.class)
    public ApiResult<Map<String, Object>> evaluateEarningMilestones(Long userId, String idempotencyKey, String requestedMilestoneId) {
        requireCanonicalEngagementRuntime();
        requireUser(userId);
        String target = requestedMilestoneId == null ? null : reference(requestedMilestoneId, "EARNING_MILESTONE_ID_REQUIRED");
        return executeOnce("EARNING_MILESTONE_EVALUATE", userId, idempotencyKey, target == null ? "eligible-rules" : "milestone:" + target, () -> {
            List<EarningMilestone> eligible = mapper.lockEligibleEarningMilestones(userId);
            List<Map<String, Object>> fired = new ArrayList<>();
            Attribution attribution = attribution(userId);
            List<EarningMilestone> ordered = eligible == null ? List.of() : eligible;
            // Automatic evaluation advances one rung; an explicit claim must honor the selected reward.
            List<EarningMilestone> selected = ordered.stream()
                    .filter(row -> target == null || target.equals(row.milestoneId())).limit(1).toList();
            if (target != null && selected.isEmpty()) throw conflict("EARNING_MILESTONE_NOT_CLAIMABLE");
            for (EarningMilestone row : selected) {
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
            return ApiResult.ok(productionResponse(linked("fired", fired, "count", fired.size())));
        });
    }

    @Transactional(rollbackFor = Exception.class)
    public ApiResult<Map<String, Object>> claimVoucher(
            Long userId, String voucherId, String surface, String idempotencyKey) {
        return claimVoucher(userId, voucherId, surface, idempotencyKey, null);
    }

    @Transactional(rollbackFor = Exception.class)
    public ApiResult<Map<String, Object>> claimVoucher(
            Long userId, String voucherId, String surface, String idempotencyKey,
            String requestedRunId) {
        if (voucherSandboxService.isPresent()) {
            if (voucherSandboxService.get().enabled()) {
                return voucherSandboxService.get().claimVoucher(
                        userId, voucherId, surface, idempotencyKey, requestedRunId);
            }
            if (voucherSandboxService.get().unknownProfile()) {
                throw new BizException(503, "WHEEL_RUNTIME_PROFILE_UNSUPPORTED");
            }
        }
        requireCanonicalEngagementRuntime();
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
                    "replay", grant.replayed(), "serverCanonical", true,
                    "source", "nx_growth_voucher + nx_growth_voucher_grant",
                    "sourceEnvironment", "PRODUCTION", "runId", ""));
        });
    }

    private void requireUser(Long userId) {
        Long activeUser = null;
        if (userId != null && userId > 0) {
            var resolved = UserAuthEnvironment.resolve(environment);
            if (environment != null && resolved.isEmpty()) {
                throw new BizException(503, "USER_AUTH_ENVIRONMENT_UNSUPPORTED");
            }
            activeUser = resolved.orElse(UserAuthEnvironment.PRODUCTION) == UserAuthEnvironment.SANDBOX
                    ? mapper.lockActiveSandboxUser(userId)
                    : mapper.lockActiveUser(userId);
        }
        if (activeUser == null) {
            throw new BizException(404, "USER_NOT_FOUND_OR_INACTIVE");
        }
    }

    private void requireReadableUser(Long userId) {
        if (userId == null || userId <= 0 || mapper.findActiveUser(userId) == null) {
            throw new BizException(404, "USER_NOT_FOUND_OR_INACTIVE");
        }
    }

    /**
     * These H3-H7 operations still use canonical tables and have no run-scoped
     * sandbox implementation.  They must never silently fall back to those
     * tables when a sandbox or mixed runtime is active.
     */
    private void requireCanonicalEngagementRuntime() {
        if (sandboxService == null) return;
        if (sandboxService.enabled()) throw new BizException(503, "GROWTH_SANDBOX_SCOPE_UNAVAILABLE");
        if (sandboxService.unknownProfile()) throw new BizException(503, "WHEEL_RUNTIME_PROFILE_UNSUPPORTED");
    }

    private List<Map<String, Object>> safeList(List<Map<String, Object>> value) {
        return value == null ? List.of() : value;
    }

    private boolean truthy(Object value) {
        if (value instanceof Boolean flag) {
            return flag;
        }
        if (value instanceof Number number) {
            return number.intValue() != 0;
        }
        return "true".equalsIgnoreCase(String.valueOf(value))
                || "1".equals(String.valueOf(value));
    }

    private long numberValue(Object value) {
        if (value instanceof Number number) return Math.max(0L, number.longValue());
        try { return value == null ? 0L : Math.max(0L, Long.parseLong(value.toString())); }
        catch (NumberFormatException ignored) { return 0L; }
    }

    private long boundedCadence(Map<String, Object> row, String key, long fallback, long minimum, long maximum) {
        Object value = row.get(key);
        long parsed;
        if (!row.containsKey(key)) {
            parsed = fallback;
        } else if (value instanceof Number number) {
            parsed = number.longValue();
        } else {
            try {
                parsed = value == null ? 0L : Long.parseLong(value.toString());
            } catch (NumberFormatException ex) {
                throw new BizException(503, "VOUCHER_CADENCE_INVALID");
            }
        }
        if (parsed < minimum || parsed > maximum) throw new BizException(503, "VOUCHER_CADENCE_INVALID");
        return parsed;
    }

    private long safeNextEligibleAt(long lastSeenAt, long cooldownHours) {
        try {
            return Math.addExact(lastSeenAt, Math.multiplyExact(cooldownHours, 3_600_000L));
        } catch (ArithmeticException ex) {
            throw new BizException(503, "VOUCHER_CADENCE_INVALID");
        }
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
        if (earningsReleaseService == null) {
            if (mapper.creditWalletNex(userId, amount) != 1) throw conflict("USER_WALLET_CONFLICT");
        } else {
            creditReleasedReward(userId, bizType, bizNo, "NEX", amount,
                    "GROWTH:" + bizNo + ":NEX");
        }
        if (mapper.insertNexLedger(userId, bizNo, bizType, amount, before.add(amount), remark) != 1) {
            throw conflict("REWARD_LEDGER_CONFLICT");
        }
    }

    private void creditUsdt(Long userId, String bizNo, String bizType, BigDecimal amount, String remark) {
        if (amount.signum() <= 0) throw conflict("REWARD_AMOUNT_INVALID");
        requireHealthyCoverage();
        BigDecimal before = mapper.lockWalletUsdt(userId);
        if (before == null) throw conflict("USER_WALLET_NOT_FOUND");
        if (earningsReleaseService == null) {
            if (mapper.creditWalletUsdt(userId, amount) != 1) throw conflict("USER_WALLET_CONFLICT");
        } else {
            creditReleasedReward(userId, bizType, bizNo, "USDT", amount,
                    "GROWTH:" + bizNo + ":USDT");
        }
        if (mapper.insertUsdtLedger(userId, bizNo, bizType, amount, before.add(amount), remark) != 1) {
            throw conflict("REWARD_LEDGER_CONFLICT");
        }
    }

    private void creditReleasedReward(Long userId, String bizType, String bizNo, String asset,
                                      BigDecimal amount, String idempotencyKey) {
        String[] profiles = environment == null ? new String[0] : environment.getActiveProfiles();
        if (FundsSandboxProfileGuard.isStrictTestProfile(profiles)) {
            earningsReleaseService.creditReward(userId, "MOCK_" + bizType, bizNo, asset, amount,
                    "SANDBOX", idempotencyKey);
            return;
        }
        if (profiles.length != 0
                && !FundsSandboxProfileGuard.isStrictDevelopmentProfile(profiles)
                && !FundsSandboxProfileGuard.isStrictProductionProfile(profiles)) {
            throw new BizException(503, "EARNINGS_RELEASE_PROFILE_INVALID");
        }
        earningsReleaseService.creditReward(userId, bizType, bizNo, asset, amount, idempotencyKey);
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
        if (!Set.of("USDT", "NEX", "SPIN", "BADGE").contains(normalized)) {
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

    private String shareReference(String value, String error) {
        if (!StringUtils.hasText(value) || value.length() > 32
                || !value.matches("^[A-Za-z0-9._:-]+$")) {
            throw new BizException(422, error);
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private String allowedShareReference(String value, Set<String> allowed, String error) {
        String normalized = shareReference(value, error);
        if (!allowed.contains(normalized)) throw new BizException(422, error);
        return normalized;
    }

    private boolean validRunId(String value) {
        return value.matches("^[A-Za-z0-9][A-Za-z0-9._-]{7,95}$");
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

    private Map<String, Object> productionResponse(Map<String, Object> payload) {
        Map<String, Object> response = new LinkedHashMap<>(payload);
        response.put("serverCanonical", true);
        response.put("sourceEnvironment", "PRODUCTION");
        response.put("runId", "");
        return response;
    }

    public record ShareEventRequest(
            String eventId, String channel, String surface,
            String sourceEnvironment, String runId) {
    }
}
