package ffdd.mission.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class MissionCenterServiceTest {
    private final MissionMapper missionMapper = mock(MissionMapper.class);
    private final UserMissionMapper userMissionMapper = mock(UserMissionMapper.class);
    private final PointsLedgerMapper pointsLedgerMapper = mock(PointsLedgerMapper.class);
    private final DailyCheckInMapper dailyCheckInMapper = mock(DailyCheckInMapper.class);
    private final UserStreakMapper userStreakMapper = mock(UserStreakMapper.class);
    private final AchievementMapper achievementMapper = mock(AchievementMapper.class);
    private final UserAchievementMapper userAchievementMapper = mock(UserAchievementMapper.class);
    private final MissionCenterService service =
            new MissionCenterService(
                    missionMapper,
                    userMissionMapper,
                    pointsLedgerMapper,
                    dailyCheckInMapper,
                    userStreakMapper,
                    achievementMapper,
                    userAchievementMapper);

    @Test
    void listsActiveMissionsWithUserCompletionAndTodayCheckInStatus() {
        LocalDateTime completedAt = LocalDateTime.of(2026, 5, 26, 8, 30);
        Mission daily = mission(3L, "DAILY_CHECK_IN", "Daily Check-in", "DAILY", 30);
        Mission firstReceipt = mission(2L, "FIRST_RECEIPT", "First Compute Receipt", "GROWTH", 200);
        when(missionMapper.selectList(any())).thenReturn(List.of(daily, firstReceipt));
        UserMission completed = new UserMission();
        completed.setMissionId(2L);
        completed.setMissionStatus("COMPLETED");
        completed.setCompletedAt(completedAt);
        when(userMissionMapper.selectList(any())).thenReturn(List.of(completed));
        DailyCheckIn checkIn = new DailyCheckIn();
        checkIn.setUserId(10001L);
        checkIn.setCheckInDate(LocalDate.now());
        when(dailyCheckInMapper.selectOne(any())).thenReturn(checkIn);

        MissionListResponse response = service.listMissions(10001L);

        assertThat(response.getUserId()).isEqualTo(10001L);
        assertThat(response.getCompletedMissions()).isEqualTo(2);
        assertThat(response.isTodayCheckedIn()).isTrue();
        assertThat(response.getRecords()).extracting(MissionItemResponse::getMissionCode)
                .containsExactly("DAILY_CHECK_IN", "FIRST_RECEIPT");
        assertThat(response.getRecords().get(0).isCompleted()).isTrue();
        assertThat(response.getRecords().get(1).getCompletedAt()).isEqualTo(completedAt);
    }

    @Test
    void createsDailyCheckInOnceAndWritesPointsLedger() {
        Mission daily = mission(3L, "DAILY_CHECK_IN", "Daily Check-in", "DAILY", 30);
        when(missionMapper.selectOne(any())).thenReturn(daily);
        when(pointsLedgerMapper.sumPointsByUser(10001L)).thenReturn(120);

        DailyCheckInResponse response = service.dailyCheckIn(10001L);

        assertThat(response.isCompleted()).isTrue();
        assertThat(response.getAwardedPoints()).isEqualTo(30);
        assertThat(response.getTotalPoints()).isEqualTo(150);
        assertThat(response.getStatus()).isEqualTo("COMPLETED");
        assertThat(response.getCurrentStreak()).isEqualTo(1);
        assertThat(response.getLongestStreak()).isEqualTo(1);

        ArgumentCaptor<DailyCheckIn> checkInCaptor = ArgumentCaptor.forClass(DailyCheckIn.class);
        verify(dailyCheckInMapper).insert(checkInCaptor.capture());
        assertThat(checkInCaptor.getValue().getUserId()).isEqualTo(10001L);
        assertThat(checkInCaptor.getValue().getMissionId()).isEqualTo(3L);
        assertThat(checkInCaptor.getValue().getRewardPoints()).isEqualTo(30);

        ArgumentCaptor<UserStreak> streakCaptor = ArgumentCaptor.forClass(UserStreak.class);
        verify(userStreakMapper).insert(streakCaptor.capture());
        assertThat(streakCaptor.getValue().getCurrentStreak()).isEqualTo(1);
        assertThat(streakCaptor.getValue().getLongestStreak()).isEqualTo(1);
        assertThat(streakCaptor.getValue().getStreakSavers()).isEqualTo(1);

        ArgumentCaptor<PointsLedger> ledgerCaptor = ArgumentCaptor.forClass(PointsLedger.class);
        verify(pointsLedgerMapper).insert(ledgerCaptor.capture());
        assertThat(ledgerCaptor.getValue().getBizNo())
                .startsWith("CHECKIN-DAILY-10001-");
        assertThat(ledgerCaptor.getValue().getBizType()).isEqualTo("DAILY_CHECK_IN");
        assertThat(ledgerCaptor.getValue().getBalanceAfter()).isEqualTo(150);
    }

    @Test
    void returnsAlreadyCheckedInWithoutWritingDuplicateLedger() {
        Mission daily = mission(3L, "DAILY_CHECK_IN", "Daily Check-in", "DAILY", 30);
        DailyCheckIn existing = new DailyCheckIn();
        existing.setUserId(10001L);
        existing.setCheckInDate(LocalDate.now());
        existing.setRewardPoints(30);
        when(missionMapper.selectOne(any())).thenReturn(daily);
        when(dailyCheckInMapper.selectOne(any())).thenReturn(existing);
        when(pointsLedgerMapper.sumPointsByUser(10001L)).thenReturn(150);

        DailyCheckInResponse response = service.dailyCheckIn(10001L);

        assertThat(response.isCompleted()).isFalse();
        assertThat(response.getAwardedPoints()).isZero();
        assertThat(response.getTotalPoints()).isEqualTo(150);
        assertThat(response.getStatus()).isEqualTo("ALREADY_CHECKED_IN");
        verify(dailyCheckInMapper, never()).insert(any(DailyCheckIn.class));
        verify(pointsLedgerMapper, never()).insert(any(PointsLedger.class));
    }

    @Test
    void dailyCheckInContinuesStreakAndUnlocksThresholdAchievement() {
        Mission daily = mission(3L, "DAILY_CHECK_IN", "Daily Check-in", "DAILY", 30);
        UserStreak streak = streak(9L, 10001L, 2, 5, LocalDate.now().minusDays(1));
        Achievement achievement = achievement(11L, "STREAK_3", "3-Day Streak", 3, 5);
        when(missionMapper.selectOne(any())).thenReturn(daily);
        when(userStreakMapper.selectOne(any())).thenReturn(streak);
        when(pointsLedgerMapper.sumPointsByUser(10001L)).thenReturn(120);
        when(achievementMapper.selectList(any())).thenReturn(List.of(achievement));

        DailyCheckInResponse response = service.dailyCheckIn(10001L);

        assertThat(response.getCurrentStreak()).isEqualTo(3);
        assertThat(response.getLongestStreak()).isEqualTo(5);
        assertThat(response.getUnlockedAchievements())
                .extracting(AchievementItemResponse::getAchievementCode)
                .containsExactly("STREAK_3");

        ArgumentCaptor<UserStreak> streakPatch = ArgumentCaptor.forClass(UserStreak.class);
        verify(userStreakMapper).updateById(streakPatch.capture());
        assertThat(streakPatch.getValue().getId()).isEqualTo(9L);
        assertThat(streakPatch.getValue().getCurrentStreak()).isEqualTo(3);
        assertThat(streakPatch.getValue().getLongestStreak()).isEqualTo(5);

        ArgumentCaptor<UserAchievement> userAchievementCaptor = ArgumentCaptor.forClass(UserAchievement.class);
        verify(userAchievementMapper).insert(userAchievementCaptor.capture());
        assertThat(userAchievementCaptor.getValue().getUserId()).isEqualTo(10001L);
        assertThat(userAchievementCaptor.getValue().getAchievementCode()).isEqualTo("STREAK_3");
        assertThat(userAchievementCaptor.getValue().getAchievementStatus()).isEqualTo("UNLOCKED");
    }

    @Test
    void dailyCheckInResetsStreakAfterGap() {
        Mission daily = mission(3L, "DAILY_CHECK_IN", "Daily Check-in", "DAILY", 30);
        UserStreak streak = streak(9L, 10001L, 4, 4, LocalDate.now().minusDays(3));
        when(missionMapper.selectOne(any())).thenReturn(daily);
        when(userStreakMapper.selectOne(any())).thenReturn(streak);

        DailyCheckInResponse response = service.dailyCheckIn(10001L);

        assertThat(response.getCurrentStreak()).isEqualTo(1);
        assertThat(response.getLongestStreak()).isEqualTo(4);
    }

    @Test
    void listsAchievementsWithStreakProgressAndUserStatus() {
        Achievement achievement = achievement(11L, "STREAK_3", "3-Day Streak", 3, 5);
        UserAchievement unlocked = userAchievement(21L, 10001L, 11L, "STREAK_3", "UNLOCKED");
        when(achievementMapper.selectList(any())).thenReturn(List.of(achievement));
        when(userAchievementMapper.selectList(any())).thenReturn(List.of(unlocked));
        when(userStreakMapper.selectOne(any())).thenReturn(streak(9L, 10001L, 2, 2, LocalDate.now()));

        List<AchievementItemResponse> achievements = service.listAchievements(10001L);

        assertThat(achievements).hasSize(1);
        assertThat(achievements.get(0).getStatus()).isEqualTo("UNLOCKED");
        assertThat(achievements.get(0).getProgress()).isEqualTo(2);
    }

    @Test
    void claimsUnlockedAchievementOnceAndWritesPointsLedger() {
        Achievement achievement = achievement(11L, "STREAK_3", "3-Day Streak", 3, 5);
        UserAchievement unlocked = userAchievement(21L, 10001L, 11L, "STREAK_3", "UNLOCKED");
        when(achievementMapper.selectOne(any())).thenReturn(achievement);
        when(userAchievementMapper.selectOne(any())).thenReturn(unlocked);
        when(pointsLedgerMapper.sumPointsByUser(10001L)).thenReturn(150);

        AchievementClaimResponse response = service.claimAchievement(10001L, "STREAK_3");

        assertThat(response.isClaimed()).isTrue();
        assertThat(response.getAwardedPoints()).isEqualTo(5);
        assertThat(response.getTotalPoints()).isEqualTo(155);
        assertThat(response.getStatus()).isEqualTo("CLAIMED");

        ArgumentCaptor<PointsLedger> ledgerCaptor = ArgumentCaptor.forClass(PointsLedger.class);
        verify(pointsLedgerMapper).insert(ledgerCaptor.capture());
        assertThat(ledgerCaptor.getValue().getBizNo()).isEqualTo("ACHIEVEMENT-STREAK_3-10001");
        assertThat(ledgerCaptor.getValue().getBizType()).isEqualTo("ACHIEVEMENT");
        assertThat(ledgerCaptor.getValue().getBalanceAfter()).isEqualTo(155);

        ArgumentCaptor<UserAchievement> patchCaptor = ArgumentCaptor.forClass(UserAchievement.class);
        verify(userAchievementMapper).updateById(patchCaptor.capture());
        assertThat(patchCaptor.getValue().getId()).isEqualTo(21L);
        assertThat(patchCaptor.getValue().getAchievementStatus()).isEqualTo("CLAIMED");
    }

    @Test
    void claimAchievementIsIdempotentWhenAlreadyClaimed() {
        Achievement achievement = achievement(11L, "STREAK_3", "3-Day Streak", 3, 5);
        UserAchievement claimed = userAchievement(21L, 10001L, 11L, "STREAK_3", "CLAIMED");
        when(achievementMapper.selectOne(any())).thenReturn(achievement);
        when(userAchievementMapper.selectOne(any())).thenReturn(claimed);
        when(pointsLedgerMapper.sumPointsByUser(10001L)).thenReturn(155);

        AchievementClaimResponse response = service.claimAchievement(10001L, "STREAK_3");

        assertThat(response.isClaimed()).isFalse();
        assertThat(response.getAwardedPoints()).isZero();
        assertThat(response.getTotalPoints()).isEqualTo(155);
        assertThat(response.getStatus()).isEqualTo("ALREADY_CLAIMED");
        verify(pointsLedgerMapper, never()).insert(any(PointsLedger.class));
        verify(userAchievementMapper, never()).updateById(any(UserAchievement.class));
    }

    @Test
    void rejectsClaimForLockedAchievement() {
        Achievement achievement = achievement(11L, "STREAK_3", "3-Day Streak", 3, 5);
        when(achievementMapper.selectOne(any())).thenReturn(achievement);

        assertThatThrownBy(() -> service.claimAchievement(10001L, "STREAK_3"))
                .isInstanceOf(BizException.class)
                .hasMessage("Achievement is not unlocked");
        verify(pointsLedgerMapper, never()).insert(any(PointsLedger.class));
    }

    @Test
    void returnsCurrentStreakSummary() {
        when(userStreakMapper.selectOne(any())).thenReturn(streak(9L, 10001L, 6, 8, LocalDate.now()));
        when(achievementMapper.selectList(any())).thenReturn(List.of(achievement(12L, "STREAK_7", "7-Day Streak", 7, 15)));

        StreakSummaryResponse response = service.streakSummary(10001L);

        assertThat(response.getCurrentStreak()).isEqualTo(6);
        assertThat(response.getLongestStreak()).isEqualTo(8);
        assertThat(response.getNextMilestoneDays()).isEqualTo(7);
        assertThat(response.isCheckedInToday()).isTrue();
    }

    @Test
    void summarizesTotalPointsWithRecentLedgerPage() {
        PointsLedger ledger = new PointsLedger();
        ledger.setUserId(10001L);
        ledger.setBizNo("MISSION-FIRST_RECEIPT-10001");
        ledger.setBizType("MISSION");
        ledger.setPoints(200);
        when(pointsLedgerMapper.sumPointsByUser(10001L)).thenReturn(230);
        when(pointsLedgerMapper.selectPage(any(Page.class), any())).thenAnswer(invocation -> {
            Page<PointsLedger> page = invocation.getArgument(0);
            page.setTotal(1);
            page.setRecords(List.of(ledger));
            return page;
        });

        PointsSummaryResponse response = service.pointsSummary(10001L, 1, 20);

        assertThat(response.getUserId()).isEqualTo(10001L);
        assertThat(response.getTotalPoints()).isEqualTo(230);
        PageResult<PointsLedger> recentLedgers = response.getRecentLedgers();
        assertThat(recentLedgers.getTotal()).isEqualTo(1);
        assertThat(recentLedgers.getPageNum()).isEqualTo(1);
        assertThat(recentLedgers.getPageSize()).isEqualTo(20);
        assertThat(recentLedgers.getRecords()).containsExactly(ledger);
    }

    @Test
    void rejectsMissingUserId() {
        assertThatThrownBy(() -> service.pointsSummary(null, 1, 20))
                .isInstanceOf(BizException.class)
                .hasMessage("User id is required");
        verify(pointsLedgerMapper, never()).selectPage(any(Page.class), any());
    }

    private Mission mission(Long id, String code, String name, String type, int rewardPoints) {
        Mission mission = new Mission();
        mission.setId(id);
        mission.setMissionCode(code);
        mission.setMissionName(name);
        mission.setMissionType(type);
        mission.setRewardPoints(rewardPoints);
        mission.setStatus(1);
        mission.setIsDeleted(0);
        return mission;
    }

    private UserStreak streak(Long id, Long userId, int currentStreak, int longestStreak, LocalDate lastCheckInDate) {
        UserStreak streak = new UserStreak();
        streak.setId(id);
        streak.setUserId(userId);
        streak.setCurrentStreak(currentStreak);
        streak.setLongestStreak(longestStreak);
        streak.setStreakSavers(1);
        streak.setLastCheckInDate(lastCheckInDate);
        streak.setIsDeleted(0);
        return streak;
    }

    private Achievement achievement(Long id, String code, String name, int triggerValue, int rewardPoints) {
        Achievement achievement = new Achievement();
        achievement.setId(id);
        achievement.setAchievementCode(code);
        achievement.setAchievementName(name);
        achievement.setCategory("LOYALTY");
        achievement.setTriggerType("STREAK_DAYS");
        achievement.setTriggerValue(triggerValue);
        achievement.setRewardPoints(rewardPoints);
        achievement.setStatus(1);
        achievement.setIsDeleted(0);
        return achievement;
    }

    private UserAchievement userAchievement(
            Long id,
            Long userId,
            Long achievementId,
            String achievementCode,
            String status) {
        UserAchievement userAchievement = new UserAchievement();
        userAchievement.setId(id);
        userAchievement.setUserId(userId);
        userAchievement.setAchievementId(achievementId);
        userAchievement.setAchievementCode(achievementCode);
        userAchievement.setAchievementStatus(status);
        userAchievement.setUnlockedAt(LocalDateTime.now());
        userAchievement.setIsDeleted(0);
        return userAchievement;
    }
}
