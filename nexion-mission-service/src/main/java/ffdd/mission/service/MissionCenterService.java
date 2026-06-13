package ffdd.mission.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import ffdd.common.api.PageResult;
import ffdd.common.exception.BizException;
import ffdd.mission.domain.Achievement;
import ffdd.mission.domain.DailyCheckIn;
import ffdd.mission.domain.Mission;
import ffdd.mission.domain.PointsLedger;
import ffdd.mission.domain.StreakMilestone;
import ffdd.mission.domain.StreakPowerUp;
import ffdd.mission.domain.UserAchievement;
import ffdd.mission.domain.UserMission;
import ffdd.mission.domain.UserStreak;
import ffdd.mission.domain.UserStreakMilestone;
import ffdd.mission.domain.UserStreakPowerUp;
import ffdd.mission.dto.AchievementClaimResponse;
import ffdd.mission.dto.AchievementItemResponse;
import ffdd.mission.dto.AchievementRequest;
import ffdd.mission.dto.AchievementUpdateRequest;
import ffdd.mission.dto.DailyCheckInResponse;
import ffdd.mission.dto.MissionItemResponse;
import ffdd.mission.dto.MissionListResponse;
import ffdd.mission.dto.MissionRequest;
import ffdd.mission.dto.MissionUpdateRequest;
import ffdd.mission.dto.PointsSummaryResponse;
import ffdd.mission.dto.StreakMilestoneClaimResponse;
import ffdd.mission.dto.StreakMilestoneItemResponse;
import ffdd.mission.dto.StreakMilestoneRequest;
import ffdd.mission.dto.StreakMilestoneUpdateRequest;
import ffdd.mission.dto.StreakPowerUpActivationResponse;
import ffdd.mission.dto.StreakPowerUpItemResponse;
import ffdd.mission.dto.StreakPowerUpRequest;
import ffdd.mission.dto.StreakPowerUpUpdateRequest;
import ffdd.mission.dto.StreakLeaderboardEntryResponse;
import ffdd.mission.dto.StreakSaverResponse;
import ffdd.mission.dto.StreakSummaryResponse;
import ffdd.mission.mapper.AchievementMapper;
import ffdd.mission.mapper.DailyCheckInMapper;
import ffdd.mission.mapper.MissionMapper;
import ffdd.mission.mapper.PointsLedgerMapper;
import ffdd.mission.mapper.StreakMilestoneMapper;
import ffdd.mission.mapper.StreakPowerUpMapper;
import ffdd.mission.mapper.UserAchievementMapper;
import ffdd.mission.mapper.UserMissionMapper;
import ffdd.mission.mapper.UserStreakMapper;
import ffdd.mission.mapper.UserStreakMilestoneMapper;
import ffdd.mission.mapper.UserStreakPowerUpMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MissionCenterService {
    private static final int MAX_PAGE_SIZE = 100;
    private static final String MISSION_DAILY_CHECK_IN = "DAILY_CHECK_IN";
    private static final String STATUS_COMPLETED = "COMPLETED";
    private static final String STATUS_ALREADY_CHECKED_IN = "ALREADY_CHECKED_IN";
    private static final String STATUS_PENDING = "PENDING";
    private static final String STATUS_LOCKED = "LOCKED";
    private static final String STATUS_UNLOCKED = "UNLOCKED";
    private static final String STATUS_CLAIMED = "CLAIMED";
    private static final String STATUS_ALREADY_CLAIMED = "ALREADY_CLAIMED";
    private static final String STATUS_ACTIVATED = "ACTIVATED";
    private static final String STATUS_ALREADY_ACTIVATED = "ALREADY_ACTIVATED";
    private static final String STATUS_RESTORED = "RESTORED";
    private static final String STATUS_NOT_BROKEN = "NOT_BROKEN";
    private static final String STATUS_NO_SAVERS = "NO_SAVERS";
    private static final String STATUS_NO_RECOVERABLE_STREAK = "NO_RECOVERABLE_STREAK";
    private static final String BIZ_TYPE_DAILY_CHECK_IN = "DAILY_CHECK_IN";
    private static final String BIZ_TYPE_ACHIEVEMENT = "ACHIEVEMENT";
    private static final String BIZ_TYPE_STREAK_MILESTONE = "STREAK_MILESTONE";
    private static final String REWARD_TYPE_POINTS = "POINTS";
    private static final String REWARD_TYPE_BADGE = "BADGE";
    private static final String TRIGGER_STREAK_DAYS = "STREAK_DAYS";
    private static final int INITIAL_STREAK_SAVERS = 1;
    private static final int STREAK_SAVER_RECOVERY_LIMIT_DAYS = 30;
    private static final int STREAK_BONUS_INTERVAL_DAYS = 7;
    private static final int STREAK_BONUS_POINTS = 5;
    private static final int TOP_STREAKERS_MAX_LIMIT = 100;
    private static final BigDecimal DEFAULT_REWARD_MULTIPLIER = new BigDecimal("1.00");
    private static final Pattern ACHIEVEMENT_CODE_PATTERN = Pattern.compile("[A-Z0-9_]{1,64}");
    private static final Pattern POWER_UP_CODE_PATTERN = Pattern.compile("[a-z0-9_]{1,64}");

    private final MissionMapper missionMapper;
    private final UserMissionMapper userMissionMapper;
    private final PointsLedgerMapper pointsLedgerMapper;
    private final DailyCheckInMapper dailyCheckInMapper;
    private final UserStreakMapper userStreakMapper;
    private final AchievementMapper achievementMapper;
    private final UserAchievementMapper userAchievementMapper;
    private final StreakPowerUpMapper streakPowerUpMapper;
    private final UserStreakPowerUpMapper userStreakPowerUpMapper;
    private final StreakMilestoneMapper streakMilestoneMapper;
    private final UserStreakMilestoneMapper userStreakMilestoneMapper;
    private final DailyCheckInRewardPolicy rewardPolicy;

    public MissionCenterService(
            MissionMapper missionMapper,
            UserMissionMapper userMissionMapper,
            PointsLedgerMapper pointsLedgerMapper,
            DailyCheckInMapper dailyCheckInMapper,
            UserStreakMapper userStreakMapper,
            AchievementMapper achievementMapper,
            UserAchievementMapper userAchievementMapper,
            StreakPowerUpMapper streakPowerUpMapper,
            UserStreakPowerUpMapper userStreakPowerUpMapper,
            StreakMilestoneMapper streakMilestoneMapper,
            UserStreakMilestoneMapper userStreakMilestoneMapper,
            DailyCheckInRewardPolicy rewardPolicy) {
        this.missionMapper = missionMapper;
        this.userMissionMapper = userMissionMapper;
        this.pointsLedgerMapper = pointsLedgerMapper;
        this.dailyCheckInMapper = dailyCheckInMapper;
        this.userStreakMapper = userStreakMapper;
        this.achievementMapper = achievementMapper;
        this.userAchievementMapper = userAchievementMapper;
        this.streakPowerUpMapper = streakPowerUpMapper;
        this.userStreakPowerUpMapper = userStreakPowerUpMapper;
        this.streakMilestoneMapper = streakMilestoneMapper;
        this.userStreakMilestoneMapper = userStreakMilestoneMapper;
        this.rewardPolicy = rewardPolicy;
    }

    public MissionListResponse listMissions(Long userId) {
        requireUserId(userId);
        LocalDate today = LocalDate.now();
        DailyCheckIn todayCheckIn = findDailyCheckIn(userId, today);
        Map<Long, UserMission> completions = userCompletions(userId).stream()
                .collect(Collectors.toMap(UserMission::getMissionId, Function.identity(), (left, right) -> left));
        List<MissionItemResponse> records = activeMissions().stream()
                .map(mission -> toMissionItem(mission, completions.get(mission.getId()), todayCheckIn))
                .toList();
        long completedCount = records.stream().filter(MissionItemResponse::isCompleted).count();
        return new MissionListResponse(userId, records.size(), completedCount, todayCheckIn != null, records);
    }

    public PageResult<Mission> pageMissionOps(String status, long pageNum, long pageSize) {
        long normalizedPageNum = Math.max(1, pageNum);
        long normalizedPageSize = Math.min(Math.max(1, pageSize), MAX_PAGE_SIZE);
        LambdaQueryWrapper<Mission> wrapper = new LambdaQueryWrapper<Mission>()
                .eq(Mission::getIsDeleted, 0)
                .orderByAsc(Mission::getId);
        if (status != null && !status.isBlank()) {
            wrapper.eq(Mission::getStatus, Integer.parseInt(status));
        }
        Page<Mission> page = missionMapper.selectPage(new Page<>(normalizedPageNum, normalizedPageSize), wrapper);
        return new PageResult<>(page.getTotal(), page.getCurrent(), page.getSize(), page.getRecords());
    }

    @Transactional(rollbackFor = Exception.class)
    public Mission createMission(MissionRequest request) {
        String code = normalizeMissionCode(request.getMissionCode());
        Mission existing = missionMapper.selectOne(new LambdaQueryWrapper<Mission>()
                .eq(Mission::getMissionCode, code)
                .eq(Mission::getIsDeleted, 0));
        if (existing != null) {
            throw new BizException("Mission code already exists");
        }
        Mission mission = new Mission();
        mission.setMissionCode(code);
        mission.setMissionName(requireText(request.getMissionName(), "Mission name is required"));
        mission.setMissionType(normalizeMissionType(request.getMissionType()));
        mission.setRewardPoints(Math.max(0, request.getRewardPoints() == null ? 0 : request.getRewardPoints()));
        mission.setStatus(request.getStatus() == null ? 1 : request.getStatus());
        mission.setIsDeleted(0);
        missionMapper.insert(mission);
        return mission;
    }

    @Transactional(rollbackFor = Exception.class)
    public Mission updateMission(Long id, MissionUpdateRequest request) {
        Mission existing = requireMissionById(id);
        Mission patch = new Mission();
        patch.setId(existing.getId());
        if (request.getMissionName() != null) {
            patch.setMissionName(requireText(request.getMissionName(), "Mission name is required"));
        }
        if (request.getMissionType() != null) {
            patch.setMissionType(normalizeMissionType(request.getMissionType()));
        }
        if (request.getRewardPoints() != null) {
            patch.setRewardPoints(Math.max(0, request.getRewardPoints()));
        }
        if (request.getStatus() != null) {
            patch.setStatus(request.getStatus());
        }
        missionMapper.updateById(patch);
        return requireMissionById(existing.getId());
    }

    @Transactional(rollbackFor = Exception.class)
    public void deleteMission(Long id) {
        Mission existing = requireMissionById(id);
        Mission patch = new Mission();
        patch.setId(existing.getId());
        patch.setIsDeleted(1);
        missionMapper.updateById(patch);
    }

    public PageResult<Achievement> pageAchievementsOps(
            String category,
            String status,
            String keyword,
            long pageNum,
            long pageSize) {
        long normalizedPageNum = Math.max(1, pageNum);
        long normalizedPageSize = Math.min(Math.max(1, pageSize), MAX_PAGE_SIZE);
        LambdaQueryWrapper<Achievement> wrapper = new LambdaQueryWrapper<Achievement>()
                .eq(Achievement::getIsDeleted, 0)
                .orderByAsc(Achievement::getCategory)
                .orderByAsc(Achievement::getSortOrder)
                .orderByAsc(Achievement::getTriggerValue)
                .orderByAsc(Achievement::getId);
        if (category != null && !category.isBlank()) {
            wrapper.eq(Achievement::getCategory, category.trim().toUpperCase());
        }
        if (status != null && !status.isBlank()) {
            wrapper.eq(Achievement::getStatus, Integer.parseInt(status));
        }
        if (keyword != null && !keyword.isBlank()) {
            String like = keyword.trim();
            wrapper.and(w -> w.like(Achievement::getAchievementCode, like)
                    .or()
                    .like(Achievement::getAchievementName, like)
                    .or()
                    .like(Achievement::getDescription, like));
        }
        Page<Achievement> page = achievementMapper.selectPage(new Page<>(normalizedPageNum, normalizedPageSize), wrapper);
        return new PageResult<>(page.getTotal(), page.getCurrent(), page.getSize(), page.getRecords());
    }

    @Transactional(rollbackFor = Exception.class)
    public Achievement createAchievement(AchievementRequest request) {
        String code = normalizeAchievementCode(request.getAchievementCode());
        Achievement existing = achievementMapper.selectOne(new LambdaQueryWrapper<Achievement>()
                .eq(Achievement::getAchievementCode, code)
                .eq(Achievement::getIsDeleted, 0));
        if (existing != null) {
            throw new BizException("Achievement code already exists");
        }
        Achievement achievement = new Achievement();
        achievement.setAchievementCode(code);
        achievement.setAchievementName(requireText(request.getAchievementName(), "Achievement name is required"));
        achievement.setDescription(trimToNull(request.getDescription()));
        achievement.setCategory(requireText(request.getCategory(), "Achievement category is required").toUpperCase());
        achievement.setIconKey(trimToNull(request.getIconKey()));
        achievement.setAccentColor(trimToNull(request.getAccentColor()));
        achievement.setTriggerType(requireText(request.getTriggerType(), "Achievement trigger type is required").toUpperCase());
        achievement.setTriggerValue(Math.max(1, request.getTriggerValue() == null ? 1 : request.getTriggerValue()));
        achievement.setRewardPoints(Math.max(0, request.getRewardPoints() == null ? 0 : request.getRewardPoints()));
        achievement.setSortOrder(request.getSortOrder() == null ? 0 : request.getSortOrder());
        achievement.setStatus(request.getStatus() == null ? 1 : request.getStatus());
        achievement.setIsDeleted(0);
        achievementMapper.insert(achievement);
        return achievement;
    }

    @Transactional(rollbackFor = Exception.class)
    public Achievement updateAchievement(Long id, AchievementUpdateRequest request) {
        Achievement existing = requireAchievementById(id);
        Achievement patch = new Achievement();
        patch.setId(existing.getId());
        if (request.getAchievementName() != null) {
            patch.setAchievementName(requireText(request.getAchievementName(), "Achievement name is required"));
        }
        if (request.getDescription() != null) {
            patch.setDescription(trimToNull(request.getDescription()));
        }
        if (request.getCategory() != null) {
            patch.setCategory(requireText(request.getCategory(), "Achievement category is required").toUpperCase());
        }
        if (request.getIconKey() != null) {
            patch.setIconKey(trimToNull(request.getIconKey()));
        }
        if (request.getAccentColor() != null) {
            patch.setAccentColor(trimToNull(request.getAccentColor()));
        }
        if (request.getTriggerType() != null) {
            patch.setTriggerType(requireText(request.getTriggerType(), "Achievement trigger type is required").toUpperCase());
        }
        if (request.getTriggerValue() != null) {
            patch.setTriggerValue(Math.max(1, request.getTriggerValue()));
        }
        if (request.getRewardPoints() != null) {
            patch.setRewardPoints(Math.max(0, request.getRewardPoints()));
        }
        if (request.getSortOrder() != null) {
            patch.setSortOrder(request.getSortOrder());
        }
        if (request.getStatus() != null) {
            patch.setStatus(request.getStatus());
        }
        achievementMapper.updateById(patch);
        return requireAchievementById(existing.getId());
    }

    @Transactional(rollbackFor = Exception.class)
    public void deleteAchievement(Long id) {
        Achievement existing = requireAchievementById(id);
        Achievement patch = new Achievement();
        patch.setId(existing.getId());
        patch.setIsDeleted(1);
        achievementMapper.updateById(patch);
    }

    @Transactional(rollbackFor = Exception.class)
    public DailyCheckInResponse dailyCheckIn(Long userId) {
        requireUserId(userId);
        LocalDate today = LocalDate.now();
        Mission mission = requireDailyCheckInMission();
        DailyCheckIn existing = findDailyCheckIn(userId, today);
        int currentTotalPoints = totalPoints(userId);
        if (existing != null) {
            UserStreak streak = findUserStreak(userId);
            return dailyCheckInResponse(
                    userId,
                    today,
                    false,
                    0,
                    basePoints(existing),
                    rewardMultiplier(existing),
                    bonusPoints(existing),
                    streakBonusPoints(existing),
                    currentTotalPoints,
                    STATUS_ALREADY_CHECKED_IN,
                    streak,
                    List.of());
        }

        int basePoints = rewardPoints(mission);
        UserStreak streakBeforeCheckIn = findUserStreak(userId);
        int nextStreak = nextStreakAfterCheckIn(streakBeforeCheckIn, today);
        DailyCheckInReward reward = rewardPolicy.roll(basePoints);
        int streakBonusPoints = streakBonusPoints(nextStreak);
        int awardedPoints = reward.getAwardedPoints() + streakBonusPoints;
        int bonusPoints = Math.max(0, awardedPoints - basePoints);
        DailyCheckIn checkIn = new DailyCheckIn();
        checkIn.setUserId(userId);
        checkIn.setMissionId(mission.getId());
        checkIn.setCheckInDate(today);
        checkIn.setBasePoints(basePoints);
        checkIn.setRewardMultiplier(rewardMultiplier(reward));
        checkIn.setBonusPoints(bonusPoints);
        checkIn.setStreakBonusPoints(streakBonusPoints);
        checkIn.setRewardPoints(awardedPoints);
        checkIn.setIsDeleted(0);
        try {
            dailyCheckInMapper.insert(checkIn);
        } catch (DuplicateKeyException ex) {
            DailyCheckIn persisted = findDailyCheckIn(userId, today);
            return dailyCheckInResponse(
                    userId,
                    today,
                    false,
                    0,
                    basePoints(persisted),
                    rewardMultiplier(persisted),
                    bonusPoints(persisted),
                    streakBonusPoints(persisted),
                    totalPoints(userId),
                    STATUS_ALREADY_CHECKED_IN,
                    findUserStreak(userId),
                    List.of());
        }

        UserStreak streak = advanceStreak(userId, today, streakBeforeCheckIn, nextStreak);
        List<AchievementItemResponse> unlockedAchievements = unlockStreakAchievements(userId, streak);
        int balanceAfter = currentTotalPoints + awardedPoints;
        insertPointsLedgerIfNeeded(userId, dailyCheckInBizNo(userId, today), BIZ_TYPE_DAILY_CHECK_IN, awardedPoints, balanceAfter);
        return dailyCheckInResponse(
                userId,
                today,
                true,
                awardedPoints,
                basePoints,
                rewardMultiplier(reward),
                bonusPoints,
                streakBonusPoints,
                balanceAfter,
                STATUS_COMPLETED,
                streak,
                unlockedAchievements);
    }

    public PointsSummaryResponse pointsSummary(Long userId, long pageNum, long pageSize) {
        requireUserId(userId);
        long normalizedPageNum = Math.max(1, pageNum);
        long normalizedPageSize = Math.min(Math.max(1, pageSize), MAX_PAGE_SIZE);
        Page<PointsLedger> page = pointsLedgerMapper.selectPage(
                new Page<>(normalizedPageNum, normalizedPageSize),
                new LambdaQueryWrapper<PointsLedger>()
                        .eq(PointsLedger::getUserId, userId)
                        .eq(PointsLedger::getIsDeleted, 0)
                        .orderByDesc(PointsLedger::getCreatedAt)
                        .orderByDesc(PointsLedger::getId));
        PageResult<PointsLedger> recentLedgers =
                new PageResult<>(page.getTotal(), page.getCurrent(), page.getSize(), page.getRecords());
        return new PointsSummaryResponse(userId, totalPoints(userId), recentLedgers);
    }

    public StreakSummaryResponse streakSummary(Long userId) {
        requireUserId(userId);
        UserStreak streak = findUserStreak(userId);
        LocalDate today = LocalDate.now();
        boolean streakBroken = isStreakBroken(streak, today);
        int currentStreak = currentStreak(streak);
        return new StreakSummaryResponse(
                userId,
                currentStreak,
                intValue(streak == null ? null : streak.getLongestStreak()),
                intValue(streak == null ? null : streak.getStreakSavers()),
                streak == null ? null : streak.getLastCheckInDate(),
                nextStreakMilestone(currentStreak),
                checkedInToday(streak),
                streakBroken,
                saverAvailable(streak, today),
                streakBroken ? recoverableStreak(streak) : 0);
    }

    public List<StreakLeaderboardEntryResponse> topStreakers(Long userId, int limit) {
        requireUserId(userId);
        int normalizedLimit = Math.min(Math.max(1, limit), TOP_STREAKERS_MAX_LIMIT);
        List<StreakLeaderboardEntryResponse> entries =
                userStreakMapper.selectTopStreakers(LocalDate.now(), normalizedLimit);
        return entries == null ? List.of() : entries;
    }

    @Transactional(rollbackFor = Exception.class)
    public StreakSaverResponse useStreakSaver(Long userId) {
        requireUserId(userId);
        LocalDate today = LocalDate.now();
        UserStreak streak = findUserStreak(userId);
        if (!isStreakBroken(streak, today)) {
            return streakSaverResponse(userId, streak, STATUS_NOT_BROKEN, false);
        }
        int recoverableStreak = recoverableStreak(streak);
        if (recoverableStreak < 1) {
            return streakSaverResponse(userId, streak, STATUS_NO_RECOVERABLE_STREAK, false);
        }
        int remainingSavers = intValue(streak.getStreakSavers());
        if (remainingSavers < 1) {
            return streakSaverResponse(userId, streak, STATUS_NO_SAVERS, false);
        }

        LocalDate recoveredLastCheckInDate = today.minusDays(1);
        UserStreak patch = new UserStreak();
        patch.setId(streak.getId());
        patch.setCurrentStreak(recoverableStreak);
        patch.setStreakSavers(remainingSavers - 1);
        patch.setLastCheckInDate(recoveredLastCheckInDate);
        int updated = userStreakMapper.update(patch, new LambdaUpdateWrapper<UserStreak>()
                .eq(UserStreak::getId, streak.getId())
                .eq(UserStreak::getIsDeleted, 0)
                .gt(UserStreak::getStreakSavers, 0)
                .lt(UserStreak::getLastCheckInDate, today.minusDays(1)));
        if (updated < 1) {
            UserStreak latest = findUserStreak(userId);
            if (!isStreakBroken(latest, today)) {
                return streakSaverResponse(userId, latest, STATUS_NOT_BROKEN, false);
            }
            if (intValue(latest == null ? null : latest.getStreakSavers()) < 1) {
                return streakSaverResponse(userId, latest, STATUS_NO_SAVERS, false);
            }
            return streakSaverResponse(userId, latest, STATUS_NOT_BROKEN, false);
        }

        streak.setCurrentStreak(recoverableStreak);
        streak.setStreakSavers(remainingSavers - 1);
        streak.setLastCheckInDate(recoveredLastCheckInDate);
        return streakSaverResponse(userId, streak, STATUS_RESTORED, true);
    }

    public List<AchievementItemResponse> listAchievements(Long userId) {
        requireUserId(userId);
        int currentStreak = currentStreak(findUserStreak(userId));
        Map<String, UserAchievement> userAchievements = userAchievements(userId).stream()
                .collect(Collectors.toMap(UserAchievement::getAchievementCode, Function.identity(), (left, right) -> left));
        return activeAchievements().stream()
                .map(achievement -> toAchievementItem(
                        achievement,
                        userAchievements.get(achievement.getAchievementCode()),
                        currentStreak))
                .toList();
    }

    public List<StreakPowerUpItemResponse> listPowerUps(Long userId) {
        requireUserId(userId);
        int currentStreak = currentStreak(findUserStreak(userId));
        Map<String, UserStreakPowerUp> activations = userPowerUps(userId).stream()
                .collect(Collectors.toMap(UserStreakPowerUp::getPowerUpCode, Function.identity(), (left, right) -> left));
        return activePowerUps().stream()
                .map(powerUp -> toPowerUpItem(powerUp, activations.get(powerUp.getPowerUpCode()), currentStreak))
                .toList();
    }

    public List<StreakMilestoneItemResponse> listMilestones(Long userId) {
        requireUserId(userId);
        int currentStreak = currentStreak(findUserStreak(userId));
        Map<Integer, UserStreakMilestone> claims = userMilestones(userId).stream()
                .collect(Collectors.toMap(UserStreakMilestone::getMilestoneDay, Function.identity(), (left, right) -> left));
        return activeMilestones().stream()
                .map(milestone -> toMilestoneItem(milestone, claims.get(milestone.getMilestoneDay()), currentStreak))
                .toList();
    }

    public PageResult<StreakPowerUp> pagePowerUpsOps(String status, long pageNum, long pageSize) {
        long normalizedPageNum = Math.max(1, pageNum);
        long normalizedPageSize = Math.min(Math.max(1, pageSize), MAX_PAGE_SIZE);
        LambdaQueryWrapper<StreakPowerUp> wrapper = new LambdaQueryWrapper<StreakPowerUp>()
                .eq(StreakPowerUp::getIsDeleted, 0)
                .orderByAsc(StreakPowerUp::getUnlockStreakDays)
                .orderByAsc(StreakPowerUp::getSortOrder)
                .orderByAsc(StreakPowerUp::getId);
        if (status != null && !status.isBlank()) {
            wrapper.eq(StreakPowerUp::getStatus, Integer.parseInt(status));
        }
        Page<StreakPowerUp> page = streakPowerUpMapper.selectPage(new Page<>(normalizedPageNum, normalizedPageSize), wrapper);
        return new PageResult<>(page.getTotal(), page.getCurrent(), page.getSize(), page.getRecords());
    }

    public PageResult<StreakMilestone> pageMilestonesOps(String status, long pageNum, long pageSize) {
        long normalizedPageNum = Math.max(1, pageNum);
        long normalizedPageSize = Math.min(Math.max(1, pageSize), MAX_PAGE_SIZE);
        LambdaQueryWrapper<StreakMilestone> wrapper = new LambdaQueryWrapper<StreakMilestone>()
                .eq(StreakMilestone::getIsDeleted, 0)
                .orderByAsc(StreakMilestone::getMilestoneDay)
                .orderByAsc(StreakMilestone::getSortOrder)
                .orderByAsc(StreakMilestone::getId);
        if (status != null && !status.isBlank()) {
            wrapper.eq(StreakMilestone::getStatus, Integer.parseInt(status));
        }
        Page<StreakMilestone> page = streakMilestoneMapper.selectPage(new Page<>(normalizedPageNum, normalizedPageSize), wrapper);
        return new PageResult<>(page.getTotal(), page.getCurrent(), page.getSize(), page.getRecords());
    }

    @Transactional(rollbackFor = Exception.class)
    public StreakPowerUp createPowerUp(StreakPowerUpRequest request) {
        String code = normalizePowerUpCode(request.getPowerUpCode());
        StreakPowerUp existing = streakPowerUpMapper.selectOne(new LambdaQueryWrapper<StreakPowerUp>()
                .eq(StreakPowerUp::getPowerUpCode, code)
                .eq(StreakPowerUp::getIsDeleted, 0));
        if (existing != null) {
            throw new BizException("Power-up code already exists");
        }
        StreakPowerUp powerUp = new StreakPowerUp();
        powerUp.setPowerUpCode(code);
        powerUp.setPowerUpName(request.getPowerUpName().trim());
        powerUp.setI18nKey(trimToNull(request.getI18nKey()));
        powerUp.setTargetPath(trimToNull(request.getTargetPath()));
        powerUp.setBadgeAchievementCode(normalizeConfiguredAchievementCode(request.getBadgeAchievementCode()));
        powerUp.setUnlockStreakDays(request.getUnlockStreakDays());
        powerUp.setEffectType(request.getEffectType().trim());
        powerUp.setEffectValue(trimToNull(request.getEffectValue()));
        powerUp.setDurationDays(request.getDurationDays() == null ? 0 : request.getDurationDays());
        powerUp.setSortOrder(request.getSortOrder() == null ? 100 : request.getSortOrder());
        powerUp.setStatus(request.getStatus() == null ? 1 : request.getStatus());
        powerUp.setIsDeleted(0);
        streakPowerUpMapper.insert(powerUp);
        return powerUp;
    }

    @Transactional(rollbackFor = Exception.class)
    public StreakPowerUp updatePowerUp(Long id, StreakPowerUpUpdateRequest request) {
        StreakPowerUp existing = requirePowerUpById(id);
        StreakPowerUp patch = new StreakPowerUp();
        patch.setId(existing.getId());
        if (request.getPowerUpName() != null) {
            patch.setPowerUpName(request.getPowerUpName().trim());
        }
        if (request.getI18nKey() != null) {
            patch.setI18nKey(trimToNull(request.getI18nKey()));
        }
        if (request.getTargetPath() != null) {
            patch.setTargetPath(trimToNull(request.getTargetPath()));
        }
        if (request.getBadgeAchievementCode() != null) {
            patch.setBadgeAchievementCode(normalizeConfiguredAchievementCode(request.getBadgeAchievementCode()));
        }
        if (request.getUnlockStreakDays() != null) {
            patch.setUnlockStreakDays(request.getUnlockStreakDays());
        }
        if (request.getEffectType() != null) {
            patch.setEffectType(request.getEffectType().trim());
        }
        if (request.getEffectValue() != null) {
            patch.setEffectValue(trimToNull(request.getEffectValue()));
        }
        if (request.getDurationDays() != null) {
            patch.setDurationDays(request.getDurationDays());
        }
        if (request.getSortOrder() != null) {
            patch.setSortOrder(request.getSortOrder());
        }
        if (request.getStatus() != null) {
            patch.setStatus(request.getStatus());
        }
        streakPowerUpMapper.updateById(patch);
        return requirePowerUpById(existing.getId());
    }

    @Transactional(rollbackFor = Exception.class)
    public StreakMilestone createMilestone(StreakMilestoneRequest request) {
        int day = normalizeMilestoneDay(request.getMilestoneDay());
        StreakMilestone existing = streakMilestoneMapper.selectOne(new LambdaQueryWrapper<StreakMilestone>()
                .eq(StreakMilestone::getMilestoneDay, day)
                .eq(StreakMilestone::getIsDeleted, 0));
        if (existing != null) {
            throw new BizException("Streak milestone day already exists");
        }
        StreakMilestone milestone = new StreakMilestone();
        milestone.setMilestoneDay(day);
        milestone.setMilestoneName(request.getMilestoneName().trim());
        milestone.setRewardType(request.getRewardType().trim());
        milestone.setRewardAmount(request.getRewardAmount());
        milestone.setRewardName(request.getRewardName().trim());
        milestone.setBadgeAchievementCode(normalizeConfiguredAchievementCode(request.getBadgeAchievementCode()));
        milestone.setSortOrder(request.getSortOrder() == null ? day : request.getSortOrder());
        milestone.setStatus(request.getStatus() == null ? 1 : request.getStatus());
        milestone.setIsDeleted(0);
        streakMilestoneMapper.insert(milestone);
        return milestone;
    }

    @Transactional(rollbackFor = Exception.class)
    public StreakMilestone updateMilestone(Long id, StreakMilestoneUpdateRequest request) {
        StreakMilestone existing = requireMilestoneById(id);
        if (request.getMilestoneDay() != null && !request.getMilestoneDay().equals(existing.getMilestoneDay())) {
            int day = normalizeMilestoneDay(request.getMilestoneDay());
            StreakMilestone duplicate = streakMilestoneMapper.selectOne(new LambdaQueryWrapper<StreakMilestone>()
                    .eq(StreakMilestone::getMilestoneDay, day)
                    .eq(StreakMilestone::getIsDeleted, 0));
            if (duplicate != null && !duplicate.getId().equals(existing.getId())) {
                throw new BizException("Streak milestone day already exists");
            }
        }
        StreakMilestone patch = new StreakMilestone();
        patch.setId(existing.getId());
        if (request.getMilestoneDay() != null) {
            patch.setMilestoneDay(request.getMilestoneDay());
        }
        if (request.getMilestoneName() != null) {
            patch.setMilestoneName(request.getMilestoneName().trim());
        }
        if (request.getRewardType() != null) {
            patch.setRewardType(request.getRewardType().trim());
        }
        if (request.getRewardAmount() != null) {
            patch.setRewardAmount(request.getRewardAmount());
        }
        if (request.getRewardName() != null) {
            patch.setRewardName(request.getRewardName().trim());
        }
        if (request.getBadgeAchievementCode() != null) {
            patch.setBadgeAchievementCode(normalizeConfiguredAchievementCode(request.getBadgeAchievementCode()));
        }
        if (request.getSortOrder() != null) {
            patch.setSortOrder(request.getSortOrder());
        }
        if (request.getStatus() != null) {
            patch.setStatus(request.getStatus());
        }
        streakMilestoneMapper.updateById(patch);
        return requireMilestoneById(existing.getId());
    }

    @Transactional(rollbackFor = Exception.class)
    public StreakPowerUpActivationResponse activatePowerUp(Long userId, String powerUpCode) {
        requireUserId(userId);
        String normalizedCode = normalizePowerUpCode(powerUpCode);
        StreakPowerUp powerUp = requirePowerUp(normalizedCode);
        UserStreakPowerUp existing = findUserPowerUp(userId, normalizedCode);
        int currentStreak = currentStreak(findUserStreak(userId));
        if (existing != null && STATUS_ACTIVATED.equals(existing.getPowerUpStatus())) {
            return powerUpActivationResponse(
                    userId,
                    powerUp,
                    false,
                    STATUS_ALREADY_ACTIVATED,
                    currentStreak,
                    existing.getActivatedAt(),
                    existing.getExpiresAt());
        }
        if (currentStreak < intValue(powerUp.getUnlockStreakDays())) {
            return powerUpActivationResponse(
                    userId,
                    powerUp,
                    false,
                    STATUS_LOCKED,
                    currentStreak,
                    null,
                    null);
        }

        LocalDateTime now = LocalDateTime.now();
        LocalDateTime expiresAt = powerUpExpiresAt(powerUp, now);
        UserStreakPowerUp activation = new UserStreakPowerUp();
        activation.setUserId(userId);
        activation.setPowerUpId(powerUp.getId());
        activation.setPowerUpCode(powerUp.getPowerUpCode());
        activation.setPowerUpStatus(STATUS_ACTIVATED);
        activation.setUnlockedAt(now);
        activation.setActivatedAt(now);
        activation.setExpiresAt(expiresAt);
        activation.setIsDeleted(0);
        try {
            if (existing == null) {
                userStreakPowerUpMapper.insert(activation);
            } else {
                activation.setId(existing.getId());
                activation.setUnlockedAt(existing.getUnlockedAt() == null ? now : existing.getUnlockedAt());
                userStreakPowerUpMapper.updateById(activation);
            }
        } catch (DuplicateKeyException ignored) {
            UserStreakPowerUp latest = findUserPowerUp(userId, normalizedCode);
            if (latest != null && STATUS_ACTIVATED.equals(latest.getPowerUpStatus())) {
                return powerUpActivationResponse(
                        userId,
                        powerUp,
                        false,
                        STATUS_ALREADY_ACTIVATED,
                        currentStreak,
                        latest.getActivatedAt(),
                        latest.getExpiresAt());
            }
            throw ignored;
        }

        unlockPowerUpBadge(userId, powerUp, now);
        return powerUpActivationResponse(
                userId,
                powerUp,
                true,
                STATUS_ACTIVATED,
                currentStreak,
                activation.getActivatedAt(),
                activation.getExpiresAt());
    }

    @Transactional(rollbackFor = Exception.class)
    public StreakMilestoneClaimResponse claimMilestone(Long userId, Integer milestoneDay) {
        requireUserId(userId);
        int normalizedDay = normalizeMilestoneDay(milestoneDay);
        StreakMilestone milestone = requireMilestone(normalizedDay);
        UserStreakMilestone existing = findUserMilestone(userId, normalizedDay);
        int currentStreak = currentStreak(findUserStreak(userId));
        int currentTotalPoints = totalPoints(userId);
        if (existing != null && STATUS_CLAIMED.equals(existing.getClaimStatus())) {
            return milestoneClaimResponse(
                    userId,
                    milestone,
                    false,
                    STATUS_ALREADY_CLAIMED,
                    currentStreak,
                    0,
                    currentTotalPoints,
                    existing.getClaimedAt());
        }
        if (currentStreak < normalizedDay) {
            return milestoneClaimResponse(
                    userId,
                    milestone,
                    false,
                    STATUS_LOCKED,
                    currentStreak,
                    0,
                    currentTotalPoints,
                    null);
        }

        int awardedPoints = milestonePoints(milestone);
        LocalDateTime now = LocalDateTime.now();
        UserStreakMilestone claim = new UserStreakMilestone();
        claim.setUserId(userId);
        claim.setMilestoneId(milestone.getId());
        claim.setMilestoneDay(milestone.getMilestoneDay());
        claim.setRewardType(milestone.getRewardType());
        claim.setRewardAmount(milestone.getRewardAmount());
        claim.setClaimStatus(STATUS_CLAIMED);
        claim.setClaimedAt(now);
        claim.setIsDeleted(0);
        try {
            userStreakMilestoneMapper.insert(claim);
        } catch (DuplicateKeyException ignored) {
            UserStreakMilestone latest = findUserMilestone(userId, normalizedDay);
            if (latest != null && STATUS_CLAIMED.equals(latest.getClaimStatus())) {
                return milestoneClaimResponse(
                        userId,
                        milestone,
                        false,
                        STATUS_ALREADY_CLAIMED,
                        currentStreak,
                        0,
                        currentTotalPoints,
                        latest.getClaimedAt());
            }
            throw ignored;
        }

        int balanceAfter = currentTotalPoints + awardedPoints;
        if (awardedPoints > 0) {
            insertPointsLedgerIfNeeded(
                    userId,
                    milestoneBizNo(userId, normalizedDay),
                    BIZ_TYPE_STREAK_MILESTONE,
                    awardedPoints,
                    balanceAfter);
        }
        unlockMilestoneBadge(userId, milestone, now);
        return milestoneClaimResponse(
                userId,
                milestone,
                true,
                STATUS_CLAIMED,
                currentStreak,
                awardedPoints,
                balanceAfter,
                now);
    }

    @Transactional(rollbackFor = Exception.class)
    public AchievementClaimResponse claimAchievement(Long userId, String achievementCode) {
        requireUserId(userId);
        String normalizedCode = normalizeAchievementCode(achievementCode);
        Achievement achievement = requireAchievement(normalizedCode);
        UserAchievement userAchievement = findUserAchievement(userId, normalizedCode);
        if (userAchievement == null) {
            throw new BizException("Achievement is not unlocked");
        }
        int currentTotalPoints = totalPoints(userId);
        if (STATUS_CLAIMED.equals(userAchievement.getAchievementStatus())) {
            return new AchievementClaimResponse(
                    userId, normalizedCode, STATUS_ALREADY_CLAIMED, false, 0, currentTotalPoints);
        }
        if (!STATUS_UNLOCKED.equals(userAchievement.getAchievementStatus())) {
            throw new BizException("Achievement is not unlocked");
        }

        int rewardPoints = rewardPoints(achievement);
        int balanceAfter = currentTotalPoints + rewardPoints;
        insertPointsLedgerIfNeeded(
                userId,
                achievementBizNo(userId, normalizedCode),
                BIZ_TYPE_ACHIEVEMENT,
                rewardPoints,
                balanceAfter);
        UserAchievement patch = new UserAchievement();
        patch.setId(userAchievement.getId());
        patch.setAchievementStatus(STATUS_CLAIMED);
        patch.setClaimedAt(LocalDateTime.now());
        userAchievementMapper.updateById(patch);
        return new AchievementClaimResponse(
                userId, normalizedCode, STATUS_CLAIMED, true, rewardPoints, balanceAfter);
    }

    private List<Mission> activeMissions() {
        return missionMapper.selectList(new LambdaQueryWrapper<Mission>()
                .eq(Mission::getStatus, 1)
                .eq(Mission::getIsDeleted, 0)
                .orderByAsc(Mission::getId));
    }

    private List<UserMission> userCompletions(Long userId) {
        return userMissionMapper.selectList(new LambdaQueryWrapper<UserMission>()
                .eq(UserMission::getUserId, userId)
                .eq(UserMission::getIsDeleted, 0));
    }

    private MissionItemResponse toMissionItem(
            Mission mission,
            UserMission completion,
            DailyCheckIn todayCheckIn) {
        boolean dailyMission = MISSION_DAILY_CHECK_IN.equals(mission.getMissionCode());
        boolean completed = dailyMission ? todayCheckIn != null : isCompleted(completion);
        return new MissionItemResponse(
                mission.getId(),
                mission.getMissionCode(),
                mission.getMissionName(),
                mission.getMissionType(),
                rewardPoints(mission),
                completed,
                completed ? STATUS_COMPLETED : STATUS_PENDING,
                dailyMission || completion == null ? null : completion.getCompletedAt());
    }

    private Mission requireDailyCheckInMission() {
        Mission mission = missionMapper.selectOne(new LambdaQueryWrapper<Mission>()
                .eq(Mission::getMissionCode, MISSION_DAILY_CHECK_IN)
                .eq(Mission::getStatus, 1)
                .eq(Mission::getIsDeleted, 0));
        if (mission == null) {
            throw new BizException("Daily check-in mission is not configured");
        }
        return mission;
    }

    private Mission requireMissionById(Long id) {
        if (id == null) {
            throw new BizException("Mission id is required");
        }
        Mission mission = missionMapper.selectById(id);
        if (mission == null || Integer.valueOf(1).equals(mission.getIsDeleted())) {
            throw new BizException("Mission not found");
        }
        return mission;
    }

    private Achievement requireAchievementById(Long id) {
        if (id == null) {
            throw new BizException("Achievement id is required");
        }
        Achievement achievement = achievementMapper.selectById(id);
        if (achievement == null || Integer.valueOf(1).equals(achievement.getIsDeleted())) {
            throw new BizException("Achievement not found");
        }
        return achievement;
    }

    private DailyCheckIn findDailyCheckIn(Long userId, LocalDate date) {
        return dailyCheckInMapper.selectOne(new LambdaQueryWrapper<DailyCheckIn>()
                .eq(DailyCheckIn::getUserId, userId)
                .eq(DailyCheckIn::getCheckInDate, date)
                .eq(DailyCheckIn::getIsDeleted, 0));
    }

    private UserStreak advanceStreak(Long userId, LocalDate today, UserStreak existing, int nextStreak) {
        if (existing == null) {
            UserStreak created = new UserStreak();
            created.setUserId(userId);
            created.setCurrentStreak(nextStreak);
            created.setLongestStreak(nextStreak);
            created.setStreakSavers(INITIAL_STREAK_SAVERS);
            created.setLastCheckInDate(today);
            created.setIsDeleted(0);
            try {
                userStreakMapper.insert(created);
                return created;
            } catch (DuplicateKeyException ignored) {
                existing = findUserStreak(userId);
                if (existing == null) {
                    throw ignored;
                }
            }
        }

        int nextLongest = Math.max(intValue(existing.getLongestStreak()), nextStreak);
        UserStreak patch = new UserStreak();
        patch.setId(existing.getId());
        patch.setCurrentStreak(nextStreak);
        patch.setLongestStreak(nextLongest);
        patch.setLastCheckInDate(today);
        userStreakMapper.updateById(patch);

        existing.setCurrentStreak(nextStreak);
        existing.setLongestStreak(nextLongest);
        existing.setLastCheckInDate(today);
        return existing;
    }

    private List<AchievementItemResponse> unlockStreakAchievements(Long userId, UserStreak streak) {
        int currentStreak = intValue(streak == null ? null : streak.getCurrentStreak());
        if (currentStreak < 1) {
            return List.of();
        }
        List<AchievementItemResponse> unlocked = new ArrayList<>();
        for (Achievement achievement : activeStreakAchievements()) {
            if (currentStreak < intValue(achievement.getTriggerValue())) {
                continue;
            }
            if (findUserAchievement(userId, achievement.getAchievementCode()) != null) {
                continue;
            }
            UserAchievement userAchievement = new UserAchievement();
            userAchievement.setUserId(userId);
            userAchievement.setAchievementId(achievement.getId());
            userAchievement.setAchievementCode(achievement.getAchievementCode());
            userAchievement.setAchievementStatus(STATUS_UNLOCKED);
            userAchievement.setUnlockedAt(LocalDateTime.now());
            userAchievement.setIsDeleted(0);
            try {
                userAchievementMapper.insert(userAchievement);
                unlocked.add(toAchievementItem(achievement, userAchievement, currentStreak));
            } catch (DuplicateKeyException ignored) {
                // Concurrent unlock for the same user/achievement is already the desired state.
            }
        }
        return unlocked;
    }

    private Achievement requireAchievement(String achievementCode) {
        Achievement achievement = achievementMapper.selectOne(new LambdaQueryWrapper<Achievement>()
                .eq(Achievement::getAchievementCode, achievementCode)
                .eq(Achievement::getStatus, 1)
                .eq(Achievement::getIsDeleted, 0));
        if (achievement == null) {
            throw new BizException("Achievement not found");
        }
        return achievement;
    }

    private UserStreak findUserStreak(Long userId) {
        return userStreakMapper.selectOne(new LambdaQueryWrapper<UserStreak>()
                .eq(UserStreak::getUserId, userId)
                .eq(UserStreak::getIsDeleted, 0));
    }

    private List<Achievement> activeAchievements() {
        List<Achievement> achievements = achievementMapper.selectList(new LambdaQueryWrapper<Achievement>()
                .eq(Achievement::getStatus, 1)
                .eq(Achievement::getIsDeleted, 0)
                .orderByAsc(Achievement::getTriggerValue)
                .orderByAsc(Achievement::getId));
        return achievements == null ? List.of() : achievements;
    }

    private List<Achievement> activeStreakAchievements() {
        return activeAchievements().stream()
                .filter(achievement -> TRIGGER_STREAK_DAYS.equals(achievement.getTriggerType()))
                .toList();
    }

    private List<StreakPowerUp> activePowerUps() {
        List<StreakPowerUp> powerUps = streakPowerUpMapper.selectList(new LambdaQueryWrapper<StreakPowerUp>()
                .eq(StreakPowerUp::getStatus, 1)
                .eq(StreakPowerUp::getIsDeleted, 0)
                .orderByAsc(StreakPowerUp::getUnlockStreakDays)
                .orderByAsc(StreakPowerUp::getSortOrder)
                .orderByAsc(StreakPowerUp::getId));
        return powerUps == null ? List.of() : powerUps;
    }

    private List<StreakMilestone> activeMilestones() {
        List<StreakMilestone> milestones = streakMilestoneMapper.selectList(new LambdaQueryWrapper<StreakMilestone>()
                .eq(StreakMilestone::getStatus, 1)
                .eq(StreakMilestone::getIsDeleted, 0)
                .orderByAsc(StreakMilestone::getMilestoneDay)
                .orderByAsc(StreakMilestone::getSortOrder)
                .orderByAsc(StreakMilestone::getId));
        return milestones == null ? List.of() : milestones;
    }

    private List<UserAchievement> userAchievements(Long userId) {
        List<UserAchievement> achievements = userAchievementMapper.selectList(new LambdaQueryWrapper<UserAchievement>()
                .eq(UserAchievement::getUserId, userId)
                .eq(UserAchievement::getIsDeleted, 0));
        return achievements == null ? List.of() : achievements;
    }

    private List<UserStreakPowerUp> userPowerUps(Long userId) {
        List<UserStreakPowerUp> powerUps = userStreakPowerUpMapper.selectList(new LambdaQueryWrapper<UserStreakPowerUp>()
                .eq(UserStreakPowerUp::getUserId, userId)
                .eq(UserStreakPowerUp::getIsDeleted, 0));
        return powerUps == null ? List.of() : powerUps;
    }

    private List<UserStreakMilestone> userMilestones(Long userId) {
        List<UserStreakMilestone> milestones = userStreakMilestoneMapper.selectList(new LambdaQueryWrapper<UserStreakMilestone>()
                .eq(UserStreakMilestone::getUserId, userId)
                .eq(UserStreakMilestone::getIsDeleted, 0));
        return milestones == null ? List.of() : milestones;
    }

    private UserAchievement findUserAchievement(Long userId, String achievementCode) {
        return userAchievementMapper.selectOne(new LambdaQueryWrapper<UserAchievement>()
                .eq(UserAchievement::getUserId, userId)
                .eq(UserAchievement::getAchievementCode, achievementCode)
                .eq(UserAchievement::getIsDeleted, 0));
    }

    private StreakPowerUp requirePowerUp(String powerUpCode) {
        StreakPowerUp powerUp = streakPowerUpMapper.selectOne(new LambdaQueryWrapper<StreakPowerUp>()
                .eq(StreakPowerUp::getPowerUpCode, powerUpCode)
                .eq(StreakPowerUp::getStatus, 1)
                .eq(StreakPowerUp::getIsDeleted, 0));
        if (powerUp == null) {
            throw new BizException("Power-up not found");
        }
        return powerUp;
    }

    private StreakMilestone requireMilestone(int milestoneDay) {
        StreakMilestone milestone = streakMilestoneMapper.selectOne(new LambdaQueryWrapper<StreakMilestone>()
                .eq(StreakMilestone::getMilestoneDay, milestoneDay)
                .eq(StreakMilestone::getStatus, 1)
                .eq(StreakMilestone::getIsDeleted, 0));
        if (milestone == null) {
            throw new BizException("Streak milestone not found");
        }
        return milestone;
    }

    private StreakPowerUp requirePowerUpById(Long id) {
        if (id == null || id < 1) {
            throw new BizException("Power-up id is required");
        }
        StreakPowerUp powerUp = streakPowerUpMapper.selectOne(new LambdaQueryWrapper<StreakPowerUp>()
                .eq(StreakPowerUp::getId, id)
                .eq(StreakPowerUp::getIsDeleted, 0));
        if (powerUp == null) {
            throw new BizException("Power-up not found");
        }
        return powerUp;
    }

    private StreakMilestone requireMilestoneById(Long id) {
        if (id == null || id < 1) {
            throw new BizException("Streak milestone id is required");
        }
        StreakMilestone milestone = streakMilestoneMapper.selectOne(new LambdaQueryWrapper<StreakMilestone>()
                .eq(StreakMilestone::getId, id)
                .eq(StreakMilestone::getIsDeleted, 0));
        if (milestone == null) {
            throw new BizException("Streak milestone not found");
        }
        return milestone;
    }

    private UserStreakPowerUp findUserPowerUp(Long userId, String powerUpCode) {
        return userStreakPowerUpMapper.selectOne(new LambdaQueryWrapper<UserStreakPowerUp>()
                .eq(UserStreakPowerUp::getUserId, userId)
                .eq(UserStreakPowerUp::getPowerUpCode, powerUpCode)
                .eq(UserStreakPowerUp::getIsDeleted, 0));
    }

    private UserStreakMilestone findUserMilestone(Long userId, int milestoneDay) {
        return userStreakMilestoneMapper.selectOne(new LambdaQueryWrapper<UserStreakMilestone>()
                .eq(UserStreakMilestone::getUserId, userId)
                .eq(UserStreakMilestone::getMilestoneDay, milestoneDay)
                .eq(UserStreakMilestone::getIsDeleted, 0));
    }

    private AchievementItemResponse toAchievementItem(
            Achievement achievement,
            UserAchievement userAchievement,
            int currentStreak) {
        return new AchievementItemResponse(
                achievement.getId(),
                achievement.getAchievementCode(),
                achievement.getAchievementName(),
                achievement.getDescription(),
                achievement.getCategory(),
                achievement.getIconKey(),
                achievement.getAccentColor(),
                achievement.getTriggerType(),
                intValue(achievement.getTriggerValue()),
                rewardPoints(achievement),
                intValue(achievement.getSortOrder()),
                userAchievement == null ? STATUS_LOCKED : userAchievement.getAchievementStatus(),
                TRIGGER_STREAK_DAYS.equals(achievement.getTriggerType())
                        ? Math.min(currentStreak, intValue(achievement.getTriggerValue()))
                        : 0,
                userAchievement == null ? null : userAchievement.getUnlockedAt(),
                userAchievement == null ? null : userAchievement.getClaimedAt());
    }

    private StreakPowerUpItemResponse toPowerUpItem(
            StreakPowerUp powerUp,
            UserStreakPowerUp activation,
            int currentStreak) {
        int unlockStreakDays = intValue(powerUp.getUnlockStreakDays());
        return new StreakPowerUpItemResponse(
                powerUp.getId(),
                powerUp.getPowerUpCode(),
                powerUp.getPowerUpName(),
                powerUp.getI18nKey(),
                powerUp.getTargetPath(),
                powerUp.getBadgeAchievementCode(),
                unlockStreakDays,
                currentStreak,
                Math.max(0, unlockStreakDays - currentStreak),
                powerUp.getEffectType(),
                powerUp.getEffectValue(),
                intValue(powerUp.getDurationDays()),
                powerUpStatus(powerUp, activation, currentStreak),
                activation == null ? null : activation.getActivatedAt(),
                activation == null ? null : activation.getExpiresAt());
    }

    private StreakMilestoneItemResponse toMilestoneItem(
            StreakMilestone milestone,
            UserStreakMilestone claim,
            int currentStreak) {
        int milestoneDay = intValue(milestone.getMilestoneDay());
        return new StreakMilestoneItemResponse(
                milestone.getId(),
                milestoneDay,
                milestone.getMilestoneName(),
                milestone.getRewardType(),
                milestone.getRewardAmount(),
                milestone.getRewardName(),
                milestone.getBadgeAchievementCode(),
                currentStreak,
                Math.max(0, milestoneDay - currentStreak),
                milestoneStatus(milestone, claim, currentStreak),
                claim == null ? null : claim.getClaimedAt());
    }

    private DailyCheckInResponse dailyCheckInResponse(
            Long userId,
            LocalDate checkInDate,
            boolean completed,
            int awardedPoints,
            int basePoints,
            BigDecimal rewardMultiplier,
            int bonusPoints,
            int streakBonusPoints,
            int totalPoints,
            String status,
            UserStreak streak,
            List<AchievementItemResponse> unlockedAchievements) {
        return new DailyCheckInResponse(
                userId,
                checkInDate,
                completed,
                awardedPoints,
                basePoints,
                rewardMultiplier == null ? DEFAULT_REWARD_MULTIPLIER : rewardMultiplier,
                bonusPoints,
                streakBonusPoints,
                totalPoints,
                status,
                intValue(streak == null ? null : streak.getCurrentStreak()),
                intValue(streak == null ? null : streak.getLongestStreak()),
                unlockedAchievements == null ? List.of() : unlockedAchievements);
    }

    private StreakSaverResponse streakSaverResponse(
            Long userId,
            UserStreak streak,
            String status,
            boolean restored) {
        return new StreakSaverResponse(
                userId,
                restored,
                status,
                currentStreak(streak),
                intValue(streak == null ? null : streak.getLongestStreak()),
                intValue(streak == null ? null : streak.getStreakSavers()),
                streak == null ? null : streak.getLastCheckInDate(),
                recoverableStreak(streak),
                checkedInToday(streak));
    }

    private StreakPowerUpActivationResponse powerUpActivationResponse(
            Long userId,
            StreakPowerUp powerUp,
            boolean activated,
            String status,
            int currentStreak,
            LocalDateTime activatedAt,
            LocalDateTime expiresAt) {
        int unlockStreakDays = intValue(powerUp.getUnlockStreakDays());
        return new StreakPowerUpActivationResponse(
                userId,
                powerUp.getPowerUpCode(),
                activated,
                status,
                currentStreak,
                unlockStreakDays,
                Math.max(0, unlockStreakDays - currentStreak),
                powerUp.getTargetPath(),
                powerUp.getBadgeAchievementCode(),
                activatedAt,
                expiresAt);
    }

    private StreakMilestoneClaimResponse milestoneClaimResponse(
            Long userId,
            StreakMilestone milestone,
            boolean claimed,
            String status,
            int currentStreak,
            int awardedPoints,
            int totalPoints,
            LocalDateTime claimedAt) {
        int milestoneDay = intValue(milestone.getMilestoneDay());
        return new StreakMilestoneClaimResponse(
                userId,
                milestoneDay,
                claimed,
                status,
                currentStreak,
                Math.max(0, milestoneDay - currentStreak),
                milestone.getRewardType(),
                milestone.getRewardAmount(),
                milestone.getRewardName(),
                awardedPoints,
                totalPoints,
                milestone.getBadgeAchievementCode(),
                claimedAt);
    }

    private String powerUpStatus(StreakPowerUp powerUp, UserStreakPowerUp activation, int currentStreak) {
        if (activation != null && STATUS_ACTIVATED.equals(activation.getPowerUpStatus())) {
            return STATUS_ACTIVATED;
        }
        return currentStreak >= intValue(powerUp.getUnlockStreakDays()) ? STATUS_UNLOCKED : STATUS_LOCKED;
    }

    private String milestoneStatus(StreakMilestone milestone, UserStreakMilestone claim, int currentStreak) {
        if (claim != null && STATUS_CLAIMED.equals(claim.getClaimStatus())) {
            return STATUS_CLAIMED;
        }
        return currentStreak >= intValue(milestone.getMilestoneDay()) ? STATUS_UNLOCKED : STATUS_LOCKED;
    }

    private void unlockPowerUpBadge(Long userId, StreakPowerUp powerUp, LocalDateTime now) {
        String badgeCode = normalizeConfiguredAchievementCode(powerUp.getBadgeAchievementCode());
        if (badgeCode == null || findUserAchievement(userId, badgeCode) != null) {
            return;
        }
        Achievement achievement = achievementMapper.selectOne(new LambdaQueryWrapper<Achievement>()
                .eq(Achievement::getAchievementCode, badgeCode)
                .eq(Achievement::getStatus, 1)
                .eq(Achievement::getIsDeleted, 0));
        if (achievement == null) {
            return;
        }
        UserAchievement userAchievement = new UserAchievement();
        userAchievement.setUserId(userId);
        userAchievement.setAchievementId(achievement.getId());
        userAchievement.setAchievementCode(achievement.getAchievementCode());
        userAchievement.setAchievementStatus(STATUS_UNLOCKED);
        userAchievement.setUnlockedAt(now);
        userAchievement.setIsDeleted(0);
        try {
            userAchievementMapper.insert(userAchievement);
        } catch (DuplicateKeyException ignored) {
            // Concurrent activation has already unlocked the same badge.
        }
    }

    private void unlockMilestoneBadge(Long userId, StreakMilestone milestone, LocalDateTime now) {
        if (!REWARD_TYPE_BADGE.equals(milestone.getRewardType())) {
            return;
        }
        String badgeCode = normalizeConfiguredAchievementCode(milestone.getBadgeAchievementCode());
        if (badgeCode == null || findUserAchievement(userId, badgeCode) != null) {
            return;
        }
        Achievement achievement = achievementMapper.selectOne(new LambdaQueryWrapper<Achievement>()
                .eq(Achievement::getAchievementCode, badgeCode)
                .eq(Achievement::getStatus, 1)
                .eq(Achievement::getIsDeleted, 0));
        if (achievement == null) {
            return;
        }
        UserAchievement userAchievement = new UserAchievement();
        userAchievement.setUserId(userId);
        userAchievement.setAchievementId(achievement.getId());
        userAchievement.setAchievementCode(achievement.getAchievementCode());
        userAchievement.setAchievementStatus(STATUS_UNLOCKED);
        userAchievement.setUnlockedAt(now);
        userAchievement.setIsDeleted(0);
        try {
            userAchievementMapper.insert(userAchievement);
        } catch (DuplicateKeyException ignored) {
            // Concurrent milestone claim has already unlocked the same badge.
        }
    }

    private void insertPointsLedgerIfNeeded(
            Long userId,
            String bizNo,
            String bizType,
            int points,
            int balanceAfter) {
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
            // A duplicate ledger means another request won the same user/day idempotency race.
        }
    }

    private boolean isCompleted(UserMission completion) {
        return completion != null && STATUS_COMPLETED.equals(completion.getMissionStatus());
    }

    private String dailyCheckInBizNo(Long userId, LocalDate date) {
        return "CHECKIN-DAILY-" + userId + "-" + DateTimeFormatter.BASIC_ISO_DATE.format(date);
    }

    private String achievementBizNo(Long userId, String achievementCode) {
        return "ACHIEVEMENT-" + achievementCode + "-" + userId;
    }

    private String milestoneBizNo(Long userId, int milestoneDay) {
        return "STREAK-MILESTONE-" + milestoneDay + "-" + userId;
    }

    private String normalizeAchievementCode(String achievementCode) {
        if (achievementCode == null) {
            throw new BizException("Achievement code is required");
        }
        String normalized = achievementCode.trim().toUpperCase();
        if (!ACHIEVEMENT_CODE_PATTERN.matcher(normalized).matches()) {
            throw new BizException("Unsupported achievement code");
        }
        return normalized;
    }

    private String normalizePowerUpCode(String powerUpCode) {
        if (powerUpCode == null) {
            throw new BizException("Power-up code is required");
        }
        String normalized = powerUpCode.trim().toLowerCase();
        if (!POWER_UP_CODE_PATTERN.matcher(normalized).matches()) {
            throw new BizException("Unsupported power-up code");
        }
        return normalized;
    }

    private int normalizeMilestoneDay(Integer milestoneDay) {
        if (milestoneDay == null || milestoneDay < 1) {
            throw new BizException("Milestone day is required");
        }
        return milestoneDay;
    }

    private String normalizeConfiguredAchievementCode(String achievementCode) {
        if (achievementCode == null) {
            return null;
        }
        String normalized = achievementCode.trim().toUpperCase();
        return ACHIEVEMENT_CODE_PATTERN.matcher(normalized).matches() ? normalized : null;
    }

    private String trimToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new BizException(message);
        }
        return value.trim();
    }

    private String normalizeMissionCode(String value) {
        String code = requireText(value, "Mission code is required").toUpperCase();
        if (!ACHIEVEMENT_CODE_PATTERN.matcher(code).matches()) {
            throw new BizException("Mission code must use A-Z, 0-9, or underscore");
        }
        return code;
    }

    private String normalizeMissionType(String value) {
        return requireText(value, "Mission type is required").toUpperCase();
    }

    private LocalDateTime powerUpExpiresAt(StreakPowerUp powerUp, LocalDateTime activatedAt) {
        int durationDays = intValue(powerUp == null ? null : powerUp.getDurationDays());
        return durationDays > 0 ? activatedAt.plusDays(durationDays) : null;
    }

    private int nextStreakMilestone(int currentStreak) {
        return activeStreakAchievements().stream()
                .map(Achievement::getTriggerValue)
                .filter(value -> value != null && value > currentStreak)
                .findFirst()
                .orElse(0);
    }

    private int nextStreakAfterCheckIn(UserStreak streak, LocalDate today) {
        if (streak == null || streak.getLastCheckInDate() == null) {
            return 1;
        }
        if (today.equals(streak.getLastCheckInDate())) {
            return Math.max(1, intValue(streak.getCurrentStreak()));
        }
        if (today.minusDays(1).equals(streak.getLastCheckInDate())) {
            return intValue(streak.getCurrentStreak()) + 1;
        }
        return 1;
    }

    private int streakBonusPoints(int nextStreak) {
        return nextStreak > 0 && nextStreak % STREAK_BONUS_INTERVAL_DAYS == 0 ? STREAK_BONUS_POINTS : 0;
    }

    private int currentStreak(UserStreak streak) {
        if (streak == null || streak.getLastCheckInDate() == null) {
            return 0;
        }
        LocalDate today = LocalDate.now();
        if (today.equals(streak.getLastCheckInDate()) || today.minusDays(1).equals(streak.getLastCheckInDate())) {
            return intValue(streak.getCurrentStreak());
        }
        return 0;
    }

    private boolean checkedInToday(UserStreak streak) {
        return streak != null && LocalDate.now().equals(streak.getLastCheckInDate());
    }

    private boolean isStreakBroken(UserStreak streak, LocalDate today) {
        return streak != null
                && streak.getLastCheckInDate() != null
                && streak.getLastCheckInDate().isBefore(today.minusDays(1));
    }

    private boolean saverAvailable(UserStreak streak, LocalDate today) {
        return isStreakBroken(streak, today)
                && intValue(streak == null ? null : streak.getStreakSavers()) > 0
                && recoverableStreak(streak) > 0;
    }

    private int recoverableStreak(UserStreak streak) {
        return Math.min(
                intValue(streak == null ? null : streak.getLongestStreak()),
                STREAK_SAVER_RECOVERY_LIMIT_DAYS);
    }

    private int totalPoints(Long userId) {
        Integer total = pointsLedgerMapper.sumPointsByUser(userId);
        return total == null ? 0 : total;
    }

    private int rewardPoints(Mission mission) {
        return mission.getRewardPoints() == null ? 0 : mission.getRewardPoints();
    }

    private int rewardPoints(Achievement achievement) {
        return achievement.getRewardPoints() == null ? 0 : achievement.getRewardPoints();
    }

    private int milestonePoints(StreakMilestone milestone) {
        if (milestone == null || !REWARD_TYPE_POINTS.equals(milestone.getRewardType())) {
            return 0;
        }
        BigDecimal amount = milestone.getRewardAmount();
        if (amount == null || amount.signum() <= 0) {
            return 0;
        }
        try {
            return amount.intValueExact();
        } catch (ArithmeticException ex) {
            throw new BizException("Unsupported milestone points reward");
        }
    }

    private int basePoints(DailyCheckIn checkIn) {
        if (checkIn == null) {
            return 0;
        }
        return checkIn.getBasePoints() == null ? intValue(checkIn.getRewardPoints()) : checkIn.getBasePoints();
    }

    private BigDecimal rewardMultiplier(DailyCheckIn checkIn) {
        if (checkIn == null || checkIn.getRewardMultiplier() == null) {
            return DEFAULT_REWARD_MULTIPLIER;
        }
        return checkIn.getRewardMultiplier();
    }

    private BigDecimal rewardMultiplier(DailyCheckInReward reward) {
        return reward == null || reward.getMultiplier() == null
                ? DEFAULT_REWARD_MULTIPLIER
                : reward.getMultiplier();
    }

    private int bonusPoints(DailyCheckIn checkIn) {
        return checkIn == null || checkIn.getBonusPoints() == null ? 0 : checkIn.getBonusPoints();
    }

    private int streakBonusPoints(DailyCheckIn checkIn) {
        return checkIn == null || checkIn.getStreakBonusPoints() == null ? 0 : checkIn.getStreakBonusPoints();
    }

    private int intValue(Integer value) {
        return value == null ? 0 : value;
    }

    private void requireUserId(Long userId) {
        if (userId == null || userId < 1) {
            throw new BizException("User id is required");
        }
    }
}
