package ffdd.mission.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import ffdd.common.api.PageResult;
import ffdd.common.exception.BizException;
import ffdd.mission.domain.EventQuest;
import ffdd.mission.domain.MonthlyChallenge;
import ffdd.mission.domain.PointsLedger;
import ffdd.mission.domain.UserEventQuest;
import ffdd.mission.domain.UserMonthlyChallenge;
import ffdd.mission.dto.CampaignClaimResponse;
import ffdd.mission.dto.EventQuestItemResponse;
import ffdd.mission.dto.EventQuestRequest;
import ffdd.mission.dto.EventQuestUpdateRequest;
import ffdd.mission.dto.MissionProgressUpdateRequest;
import ffdd.mission.dto.MonthlyChallengeItemResponse;
import ffdd.mission.dto.MonthlyChallengeRequest;
import ffdd.mission.dto.MonthlyChallengeUpdateRequest;
import ffdd.mission.mapper.EventQuestMapper;
import ffdd.mission.mapper.MonthlyChallengeMapper;
import ffdd.mission.mapper.PointsLedgerMapper;
import ffdd.mission.mapper.UserEventQuestMapper;
import ffdd.mission.mapper.UserMonthlyChallengeMapper;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class MissionCampaignService {
    private static final int MAX_PAGE_SIZE = 100;
    private static final String STATUS_LOCKED = "LOCKED";
    private static final String STATUS_UNLOCKED = "UNLOCKED";
    private static final String STATUS_CLAIMED = "CLAIMED";
    private static final String STATUS_ALREADY_CLAIMED = "ALREADY_CLAIMED";
    private static final String STATUS_EXPIRED = "EXPIRED";
    private static final String REWARD_TYPE_POINTS = "POINTS";
    private static final Pattern CODE_PATTERN = Pattern.compile("[A-Z0-9_]{1,64}");

    private final MonthlyChallengeMapper monthlyChallengeMapper;
    private final UserMonthlyChallengeMapper userMonthlyChallengeMapper;
    private final EventQuestMapper eventQuestMapper;
    private final UserEventQuestMapper userEventQuestMapper;
    private final PointsLedgerMapper pointsLedgerMapper;

    public MissionCampaignService(
            MonthlyChallengeMapper monthlyChallengeMapper,
            UserMonthlyChallengeMapper userMonthlyChallengeMapper,
            EventQuestMapper eventQuestMapper,
            UserEventQuestMapper userEventQuestMapper,
            PointsLedgerMapper pointsLedgerMapper) {
        this.monthlyChallengeMapper = monthlyChallengeMapper;
        this.userMonthlyChallengeMapper = userMonthlyChallengeMapper;
        this.eventQuestMapper = eventQuestMapper;
        this.userEventQuestMapper = userEventQuestMapper;
        this.pointsLedgerMapper = pointsLedgerMapper;
    }

    public List<MonthlyChallengeItemResponse> listMonthly(Long userId) {
        requireUserId(userId);
        Map<String, UserMonthlyChallenge> userRecords = userMonthlyChallengeMapper.selectList(
                        new LambdaQueryWrapper<UserMonthlyChallenge>()
                                .eq(UserMonthlyChallenge::getUserId, userId)
                                .eq(UserMonthlyChallenge::getIsDeleted, 0))
                .stream()
                .collect(Collectors.toMap(UserMonthlyChallenge::getChallengeCode, Function.identity(), (left, right) -> left));
        int monthsSinceRegistration = monthsSinceRegistration(userId);
        return monthlyChallengeMapper.selectList(activeMonthlyQuery(monthsSinceRegistration)).stream()
                .map(challenge -> toMonthlyResponse(challenge, userRecords.get(challenge.getChallengeCode())))
                .toList();
    }

    public List<EventQuestItemResponse> listEvents(Long userId) {
        requireUserId(userId);
        LocalDateTime now = LocalDateTime.now();
        Map<String, UserEventQuest> userRecords = userEventQuestMapper.selectList(
                        new LambdaQueryWrapper<UserEventQuest>()
                                .eq(UserEventQuest::getUserId, userId)
                                .eq(UserEventQuest::getIsDeleted, 0))
                .stream()
                .collect(Collectors.toMap(UserEventQuest::getQuestCode, Function.identity(), (left, right) -> left));
        return eventQuestMapper.selectList(activeEventQuery(now)).stream()
                .map(quest -> toEventResponse(quest, userRecords.get(quest.getQuestCode()), now))
                .toList();
    }

    @Transactional(rollbackFor = Exception.class)
    public CampaignClaimResponse claimMonthly(Long userId, String challengeCode) {
        requireUserId(userId);
        MonthlyChallenge challenge = requireMonthly(challengeCode, userId);
        UserMonthlyChallenge existing = findUserMonthly(userId, challenge.getChallengeCode());
        if (isClaimed(existing)) {
            return monthlyClaimResponse(userId, challenge, existing, false, STATUS_ALREADY_CLAIMED, 0, totalPoints(userId));
        }
        int progress = progress(existing);
        if (progress < targetValue(challenge.getTargetValue())) {
            return monthlyClaimResponse(userId, challenge, existing, false, STATUS_LOCKED, 0, totalPoints(userId));
        }
        LocalDateTime now = LocalDateTime.now();
        int points = pointsReward(challenge.getRewardType(), challenge.getRewardAmount());
        int total = totalPoints(userId);
        UserMonthlyChallenge claim = existing == null ? new UserMonthlyChallenge() : existing;
        claim.setUserId(userId);
        claim.setChallengeId(challenge.getId());
        claim.setChallengeCode(challenge.getChallengeCode());
        claim.setProgressValue(progress);
        claim.setClaimStatus(STATUS_CLAIMED);
        claim.setRewardType(challenge.getRewardType());
        claim.setRewardAmount(challenge.getRewardAmount());
        claim.setClaimedAt(now);
        claim.setIsDeleted(0);
        upsertMonthlyClaim(claim, existing);
        insertPointsLedgerIfNeeded(userId, "MONTHLY-CHALLENGE-" + challenge.getChallengeCode() + "-" + userId,
                "MONTHLY_CHALLENGE", points, total + points);
        return monthlyClaimResponse(userId, challenge, claim, true, STATUS_CLAIMED, points, total + points);
    }

    @Transactional(rollbackFor = Exception.class)
    public CampaignClaimResponse claimEvent(Long userId, String questCode) {
        requireUserId(userId);
        EventQuest quest = requireEvent(questCode);
        LocalDateTime now = LocalDateTime.now();
        if (isExpired(quest, now)) {
            return eventClaimResponse(userId, quest, findUserEvent(userId, quest.getQuestCode()), false, STATUS_EXPIRED, 0, totalPoints(userId));
        }
        UserEventQuest existing = findUserEvent(userId, quest.getQuestCode());
        if (isClaimed(existing)) {
            return eventClaimResponse(userId, quest, existing, false, STATUS_ALREADY_CLAIMED, 0, totalPoints(userId));
        }
        int progress = progress(existing);
        if (progress < targetValue(quest.getTargetValue())) {
            return eventClaimResponse(userId, quest, existing, false, STATUS_LOCKED, 0, totalPoints(userId));
        }
        int points = pointsReward(quest.getRewardType(), quest.getRewardAmount());
        int total = totalPoints(userId);
        UserEventQuest claim = existing == null ? new UserEventQuest() : existing;
        claim.setUserId(userId);
        claim.setQuestId(quest.getId());
        claim.setQuestCode(quest.getQuestCode());
        claim.setProgressValue(progress);
        claim.setClaimStatus(STATUS_CLAIMED);
        claim.setRewardType(quest.getRewardType());
        claim.setRewardAmount(quest.getRewardAmount());
        claim.setClaimedAt(now);
        claim.setIsDeleted(0);
        upsertEventClaim(claim, existing);
        insertPointsLedgerIfNeeded(userId, "EVENT-QUEST-" + quest.getQuestCode() + "-" + userId,
                "EVENT_QUEST", points, total + points);
        return eventClaimResponse(userId, quest, claim, true, STATUS_CLAIMED, points, total + points);
    }

    public PageResult<MonthlyChallenge> pageMonthlyOps(String status, long pageNum, long pageSize) {
        LambdaQueryWrapper<MonthlyChallenge> wrapper = new LambdaQueryWrapper<MonthlyChallenge>()
                .eq(MonthlyChallenge::getIsDeleted, 0);
        if (StringUtils.hasText(status)) {
            wrapper.eq(MonthlyChallenge::getStatus, normalizeStatus(status));
        }
        wrapper.orderByAsc(MonthlyChallenge::getSortOrder).orderByAsc(MonthlyChallenge::getId);
        Page<MonthlyChallenge> page = monthlyChallengeMapper.selectPage(
                new Page<>(normalizePageNum(pageNum), normalizePageSize(pageSize)), wrapper);
        return new PageResult<>(page.getTotal(), page.getCurrent(), page.getSize(), page.getRecords());
    }

    public PageResult<EventQuest> pageEventOps(String status, long pageNum, long pageSize) {
        LambdaQueryWrapper<EventQuest> wrapper = new LambdaQueryWrapper<EventQuest>()
                .eq(EventQuest::getIsDeleted, 0);
        if (StringUtils.hasText(status)) {
            wrapper.eq(EventQuest::getStatus, normalizeStatus(status));
        }
        wrapper.orderByAsc(EventQuest::getSortOrder).orderByAsc(EventQuest::getId);
        Page<EventQuest> page = eventQuestMapper.selectPage(
                new Page<>(normalizePageNum(pageNum), normalizePageSize(pageSize)), wrapper);
        return new PageResult<>(page.getTotal(), page.getCurrent(), page.getSize(), page.getRecords());
    }

    @Transactional(rollbackFor = Exception.class)
    public MonthlyChallenge createMonthly(MonthlyChallengeRequest request) {
        validateMonthlyWindow(request.getMonthsFrom(), request.getMonthsTo());
        String code = normalizeCode(request.getChallengeCode(), "challenge code");
        ensureMonthlyCodeAvailable(code, null);
        MonthlyChallenge challenge = new MonthlyChallenge();
        challenge.setChallengeCode(code);
        challenge.setChallengeName(requiredText(request.getChallengeName(), "challengeName", 128));
        challenge.setDescription(trimToNull(request.getDescription(), 512));
        challenge.setTheme(trimToNull(request.getTheme(), 64));
        challenge.setMonthsFrom(defaultInt(request.getMonthsFrom(), 0));
        challenge.setMonthsTo(defaultInt(request.getMonthsTo(), 999));
        challenge.setTargetType(normalizeCode(request.getTargetType(), "target type"));
        challenge.setTargetValue(targetValue(request.getTargetValue()));
        challenge.setRewardType(normalizeRewardType(request.getRewardType()));
        challenge.setRewardAmount(requirePositiveAmount(request.getRewardAmount()));
        challenge.setRewardName(requiredText(request.getRewardName(), "rewardName", 128));
        challenge.setBadgeAchievementCode(normalizeOptionalCode(request.getBadgeAchievementCode()));
        challenge.setSortOrder(defaultInt(request.getSortOrder(), 0));
        challenge.setStatus(normalizeStatus(request.getStatus()));
        challenge.setIsDeleted(0);
        monthlyChallengeMapper.insert(challenge);
        return challenge;
    }

    @Transactional(rollbackFor = Exception.class)
    public MonthlyChallenge updateMonthly(Long id, MonthlyChallengeUpdateRequest request) {
        MonthlyChallenge challenge = requireMonthlyById(id);
        MonthlyChallenge patch = new MonthlyChallenge();
        patch.setId(id);
        if (request.getChallengeName() != null) {
            patch.setChallengeName(requiredText(request.getChallengeName(), "challengeName", 128));
        }
        if (request.getDescription() != null) {
            patch.setDescription(trimToNull(request.getDescription(), 512));
        }
        if (request.getTheme() != null) {
            patch.setTheme(trimToNull(request.getTheme(), 64));
        }
        Integer monthsFrom = request.getMonthsFrom() == null ? challenge.getMonthsFrom() : request.getMonthsFrom();
        Integer monthsTo = request.getMonthsTo() == null ? challenge.getMonthsTo() : request.getMonthsTo();
        validateMonthlyWindow(monthsFrom, monthsTo);
        if (request.getMonthsFrom() != null) {
            patch.setMonthsFrom(request.getMonthsFrom());
        }
        if (request.getMonthsTo() != null) {
            patch.setMonthsTo(request.getMonthsTo());
        }
        if (request.getTargetType() != null) {
            patch.setTargetType(normalizeCode(request.getTargetType(), "target type"));
        }
        if (request.getTargetValue() != null) {
            patch.setTargetValue(targetValue(request.getTargetValue()));
        }
        if (request.getRewardType() != null) {
            patch.setRewardType(normalizeRewardType(request.getRewardType()));
        }
        if (request.getRewardAmount() != null) {
            patch.setRewardAmount(requirePositiveAmount(request.getRewardAmount()));
        }
        if (request.getRewardName() != null) {
            patch.setRewardName(requiredText(request.getRewardName(), "rewardName", 128));
        }
        if (request.getBadgeAchievementCode() != null) {
            patch.setBadgeAchievementCode(normalizeOptionalCode(request.getBadgeAchievementCode()));
        }
        if (request.getSortOrder() != null) {
            patch.setSortOrder(request.getSortOrder());
        }
        if (request.getStatus() != null) {
            patch.setStatus(normalizeStatus(request.getStatus()));
        }
        monthlyChallengeMapper.updateById(patch);
        return requireMonthlyById(id);
    }

    @Transactional(rollbackFor = Exception.class)
    public void deleteMonthly(Long id) {
        requireMonthlyById(id);
        MonthlyChallenge patch = new MonthlyChallenge();
        patch.setId(id);
        patch.setStatus(0);
        patch.setIsDeleted(1);
        monthlyChallengeMapper.updateById(patch);
    }

    @Transactional(rollbackFor = Exception.class)
    public EventQuest createEvent(EventQuestRequest request) {
        validateEventWindow(request.getStartsAt(), request.getEndsAt());
        String code = normalizeCode(request.getQuestCode(), "quest code");
        ensureEventCodeAvailable(code, null);
        EventQuest quest = new EventQuest();
        quest.setQuestCode(code);
        quest.setQuestName(requiredText(request.getQuestName(), "questName", 128));
        quest.setDescription(trimToNull(request.getDescription(), 512));
        quest.setStartsAt(request.getStartsAt());
        quest.setEndsAt(request.getEndsAt());
        quest.setTargetType(normalizeCode(request.getTargetType(), "target type"));
        quest.setTargetValue(targetValue(request.getTargetValue()));
        quest.setRewardType(normalizeRewardType(request.getRewardType()));
        quest.setRewardAmount(requirePositiveAmount(request.getRewardAmount()));
        quest.setRewardName(requiredText(request.getRewardName(), "rewardName", 128));
        quest.setBadgeAchievementCode(normalizeOptionalCode(request.getBadgeAchievementCode()));
        quest.setSortOrder(defaultInt(request.getSortOrder(), 0));
        quest.setStatus(normalizeStatus(request.getStatus()));
        quest.setIsDeleted(0);
        eventQuestMapper.insert(quest);
        return quest;
    }

    @Transactional(rollbackFor = Exception.class)
    public EventQuest updateEvent(Long id, EventQuestUpdateRequest request) {
        EventQuest quest = requireEventById(id);
        EventQuest patch = new EventQuest();
        patch.setId(id);
        LocalDateTime startsAt = request.getStartsAt() == null ? quest.getStartsAt() : request.getStartsAt();
        LocalDateTime endsAt = request.getEndsAt() == null ? quest.getEndsAt() : request.getEndsAt();
        validateEventWindow(startsAt, endsAt);
        if (request.getQuestName() != null) {
            patch.setQuestName(requiredText(request.getQuestName(), "questName", 128));
        }
        if (request.getDescription() != null) {
            patch.setDescription(trimToNull(request.getDescription(), 512));
        }
        if (request.getStartsAt() != null) {
            patch.setStartsAt(request.getStartsAt());
        }
        if (request.getEndsAt() != null) {
            patch.setEndsAt(request.getEndsAt());
        }
        if (request.getTargetType() != null) {
            patch.setTargetType(normalizeCode(request.getTargetType(), "target type"));
        }
        if (request.getTargetValue() != null) {
            patch.setTargetValue(targetValue(request.getTargetValue()));
        }
        if (request.getRewardType() != null) {
            patch.setRewardType(normalizeRewardType(request.getRewardType()));
        }
        if (request.getRewardAmount() != null) {
            patch.setRewardAmount(requirePositiveAmount(request.getRewardAmount()));
        }
        if (request.getRewardName() != null) {
            patch.setRewardName(requiredText(request.getRewardName(), "rewardName", 128));
        }
        if (request.getBadgeAchievementCode() != null) {
            patch.setBadgeAchievementCode(normalizeOptionalCode(request.getBadgeAchievementCode()));
        }
        if (request.getSortOrder() != null) {
            patch.setSortOrder(request.getSortOrder());
        }
        if (request.getStatus() != null) {
            patch.setStatus(normalizeStatus(request.getStatus()));
        }
        eventQuestMapper.updateById(patch);
        return requireEventById(id);
    }

    @Transactional(rollbackFor = Exception.class)
    public void deleteEvent(Long id) {
        requireEventById(id);
        EventQuest patch = new EventQuest();
        patch.setId(id);
        patch.setStatus(0);
        patch.setIsDeleted(1);
        eventQuestMapper.updateById(patch);
    }

    @Transactional(rollbackFor = Exception.class)
    public MonthlyChallengeItemResponse updateMonthlyProgress(Long userId, String challengeCode, MissionProgressUpdateRequest request) {
        requireUserId(userId);
        MonthlyChallenge challenge = requireMonthly(challengeCode, userId);
        UserMonthlyChallenge record = findUserMonthly(userId, challenge.getChallengeCode());
        if (record == null) {
            record = new UserMonthlyChallenge();
            record.setUserId(userId);
            record.setChallengeId(challenge.getId());
            record.setChallengeCode(challenge.getChallengeCode());
            record.setClaimStatus(STATUS_LOCKED);
            record.setRewardType(challenge.getRewardType());
            record.setRewardAmount(challenge.getRewardAmount());
            record.setIsDeleted(0);
            record.setProgressValue(request.getProgressValue());
            userMonthlyChallengeMapper.insert(record);
        } else {
            record.setProgressValue(request.getProgressValue());
            if (!STATUS_CLAIMED.equals(record.getClaimStatus())) {
                record.setClaimStatus(request.getProgressValue() >= targetValue(challenge.getTargetValue()) ? STATUS_UNLOCKED : STATUS_LOCKED);
            }
            userMonthlyChallengeMapper.updateById(record);
        }
        return toMonthlyResponse(challenge, record);
    }

    @Transactional(rollbackFor = Exception.class)
    public EventQuestItemResponse updateEventProgress(Long userId, String questCode, MissionProgressUpdateRequest request) {
        requireUserId(userId);
        EventQuest quest = requireEvent(questCode);
        UserEventQuest record = findUserEvent(userId, quest.getQuestCode());
        if (record == null) {
            record = new UserEventQuest();
            record.setUserId(userId);
            record.setQuestId(quest.getId());
            record.setQuestCode(quest.getQuestCode());
            record.setClaimStatus(STATUS_LOCKED);
            record.setRewardType(quest.getRewardType());
            record.setRewardAmount(quest.getRewardAmount());
            record.setIsDeleted(0);
            record.setProgressValue(request.getProgressValue());
            userEventQuestMapper.insert(record);
        } else {
            record.setProgressValue(request.getProgressValue());
            if (!STATUS_CLAIMED.equals(record.getClaimStatus())) {
                record.setClaimStatus(request.getProgressValue() >= targetValue(quest.getTargetValue()) ? STATUS_UNLOCKED : STATUS_LOCKED);
            }
            userEventQuestMapper.updateById(record);
        }
        return toEventResponse(quest, record, LocalDateTime.now());
    }

    private LambdaQueryWrapper<MonthlyChallenge> activeMonthlyQuery(int monthsSinceRegistration) {
        return new LambdaQueryWrapper<MonthlyChallenge>()
                .eq(MonthlyChallenge::getStatus, 1)
                .eq(MonthlyChallenge::getIsDeleted, 0)
                .le(MonthlyChallenge::getMonthsFrom, monthsSinceRegistration)
                .ge(MonthlyChallenge::getMonthsTo, monthsSinceRegistration)
                .orderByAsc(MonthlyChallenge::getSortOrder)
                .orderByAsc(MonthlyChallenge::getId);
    }

    private LambdaQueryWrapper<EventQuest> activeEventQuery(LocalDateTime now) {
        return new LambdaQueryWrapper<EventQuest>()
                .eq(EventQuest::getStatus, 1)
                .eq(EventQuest::getIsDeleted, 0)
                .and(wrapper -> wrapper.isNull(EventQuest::getStartsAt).or().le(EventQuest::getStartsAt, now))
                .and(wrapper -> wrapper.isNull(EventQuest::getEndsAt).or().ge(EventQuest::getEndsAt, now))
                .orderByAsc(EventQuest::getSortOrder)
                .orderByAsc(EventQuest::getId);
    }

    private MonthlyChallenge requireMonthly(String challengeCode, Long userId) {
        int monthsSinceRegistration = monthsSinceRegistration(userId);
        MonthlyChallenge challenge = monthlyChallengeMapper.selectOne(new LambdaQueryWrapper<MonthlyChallenge>()
                .eq(MonthlyChallenge::getChallengeCode, normalizeCode(challengeCode, "challenge code"))
                .eq(MonthlyChallenge::getStatus, 1)
                .eq(MonthlyChallenge::getIsDeleted, 0)
                .le(MonthlyChallenge::getMonthsFrom, monthsSinceRegistration)
                .ge(MonthlyChallenge::getMonthsTo, monthsSinceRegistration));
        if (challenge == null) {
            throw new BizException("Monthly challenge not found");
        }
        return challenge;
    }

    private MonthlyChallenge requireMonthlyById(Long id) {
        MonthlyChallenge challenge = monthlyChallengeMapper.selectOne(new LambdaQueryWrapper<MonthlyChallenge>()
                .eq(MonthlyChallenge::getId, requireId(id, "Monthly challenge id"))
                .eq(MonthlyChallenge::getIsDeleted, 0));
        if (challenge == null) {
            throw new BizException("Monthly challenge not found");
        }
        return challenge;
    }

    private EventQuest requireEvent(String questCode) {
        EventQuest quest = eventQuestMapper.selectOne(new LambdaQueryWrapper<EventQuest>()
                .eq(EventQuest::getQuestCode, normalizeCode(questCode, "quest code"))
                .eq(EventQuest::getStatus, 1)
                .eq(EventQuest::getIsDeleted, 0));
        if (quest == null) {
            throw new BizException("Event quest not found");
        }
        return quest;
    }

    private EventQuest requireEventById(Long id) {
        EventQuest quest = eventQuestMapper.selectOne(new LambdaQueryWrapper<EventQuest>()
                .eq(EventQuest::getId, requireId(id, "Event quest id"))
                .eq(EventQuest::getIsDeleted, 0));
        if (quest == null) {
            throw new BizException("Event quest not found");
        }
        return quest;
    }

    private UserMonthlyChallenge findUserMonthly(Long userId, String challengeCode) {
        return userMonthlyChallengeMapper.selectOne(new LambdaQueryWrapper<UserMonthlyChallenge>()
                .eq(UserMonthlyChallenge::getUserId, userId)
                .eq(UserMonthlyChallenge::getChallengeCode, challengeCode)
                .eq(UserMonthlyChallenge::getIsDeleted, 0));
    }

    private UserEventQuest findUserEvent(Long userId, String questCode) {
        return userEventQuestMapper.selectOne(new LambdaQueryWrapper<UserEventQuest>()
                .eq(UserEventQuest::getUserId, userId)
                .eq(UserEventQuest::getQuestCode, questCode)
                .eq(UserEventQuest::getIsDeleted, 0));
    }

    private void upsertMonthlyClaim(UserMonthlyChallenge claim, UserMonthlyChallenge existing) {
        try {
            if (existing == null) {
                userMonthlyChallengeMapper.insert(claim);
            } else {
                userMonthlyChallengeMapper.updateById(claim);
            }
        } catch (DuplicateKeyException ignored) {
            // Another request won the same claim race. Ledger idempotency still protects points.
        }
    }

    private void upsertEventClaim(UserEventQuest claim, UserEventQuest existing) {
        try {
            if (existing == null) {
                userEventQuestMapper.insert(claim);
            } else {
                userEventQuestMapper.updateById(claim);
            }
        } catch (DuplicateKeyException ignored) {
            // Another request won the same claim race. Ledger idempotency still protects points.
        }
    }

    private void insertPointsLedgerIfNeeded(Long userId, String bizNo, String bizType, int points, int balanceAfter) {
        if (points <= 0) {
            return;
        }
        PointsLedger existing = pointsLedgerMapper.selectOne(new LambdaQueryWrapper<PointsLedger>()
                .eq(PointsLedger::getBizNo, bizNo)
                .eq(PointsLedger::getIsDeleted, 0));
        if (existing != null) {
            return;
        }
        PointsLedger ledger = new PointsLedger();
        ledger.setUserId(userId);
        ledger.setBizNo(bizNo);
        ledger.setBizType(bizType);
        ledger.setPoints(points);
        ledger.setBalanceAfter(balanceAfter);
        ledger.setIsDeleted(0);
        try {
            pointsLedgerMapper.insert(ledger);
        } catch (DuplicateKeyException ignored) {
            // Unique biz_no is the final idempotency guard.
        }
    }

    private MonthlyChallengeItemResponse toMonthlyResponse(MonthlyChallenge challenge, UserMonthlyChallenge userRecord) {
        int target = targetValue(challenge.getTargetValue());
        int progress = progress(userRecord);
        String status = isClaimed(userRecord)
                ? STATUS_CLAIMED
                : progress >= target ? STATUS_UNLOCKED : STATUS_LOCKED;
        return new MonthlyChallengeItemResponse(
                challenge.getId(),
                challenge.getChallengeCode(),
                challenge.getChallengeName(),
                challenge.getDescription(),
                challenge.getTheme(),
                challenge.getMonthsFrom(),
                challenge.getMonthsTo(),
                challenge.getTargetType(),
                target,
                progress,
                progressPercent(progress, target),
                challenge.getRewardType(),
                challenge.getRewardAmount(),
                challenge.getRewardName(),
                challenge.getBadgeAchievementCode(),
                status,
                userRecord == null ? null : userRecord.getClaimedAt());
    }

    private EventQuestItemResponse toEventResponse(EventQuest quest, UserEventQuest userRecord, LocalDateTime now) {
        int target = targetValue(quest.getTargetValue());
        int progress = progress(userRecord);
        String status = isExpired(quest, now)
                ? STATUS_EXPIRED
                : isClaimed(userRecord) ? STATUS_CLAIMED : progress >= target ? STATUS_UNLOCKED : STATUS_LOCKED;
        return new EventQuestItemResponse(
                quest.getId(),
                quest.getQuestCode(),
                quest.getQuestName(),
                quest.getDescription(),
                quest.getStartsAt(),
                quest.getEndsAt(),
                quest.getTargetType(),
                target,
                progress,
                progressPercent(progress, target),
                quest.getRewardType(),
                quest.getRewardAmount(),
                quest.getRewardName(),
                quest.getBadgeAchievementCode(),
                status,
                userRecord == null ? null : userRecord.getClaimedAt());
    }

    private CampaignClaimResponse monthlyClaimResponse(
            Long userId,
            MonthlyChallenge challenge,
            UserMonthlyChallenge userRecord,
            boolean claimed,
            String status,
            int awardedPoints,
            int totalPoints) {
        return new CampaignClaimResponse(userId, "MONTHLY_CHALLENGE", challenge.getChallengeCode(), claimed, status,
                progress(userRecord), targetValue(challenge.getTargetValue()), challenge.getRewardType(),
                challenge.getRewardAmount(), challenge.getRewardName(), awardedPoints, totalPoints,
                userRecord == null ? null : userRecord.getClaimedAt());
    }

    private CampaignClaimResponse eventClaimResponse(
            Long userId,
            EventQuest quest,
            UserEventQuest userRecord,
            boolean claimed,
            String status,
            int awardedPoints,
            int totalPoints) {
        return new CampaignClaimResponse(userId, "EVENT_QUEST", quest.getQuestCode(), claimed, status,
                progress(userRecord), targetValue(quest.getTargetValue()), quest.getRewardType(),
                quest.getRewardAmount(), quest.getRewardName(), awardedPoints, totalPoints,
                userRecord == null ? null : userRecord.getClaimedAt());
    }

    private boolean isClaimed(UserMonthlyChallenge record) {
        return record != null && STATUS_CLAIMED.equals(record.getClaimStatus());
    }

    private boolean isClaimed(UserEventQuest record) {
        return record != null && STATUS_CLAIMED.equals(record.getClaimStatus());
    }

    private boolean isExpired(EventQuest quest, LocalDateTime now) {
        return quest.getEndsAt() != null && quest.getEndsAt().isBefore(now);
    }

    private int progress(UserMonthlyChallenge record) {
        return record == null || record.getProgressValue() == null ? 0 : Math.max(0, record.getProgressValue());
    }

    private int progress(UserEventQuest record) {
        return record == null || record.getProgressValue() == null ? 0 : Math.max(0, record.getProgressValue());
    }

    private int progressPercent(int progress, int target) {
        return Math.min(100, Math.max(0, progress * 100 / Math.max(1, target)));
    }

    private int pointsReward(String rewardType, BigDecimal rewardAmount) {
        if (!REWARD_TYPE_POINTS.equals(rewardType)) {
            return 0;
        }
        try {
            return rewardAmount.intValueExact();
        } catch (ArithmeticException ex) {
            throw new BizException("Unsupported points reward");
        }
    }

    private int totalPoints(Long userId) {
        Integer total = pointsLedgerMapper.sumPointsByUser(userId);
        return total == null ? 0 : total;
    }

    private int monthsSinceRegistration(Long userId) {
        Integer value = monthlyChallengeMapper.selectUserMonthsSinceRegistration(userId);
        return value == null ? 0 : Math.max(0, value);
    }

    private void ensureMonthlyCodeAvailable(String code, Long exceptId) {
        MonthlyChallenge existing = monthlyChallengeMapper.selectOne(new LambdaQueryWrapper<MonthlyChallenge>()
                .eq(MonthlyChallenge::getChallengeCode, code)
                .eq(MonthlyChallenge::getIsDeleted, 0));
        if (existing != null && !existing.getId().equals(exceptId)) {
            throw new BizException("Monthly challenge code already exists");
        }
    }

    private void ensureEventCodeAvailable(String code, Long exceptId) {
        EventQuest existing = eventQuestMapper.selectOne(new LambdaQueryWrapper<EventQuest>()
                .eq(EventQuest::getQuestCode, code)
                .eq(EventQuest::getIsDeleted, 0));
        if (existing != null && !existing.getId().equals(exceptId)) {
            throw new BizException("Event quest code already exists");
        }
    }

    private String normalizeCode(String value, String fieldName) {
        if (!StringUtils.hasText(value)) {
            throw new BizException(fieldName + " is required");
        }
        String normalized = value.trim().toUpperCase();
        if (!CODE_PATTERN.matcher(normalized).matches()) {
            throw new BizException("Unsupported " + fieldName);
        }
        return normalized;
    }

    private String normalizeOptionalCode(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        return normalizeCode(value, "badge achievement code");
    }

    private String normalizeRewardType(String rewardType) {
        return normalizeCode(rewardType, "reward type");
    }

    private String requiredText(String value, String fieldName, int maxLength) {
        String normalized = trimToNull(value, maxLength);
        if (normalized == null) {
            throw new BizException(fieldName + " is required");
        }
        return normalized;
    }

    private String trimToNull(String value, int maxLength) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String normalized = value.trim();
        if (normalized.length() > maxLength) {
            throw new BizException("Text is too long");
        }
        return normalized;
    }

    private BigDecimal requirePositiveAmount(BigDecimal value) {
        if (value == null || value.signum() <= 0) {
            throw new BizException("Reward amount must be positive");
        }
        return value;
    }

    private int targetValue(Integer value) {
        if (value == null || value < 1) {
            throw new BizException("Target value must be positive");
        }
        return value;
    }

    private void validateMonthlyWindow(Integer monthsFrom, Integer monthsTo) {
        if (monthsFrom != null && monthsFrom < 0 || monthsTo != null && monthsTo < 0) {
            throw new BizException("Monthly challenge month window is invalid");
        }
        if (monthsFrom != null && monthsTo != null && monthsFrom > monthsTo) {
            throw new BizException("Monthly challenge month window is invalid");
        }
    }

    private void validateEventWindow(LocalDateTime startsAt, LocalDateTime endsAt) {
        if (startsAt != null && endsAt != null && endsAt.isBefore(startsAt)) {
            throw new BizException("Event quest time window is invalid");
        }
    }

    private int normalizeStatus(String status) {
        if (!StringUtils.hasText(status)) {
            return 1;
        }
        if ("ACTIVE".equalsIgnoreCase(status) || "1".equals(status.trim())) {
            return 1;
        }
        if ("DISABLED".equalsIgnoreCase(status) || "0".equals(status.trim())) {
            return 0;
        }
        throw new BizException("Unsupported status");
    }

    private int normalizeStatus(Integer status) {
        if (status == null) {
            return 1;
        }
        if (status == 0 || status == 1) {
            return status;
        }
        throw new BizException("Unsupported status");
    }

    private int defaultInt(Integer value, int defaultValue) {
        return value == null ? defaultValue : value;
    }

    private long normalizePageNum(long pageNum) {
        return Math.max(1, pageNum);
    }

    private long normalizePageSize(long pageSize) {
        return Math.min(MAX_PAGE_SIZE, Math.max(1, pageSize));
    }

    private Long requireId(Long id, String fieldName) {
        if (id == null || id < 1) {
            throw new BizException(fieldName + " is required");
        }
        return id;
    }

    private void requireUserId(Long userId) {
        if (userId == null || userId < 1) {
            throw new BizException("User id is required");
        }
    }
}
