package ffdd.mission.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import ffdd.common.api.PageResult;
import ffdd.common.exception.BizException;
import ffdd.mission.domain.Achievement;
import ffdd.mission.domain.DailyCheckIn;
import ffdd.mission.domain.Mission;
import ffdd.mission.domain.PointsLedger;
import ffdd.mission.domain.UserAchievement;
import ffdd.mission.domain.UserMission;
import ffdd.mission.domain.UserStreak;
import ffdd.mission.dto.AchievementClaimResponse;
import ffdd.mission.dto.AchievementItemResponse;
import ffdd.mission.dto.DailyCheckInResponse;
import ffdd.mission.dto.MissionItemResponse;
import ffdd.mission.dto.MissionListResponse;
import ffdd.mission.dto.PointsSummaryResponse;
import ffdd.mission.dto.StreakSummaryResponse;
import ffdd.mission.mapper.AchievementMapper;
import ffdd.mission.mapper.DailyCheckInMapper;
import ffdd.mission.mapper.MissionMapper;
import ffdd.mission.mapper.PointsLedgerMapper;
import ffdd.mission.mapper.UserAchievementMapper;
import ffdd.mission.mapper.UserMissionMapper;
import ffdd.mission.mapper.UserStreakMapper;
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
    private static final String BIZ_TYPE_DAILY_CHECK_IN = "DAILY_CHECK_IN";
    private static final String BIZ_TYPE_ACHIEVEMENT = "ACHIEVEMENT";
    private static final String TRIGGER_STREAK_DAYS = "STREAK_DAYS";
    private static final int INITIAL_STREAK_SAVERS = 1;
    private static final Pattern ACHIEVEMENT_CODE_PATTERN = Pattern.compile("[A-Z0-9_]{1,64}");

    private final MissionMapper missionMapper;
    private final UserMissionMapper userMissionMapper;
    private final PointsLedgerMapper pointsLedgerMapper;
    private final DailyCheckInMapper dailyCheckInMapper;
    private final UserStreakMapper userStreakMapper;
    private final AchievementMapper achievementMapper;
    private final UserAchievementMapper userAchievementMapper;

    public MissionCenterService(
            MissionMapper missionMapper,
            UserMissionMapper userMissionMapper,
            PointsLedgerMapper pointsLedgerMapper,
            DailyCheckInMapper dailyCheckInMapper,
            UserStreakMapper userStreakMapper,
            AchievementMapper achievementMapper,
            UserAchievementMapper userAchievementMapper) {
        this.missionMapper = missionMapper;
        this.userMissionMapper = userMissionMapper;
        this.pointsLedgerMapper = pointsLedgerMapper;
        this.dailyCheckInMapper = dailyCheckInMapper;
        this.userStreakMapper = userStreakMapper;
        this.achievementMapper = achievementMapper;
        this.userAchievementMapper = userAchievementMapper;
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
                    currentTotalPoints,
                    STATUS_ALREADY_CHECKED_IN,
                    streak,
                    List.of());
        }

        int rewardPoints = rewardPoints(mission);
        DailyCheckIn checkIn = new DailyCheckIn();
        checkIn.setUserId(userId);
        checkIn.setMissionId(mission.getId());
        checkIn.setCheckInDate(today);
        checkIn.setRewardPoints(rewardPoints);
        checkIn.setIsDeleted(0);
        try {
            dailyCheckInMapper.insert(checkIn);
        } catch (DuplicateKeyException ex) {
            return dailyCheckInResponse(
                    userId,
                    today,
                    false,
                    0,
                    totalPoints(userId),
                    STATUS_ALREADY_CHECKED_IN,
                    findUserStreak(userId),
                    List.of());
        }

        UserStreak streak = advanceStreak(userId, today);
        List<AchievementItemResponse> unlockedAchievements = unlockStreakAchievements(userId, streak);
        int balanceAfter = currentTotalPoints + rewardPoints;
        insertPointsLedgerIfNeeded(userId, dailyCheckInBizNo(userId, today), BIZ_TYPE_DAILY_CHECK_IN, rewardPoints, balanceAfter);
        return dailyCheckInResponse(
                userId,
                today,
                true,
                rewardPoints,
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
        int currentStreak = currentStreak(streak);
        return new StreakSummaryResponse(
                userId,
                currentStreak,
                intValue(streak == null ? null : streak.getLongestStreak()),
                intValue(streak == null ? null : streak.getStreakSavers()),
                streak == null ? null : streak.getLastCheckInDate(),
                nextStreakMilestone(currentStreak),
                checkedInToday(streak));
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

    private DailyCheckIn findDailyCheckIn(Long userId, LocalDate date) {
        return dailyCheckInMapper.selectOne(new LambdaQueryWrapper<DailyCheckIn>()
                .eq(DailyCheckIn::getUserId, userId)
                .eq(DailyCheckIn::getCheckInDate, date)
                .eq(DailyCheckIn::getIsDeleted, 0));
    }

    private UserStreak advanceStreak(Long userId, LocalDate today) {
        UserStreak existing = findUserStreak(userId);
        if (existing == null) {
            UserStreak created = new UserStreak();
            created.setUserId(userId);
            created.setCurrentStreak(1);
            created.setLongestStreak(1);
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

        int nextStreak = today.minusDays(1).equals(existing.getLastCheckInDate())
                ? intValue(existing.getCurrentStreak()) + 1
                : 1;
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

    private List<UserAchievement> userAchievements(Long userId) {
        List<UserAchievement> achievements = userAchievementMapper.selectList(new LambdaQueryWrapper<UserAchievement>()
                .eq(UserAchievement::getUserId, userId)
                .eq(UserAchievement::getIsDeleted, 0));
        return achievements == null ? List.of() : achievements;
    }

    private UserAchievement findUserAchievement(Long userId, String achievementCode) {
        return userAchievementMapper.selectOne(new LambdaQueryWrapper<UserAchievement>()
                .eq(UserAchievement::getUserId, userId)
                .eq(UserAchievement::getAchievementCode, achievementCode)
                .eq(UserAchievement::getIsDeleted, 0));
    }

    private AchievementItemResponse toAchievementItem(
            Achievement achievement,
            UserAchievement userAchievement,
            int currentStreak) {
        return new AchievementItemResponse(
                achievement.getId(),
                achievement.getAchievementCode(),
                achievement.getAchievementName(),
                achievement.getCategory(),
                achievement.getTriggerType(),
                intValue(achievement.getTriggerValue()),
                rewardPoints(achievement),
                userAchievement == null ? STATUS_LOCKED : userAchievement.getAchievementStatus(),
                TRIGGER_STREAK_DAYS.equals(achievement.getTriggerType())
                        ? Math.min(currentStreak, intValue(achievement.getTriggerValue()))
                        : 0,
                userAchievement == null ? null : userAchievement.getUnlockedAt(),
                userAchievement == null ? null : userAchievement.getClaimedAt());
    }

    private DailyCheckInResponse dailyCheckInResponse(
            Long userId,
            LocalDate checkInDate,
            boolean completed,
            int awardedPoints,
            int totalPoints,
            String status,
            UserStreak streak,
            List<AchievementItemResponse> unlockedAchievements) {
        return new DailyCheckInResponse(
                userId,
                checkInDate,
                completed,
                awardedPoints,
                totalPoints,
                status,
                intValue(streak == null ? null : streak.getCurrentStreak()),
                intValue(streak == null ? null : streak.getLongestStreak()),
                unlockedAchievements == null ? List.of() : unlockedAchievements);
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

    private int nextStreakMilestone(int currentStreak) {
        return activeStreakAchievements().stream()
                .map(Achievement::getTriggerValue)
                .filter(value -> value != null && value > currentStreak)
                .findFirst()
                .orElse(0);
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

    private int intValue(Integer value) {
        return value == null ? 0 : value;
    }

    private void requireUserId(Long userId) {
        if (userId == null || userId < 1) {
            throw new BizException("User id is required");
        }
    }
}
