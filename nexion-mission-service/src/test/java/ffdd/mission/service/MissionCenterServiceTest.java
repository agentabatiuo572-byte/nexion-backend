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
    private final StreakPowerUpMapper streakPowerUpMapper = mock(StreakPowerUpMapper.class);
    private final UserStreakPowerUpMapper userStreakPowerUpMapper = mock(UserStreakPowerUpMapper.class);
    private final StreakMilestoneMapper streakMilestoneMapper = mock(StreakMilestoneMapper.class);
    private final UserStreakMilestoneMapper userStreakMilestoneMapper = mock(UserStreakMilestoneMapper.class);
    private final DailyCheckInRewardPolicy rewardPolicy = mock(DailyCheckInRewardPolicy.class);
    private final MissionCenterService service =
            new MissionCenterService(
                    missionMapper,
                    userMissionMapper,
                    pointsLedgerMapper,
                    dailyCheckInMapper,
                    userStreakMapper,
                    achievementMapper,
                    userAchievementMapper,
                    streakPowerUpMapper,
                    userStreakPowerUpMapper,
                    streakMilestoneMapper,
                    userStreakMilestoneMapper,
                    rewardPolicy);

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
    void createsMissionOpsTaskWithNormalizedCodeAndDefaults() {
        MissionRequest request = new MissionRequest();
        request.setMissionCode("image_inference");
        request.setMissionName("Image inference");
        request.setMissionType("growth");
        request.setRewardPoints(80);

        Mission created = service.createMission(request);

        ArgumentCaptor<Mission> captor = ArgumentCaptor.forClass(Mission.class);
        verify(missionMapper).insert(captor.capture());
        assertThat(captor.getValue().getMissionCode()).isEqualTo("IMAGE_INFERENCE");
        assertThat(captor.getValue().getMissionType()).isEqualTo("GROWTH");
        assertThat(captor.getValue().getRewardPoints()).isEqualTo(80);
        assertThat(captor.getValue().getStatus()).isEqualTo(1);
        assertThat(captor.getValue().getIsDeleted()).isZero();
        assertThat(created.getMissionCode()).isEqualTo("IMAGE_INFERENCE");
    }

    @Test
    void updatesMissionOpsTaskAndReturnsFreshRecord() {
        Mission existing = mission(9L, "FIRST_RECEIPT", "First Receipt", "GROWTH", 200);
        Mission updated = mission(9L, "FIRST_RECEIPT", "First Compute Receipt", "COMPUTE", 260);
        when(missionMapper.selectById(9L)).thenReturn(existing, updated);

        MissionUpdateRequest request = new MissionUpdateRequest();
        request.setMissionName("First Compute Receipt");
        request.setMissionType("compute");
        request.setRewardPoints(260);
        request.setStatus(0);

        Mission result = service.updateMission(9L, request);

        ArgumentCaptor<Mission> captor = ArgumentCaptor.forClass(Mission.class);
        verify(missionMapper).updateById(captor.capture());
        assertThat(captor.getValue().getId()).isEqualTo(9L);
        assertThat(captor.getValue().getMissionType()).isEqualTo("COMPUTE");
        assertThat(captor.getValue().getRewardPoints()).isEqualTo(260);
        assertThat(captor.getValue().getStatus()).isZero();
        assertThat(result.getMissionName()).isEqualTo("First Compute Receipt");
    }

    @Test
    void deletesMissionOpsTaskBySoftDelete() {
        Mission existing = mission(9L, "FIRST_RECEIPT", "First Receipt", "GROWTH", 200);
        when(missionMapper.selectById(9L)).thenReturn(existing);

        service.deleteMission(9L);

        ArgumentCaptor<Mission> captor = ArgumentCaptor.forClass(Mission.class);
        verify(missionMapper).updateById(captor.capture());
        assertThat(captor.getValue().getId()).isEqualTo(9L);
        assertThat(captor.getValue().getIsDeleted()).isEqualTo(1);
    }

    @Test
    void pagesAchievementOpsConfigs() {
        Page<Achievement> page = new Page<>(1, 20);
        page.setRecords(List.of(achievement(11L, "STREAK_3", "3-Day Streak", 3, 5)));
        page.setTotal(1);
        when(achievementMapper.selectPage(any(Page.class), any())).thenReturn(page);

        PageResult<Achievement> result = service.pageAchievementsOps("loyalty", "1", "streak", 1, 20);

        assertThat(result.getTotal()).isEqualTo(1);
        assertThat(result.getRecords()).extracting(Achievement::getAchievementCode).containsExactly("STREAK_3");
    }

    @Test
    void createsAchievementOpsConfigWithNormalizedFieldsAndDefaults() {
        AchievementRequest request = new AchievementRequest();
        request.setAchievementCode("streak_7");
        request.setAchievementName("7-Day Streak");
        request.setDescription("Keep a 7 day streak.");
        request.setCategory("streak");
        request.setIconKey("flame");
        request.setAccentColor("#00D1FF");
        request.setTriggerType("streak_days");
        request.setTriggerValue(7);
        request.setRewardPoints(100);
        request.setSortOrder(20);

        Achievement created = service.createAchievement(request);

        ArgumentCaptor<Achievement> captor = ArgumentCaptor.forClass(Achievement.class);
        verify(achievementMapper).insert(captor.capture());
        assertThat(captor.getValue().getAchievementCode()).isEqualTo("STREAK_7");
        assertThat(captor.getValue().getDescription()).isEqualTo("Keep a 7 day streak.");
        assertThat(captor.getValue().getCategory()).isEqualTo("STREAK");
        assertThat(captor.getValue().getIconKey()).isEqualTo("flame");
        assertThat(captor.getValue().getAccentColor()).isEqualTo("#00D1FF");
        assertThat(captor.getValue().getTriggerType()).isEqualTo("STREAK_DAYS");
        assertThat(captor.getValue().getTriggerValue()).isEqualTo(7);
        assertThat(captor.getValue().getRewardPoints()).isEqualTo(100);
        assertThat(captor.getValue().getSortOrder()).isEqualTo(20);
        assertThat(captor.getValue().getStatus()).isEqualTo(1);
        assertThat(captor.getValue().getIsDeleted()).isZero();
        assertThat(created.getAchievementCode()).isEqualTo("STREAK_7");
    }

    @Test
    void updatesAchievementOpsConfigAndReturnsFreshRecord() {
        Achievement existing = achievement(11L, "STREAK_3", "3-Day Streak", 3, 5);
        Achievement updated = achievement(11L, "STREAK_3", "3-Day Streak Plus", 7, 120);
        when(achievementMapper.selectById(11L)).thenReturn(existing, updated);

        AchievementUpdateRequest request = new AchievementUpdateRequest();
        request.setAchievementName("3-Day Streak Plus");
        request.setDescription("Updated copy");
        request.setIconKey("award");
        request.setAccentColor("#9EDC1D");
        request.setTriggerValue(7);
        request.setRewardPoints(120);
        request.setSortOrder(30);
        request.setStatus(0);

        Achievement result = service.updateAchievement(11L, request);

        ArgumentCaptor<Achievement> captor = ArgumentCaptor.forClass(Achievement.class);
        verify(achievementMapper).updateById(captor.capture());
        assertThat(captor.getValue().getId()).isEqualTo(11L);
        assertThat(captor.getValue().getAchievementName()).isEqualTo("3-Day Streak Plus");
        assertThat(captor.getValue().getDescription()).isEqualTo("Updated copy");
        assertThat(captor.getValue().getIconKey()).isEqualTo("award");
        assertThat(captor.getValue().getAccentColor()).isEqualTo("#9EDC1D");
        assertThat(captor.getValue().getTriggerValue()).isEqualTo(7);
        assertThat(captor.getValue().getRewardPoints()).isEqualTo(120);
        assertThat(captor.getValue().getSortOrder()).isEqualTo(30);
        assertThat(captor.getValue().getStatus()).isZero();
        assertThat(result.getRewardPoints()).isEqualTo(120);
    }

    @Test
    void deletesAchievementOpsConfigBySoftDelete() {
        Achievement existing = achievement(11L, "STREAK_3", "3-Day Streak", 3, 5);
        when(achievementMapper.selectById(11L)).thenReturn(existing);

        service.deleteAchievement(11L);

        ArgumentCaptor<Achievement> captor = ArgumentCaptor.forClass(Achievement.class);
        verify(achievementMapper).updateById(captor.capture());
        assertThat(captor.getValue().getId()).isEqualTo(11L);
        assertThat(captor.getValue().getIsDeleted()).isEqualTo(1);
    }

    @Test
    void createsDailyCheckInOnceAndWritesPointsLedger() {
        Mission daily = mission(3L, "DAILY_CHECK_IN", "Daily Check-in", "DAILY", 30);
        when(missionMapper.selectOne(any())).thenReturn(daily);
        when(pointsLedgerMapper.sumPointsByUser(10001L)).thenReturn(120);
        when(rewardPolicy.roll(30)).thenReturn(reward(30, "1.00", 30));

        DailyCheckInResponse response = service.dailyCheckIn(10001L);

        assertThat(response.isCompleted()).isTrue();
        assertThat(response.getAwardedPoints()).isEqualTo(30);
        assertThat(response.getTotalPoints()).isEqualTo(150);
        assertThat(response.getStatus()).isEqualTo("COMPLETED");
        assertThat(response.getBasePoints()).isEqualTo(30);
        assertThat(response.getRewardMultiplier()).isEqualByComparingTo("1.00");
        assertThat(response.getBonusPoints()).isZero();
        assertThat(response.getStreakBonusPoints()).isZero();
        assertThat(response.getCurrentStreak()).isEqualTo(1);
        assertThat(response.getLongestStreak()).isEqualTo(1);

        ArgumentCaptor<DailyCheckIn> checkInCaptor = ArgumentCaptor.forClass(DailyCheckIn.class);
        verify(dailyCheckInMapper).insert(checkInCaptor.capture());
        assertThat(checkInCaptor.getValue().getUserId()).isEqualTo(10001L);
        assertThat(checkInCaptor.getValue().getMissionId()).isEqualTo(3L);
        assertThat(checkInCaptor.getValue().getRewardPoints()).isEqualTo(30);
        assertThat(checkInCaptor.getValue().getBasePoints()).isEqualTo(30);
        assertThat(checkInCaptor.getValue().getRewardMultiplier()).isEqualByComparingTo("1.00");
        assertThat(checkInCaptor.getValue().getBonusPoints()).isZero();
        assertThat(checkInCaptor.getValue().getStreakBonusPoints()).isZero();

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
        when(rewardPolicy.roll(30)).thenReturn(reward(30, "1.00", 30));

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
        when(rewardPolicy.roll(30)).thenReturn(reward(30, "1.00", 30));

        DailyCheckInResponse response = service.dailyCheckIn(10001L);

        assertThat(response.getCurrentStreak()).isEqualTo(1);
        assertThat(response.getLongestStreak()).isEqualTo(4);
    }

    @Test
    void dailyCheckInAppliesLuckyMultiplierAndSevenDayBonus() {
        Mission daily = mission(3L, "DAILY_CHECK_IN", "Daily Check-in", "DAILY", 30);
        UserStreak streak = streak(9L, 10001L, 6, 6, LocalDate.now().minusDays(1));
        when(missionMapper.selectOne(any())).thenReturn(daily);
        when(userStreakMapper.selectOne(any())).thenReturn(streak);
        when(pointsLedgerMapper.sumPointsByUser(10001L)).thenReturn(120);
        when(rewardPolicy.roll(30)).thenReturn(reward(30, "2.00", 60));

        DailyCheckInResponse response = service.dailyCheckIn(10001L);

        assertThat(response.getAwardedPoints()).isEqualTo(65);
        assertThat(response.getTotalPoints()).isEqualTo(185);
        assertThat(response.getBasePoints()).isEqualTo(30);
        assertThat(response.getRewardMultiplier()).isEqualByComparingTo("2.00");
        assertThat(response.getBonusPoints()).isEqualTo(35);
        assertThat(response.getStreakBonusPoints()).isEqualTo(5);
        assertThat(response.getCurrentStreak()).isEqualTo(7);

        ArgumentCaptor<DailyCheckIn> checkInCaptor = ArgumentCaptor.forClass(DailyCheckIn.class);
        verify(dailyCheckInMapper).insert(checkInCaptor.capture());
        assertThat(checkInCaptor.getValue().getRewardPoints()).isEqualTo(65);
        assertThat(checkInCaptor.getValue().getBasePoints()).isEqualTo(30);
        assertThat(checkInCaptor.getValue().getRewardMultiplier()).isEqualByComparingTo("2.00");
        assertThat(checkInCaptor.getValue().getBonusPoints()).isEqualTo(35);
        assertThat(checkInCaptor.getValue().getStreakBonusPoints()).isEqualTo(5);

        ArgumentCaptor<PointsLedger> ledgerCaptor = ArgumentCaptor.forClass(PointsLedger.class);
        verify(pointsLedgerMapper).insert(ledgerCaptor.capture());
        assertThat(ledgerCaptor.getValue().getPoints()).isEqualTo(65);
        assertThat(ledgerCaptor.getValue().getBalanceAfter()).isEqualTo(185);
    }

    @Test
    void usesStreakSaverToRecoverBrokenStreak() {
        UserStreak streak = streak(9L, 10001L, 4, 18, LocalDate.now().minusDays(3));
        when(userStreakMapper.selectOne(any())).thenReturn(streak);
        when(userStreakMapper.update(any(UserStreak.class), any())).thenReturn(1);

        StreakSaverResponse response = service.useStreakSaver(10001L);

        assertThat(response.isRestored()).isTrue();
        assertThat(response.getStatus()).isEqualTo("RESTORED");
        assertThat(response.getCurrentStreak()).isEqualTo(18);
        assertThat(response.getLongestStreak()).isEqualTo(18);
        assertThat(response.getRemainingStreakSavers()).isZero();
        assertThat(response.getLastCheckInDate()).isEqualTo(LocalDate.now().minusDays(1));
        assertThat(response.getRecoverableStreak()).isEqualTo(18);
        assertThat(response.isCheckedInToday()).isFalse();

        ArgumentCaptor<UserStreak> patchCaptor = ArgumentCaptor.forClass(UserStreak.class);
        verify(userStreakMapper).update(patchCaptor.capture(), any());
        assertThat(patchCaptor.getValue().getId()).isEqualTo(9L);
        assertThat(patchCaptor.getValue().getCurrentStreak()).isEqualTo(18);
        assertThat(patchCaptor.getValue().getStreakSavers()).isZero();
        assertThat(patchCaptor.getValue().getLastCheckInDate()).isEqualTo(LocalDate.now().minusDays(1));
    }

    @Test
    void doesNotUseStreakSaverWhenNoSaversRemain() {
        UserStreak streak = streak(9L, 10001L, 0, 18, LocalDate.now().minusDays(3));
        streak.setStreakSavers(0);
        when(userStreakMapper.selectOne(any())).thenReturn(streak);

        StreakSaverResponse response = service.useStreakSaver(10001L);

        assertThat(response.isRestored()).isFalse();
        assertThat(response.getStatus()).isEqualTo("NO_SAVERS");
        assertThat(response.getRemainingStreakSavers()).isZero();
        assertThat(response.getRecoverableStreak()).isEqualTo(18);
        verify(userStreakMapper, never()).update(any(UserStreak.class), any());
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
        assertThat(achievements.get(0).getDescription()).isEqualTo("3-Day Streak description");
        assertThat(achievements.get(0).getIconKey()).isEqualTo("award");
        assertThat(achievements.get(0).getAccentColor()).isEqualTo("#9EDC1D");
        assertThat(achievements.get(0).getSortOrder()).isEqualTo(10);
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
        assertThat(response.isStreakBroken()).isFalse();
        assertThat(response.isSaverAvailable()).isFalse();
        assertThat(response.getRecoverableStreak()).isZero();
    }

    @Test
    void listsTopStreakersWithBoundedLimit() {
        StreakLeaderboardEntryResponse first = streakLeaderboardEntry(
                1, 10002L, "Daniel_K", "https://cdn.example/avatar.png", "+1", 41, 42, LocalDate.now(), true);
        StreakLeaderboardEntryResponse second = streakLeaderboardEntry(
                2, 10003L, "Nexion_0003", null, "+65", 32, 40, LocalDate.now().minusDays(1), false);
        when(userStreakMapper.selectTopStreakers(any(), eq(100))).thenReturn(List.of(first, second));

        List<StreakLeaderboardEntryResponse> response = service.topStreakers(10001L, 500);

        assertThat(response).containsExactly(first, second);
        verify(userStreakMapper).selectTopStreakers(any(LocalDate.class), eq(100));
    }

    @Test
    void rejectsTopStreakersWithoutUserId() {
        assertThatThrownBy(() -> service.topStreakers(null, 5))
                .isInstanceOf(BizException.class)
                .hasMessage("User id is required");
        verify(userStreakMapper, never()).selectTopStreakers(any(), any(Integer.class));
    }

    @Test
    void listsPowerUpsWithDerivedLockedUnlockedAndActivatedStatus() {
        StreakPowerUp royalty = powerUp(1L, "royalty_boost", "Royalty Boost +5% this week", 7,
                "streak_royalty", "/team/unilevel/how-it-works");
        StreakPowerUp premium = powerUp(2L, "premium_trial", "Premium 7-day free trial", 14,
                "streak_premium", "/me/wallet/premium");
        StreakPowerUp staking = powerUp(3L, "staking_boost", "+2% APY on next stake", 30,
                "streak_staker", "/staking");
        UserStreakPowerUp activated = userPowerUp(21L, 10001L, "royalty_boost", "ACTIVATED");
        when(streakPowerUpMapper.selectList(any())).thenReturn(List.of(royalty, premium, staking));
        when(userStreakPowerUpMapper.selectList(any())).thenReturn(List.of(activated));
        when(userStreakMapper.selectOne(any())).thenReturn(streak(9L, 10001L, 14, 20, LocalDate.now()));

        List<StreakPowerUpItemResponse> response = service.listPowerUps(10001L);

        assertThat(response).hasSize(3);
        assertThat(response).extracting(StreakPowerUpItemResponse::getPowerUpCode)
                .containsExactly("royalty_boost", "premium_trial", "staking_boost");
        assertThat(response).extracting(StreakPowerUpItemResponse::getStatus)
                .containsExactly("ACTIVATED", "UNLOCKED", "LOCKED");
        assertThat(response).extracting(StreakPowerUpItemResponse::getDaysRemaining)
                .containsExactly(0, 0, 16);
    }

    @Test
    void opsCreatesPowerUpWithNormalizedCodeAndDefaults() {
        StreakPowerUpRequest request = new StreakPowerUpRequest();
        request.setPowerUpCode("ROYALTY_BOOST");
        request.setPowerUpName("Royalty Boost");
        request.setUnlockStreakDays(7);
        request.setEffectType("CONVERSION");
        request.setEffectValue("royalty");

        StreakPowerUp response = service.createPowerUp(request);

        assertThat(response.getPowerUpCode()).isEqualTo("royalty_boost");
        assertThat(response.getSortOrder()).isEqualTo(100);
        assertThat(response.getStatus()).isEqualTo(1);
        ArgumentCaptor<StreakPowerUp> powerUpCaptor = ArgumentCaptor.forClass(StreakPowerUp.class);
        verify(streakPowerUpMapper).insert(powerUpCaptor.capture());
        assertThat(powerUpCaptor.getValue().getPowerUpName()).isEqualTo("Royalty Boost");
        assertThat(powerUpCaptor.getValue().getUnlockStreakDays()).isEqualTo(7);
    }

    @Test
    void opsCreatePowerUpRejectsDuplicateCode() {
        StreakPowerUpRequest request = new StreakPowerUpRequest();
        request.setPowerUpCode("royalty_boost");
        request.setPowerUpName("Royalty Boost");
        request.setUnlockStreakDays(7);
        request.setEffectType("CONVERSION");
        when(streakPowerUpMapper.selectOne(any())).thenReturn(powerUp(1L, "royalty_boost", "Existing", 7, "streak_royalty", "/team"));

        assertThatThrownBy(() -> service.createPowerUp(request))
                .isInstanceOf(BizException.class)
                .hasMessage("Power-up code already exists");
        verify(streakPowerUpMapper, never()).insert(any(StreakPowerUp.class));
    }

    @Test
    void opsUpdatesPowerUpFields() {
        StreakPowerUp existing = powerUp(1L, "royalty_boost", "Royalty Boost", 7, "streak_royalty", "/team");
        when(streakPowerUpMapper.selectOne(any())).thenReturn(existing);
        StreakPowerUpUpdateRequest request = new StreakPowerUpUpdateRequest();
        request.setPowerUpName("Royalty Boost v2");
        request.setUnlockStreakDays(14);
        request.setStatus(0);

        StreakPowerUp response = service.updatePowerUp(1L, request);

        assertThat(response.getPowerUpCode()).isEqualTo("royalty_boost");
        ArgumentCaptor<StreakPowerUp> patchCaptor = ArgumentCaptor.forClass(StreakPowerUp.class);
        verify(streakPowerUpMapper).updateById(patchCaptor.capture());
        assertThat(patchCaptor.getValue().getId()).isEqualTo(1L);
        assertThat(patchCaptor.getValue().getPowerUpName()).isEqualTo("Royalty Boost v2");
        assertThat(patchCaptor.getValue().getUnlockStreakDays()).isEqualTo(14);
        assertThat(patchCaptor.getValue().getStatus()).isZero();
    }

    @Test
    void activatesUnlockedPowerUpOnceAndUnlocksBadge() {
        StreakPowerUp premium = powerUp(2L, "premium_trial", "Premium 7-day free trial", 14,
                "streak_premium", "/me/wallet/premium");
        when(streakPowerUpMapper.selectOne(any())).thenReturn(premium);
        when(userStreakMapper.selectOne(any())).thenReturn(streak(9L, 10001L, 14, 14, LocalDate.now()));
        when(achievementMapper.selectOne(any())).thenReturn(achievement(31L, "STREAK_PREMIUM", "Streak Premium", 0, 0));

        StreakPowerUpActivationResponse response = service.activatePowerUp(10001L, "premium_trial");

        assertThat(response.isActivated()).isTrue();
        assertThat(response.getStatus()).isEqualTo("ACTIVATED");
        assertThat(response.getPowerUpCode()).isEqualTo("premium_trial");
        assertThat(response.getCurrentStreak()).isEqualTo(14);
        assertThat(response.getUnlockStreakDays()).isEqualTo(14);
        assertThat(response.getTargetPath()).isEqualTo("/me/wallet/premium");
        assertThat(response.getBadgeAchievementCode()).isEqualTo("STREAK_PREMIUM");

        ArgumentCaptor<UserStreakPowerUp> powerUpCaptor = ArgumentCaptor.forClass(UserStreakPowerUp.class);
        verify(userStreakPowerUpMapper).insert(powerUpCaptor.capture());
        assertThat(powerUpCaptor.getValue().getUserId()).isEqualTo(10001L);
        assertThat(powerUpCaptor.getValue().getPowerUpId()).isEqualTo(2L);
        assertThat(powerUpCaptor.getValue().getPowerUpCode()).isEqualTo("premium_trial");
        assertThat(powerUpCaptor.getValue().getPowerUpStatus()).isEqualTo("ACTIVATED");
        assertThat(powerUpCaptor.getValue().getActivatedAt()).isNotNull();

        ArgumentCaptor<UserAchievement> badgeCaptor = ArgumentCaptor.forClass(UserAchievement.class);
        verify(userAchievementMapper).insert(badgeCaptor.capture());
        assertThat(badgeCaptor.getValue().getUserId()).isEqualTo(10001L);
        assertThat(badgeCaptor.getValue().getAchievementCode()).isEqualTo("STREAK_PREMIUM");
        assertThat(badgeCaptor.getValue().getAchievementStatus()).isEqualTo("UNLOCKED");
    }

    @Test
    void activatePowerUpIsIdempotentWhenAlreadyActivated() {
        StreakPowerUp royalty = powerUp(1L, "royalty_boost", "Royalty Boost +5% this week", 7,
                "streak_royalty", "/team/unilevel/how-it-works");
        UserStreakPowerUp activated = userPowerUp(21L, 10001L, "royalty_boost", "ACTIVATED");
        when(streakPowerUpMapper.selectOne(any())).thenReturn(royalty);
        when(userStreakPowerUpMapper.selectOne(any())).thenReturn(activated);
        when(userStreakMapper.selectOne(any())).thenReturn(streak(9L, 10001L, 9, 9, LocalDate.now()));

        StreakPowerUpActivationResponse response = service.activatePowerUp(10001L, "royalty_boost");

        assertThat(response.isActivated()).isFalse();
        assertThat(response.getStatus()).isEqualTo("ALREADY_ACTIVATED");
        verify(userStreakPowerUpMapper, never()).insert(any(UserStreakPowerUp.class));
        verify(userStreakPowerUpMapper, never()).updateById(any(UserStreakPowerUp.class));
        verify(userAchievementMapper, never()).insert(any(UserAchievement.class));
    }

    @Test
    void doesNotActivateLockedPowerUp() {
        StreakPowerUp royalty = powerUp(1L, "royalty_boost", "Royalty Boost +5% this week", 7,
                "streak_royalty", "/team/unilevel/how-it-works");
        when(streakPowerUpMapper.selectOne(any())).thenReturn(royalty);
        when(userStreakMapper.selectOne(any())).thenReturn(streak(9L, 10001L, 6, 6, LocalDate.now()));

        StreakPowerUpActivationResponse response = service.activatePowerUp(10001L, "royalty_boost");

        assertThat(response.isActivated()).isFalse();
        assertThat(response.getStatus()).isEqualTo("LOCKED");
        assertThat(response.getCurrentStreak()).isEqualTo(6);
        assertThat(response.getUnlockStreakDays()).isEqualTo(7);
        verify(userStreakPowerUpMapper, never()).insert(any(UserStreakPowerUp.class));
        verify(userAchievementMapper, never()).insert(any(UserAchievement.class));
    }

    @Test
    void listsStreakMilestonesWithDerivedStatuses() {
        StreakMilestone day3 = milestone(1L, 3, "POINTS", "5.000000", null);
        StreakMilestone day7 = milestone(2L, 7, "POINTS", "15.000000", null);
        StreakMilestone day14 = milestone(3L, 14, "USDT", "1.000000", null);
        UserStreakMilestone claimed = userMilestone(11L, 10001L, 3, "POINTS", "5.000000");
        when(streakMilestoneMapper.selectList(any())).thenReturn(List.of(day3, day7, day14));
        when(userStreakMilestoneMapper.selectList(any())).thenReturn(List.of(claimed));
        when(userStreakMapper.selectOne(any())).thenReturn(streak(9L, 10001L, 7, 12, LocalDate.now()));

        List<StreakMilestoneItemResponse> response = service.listMilestones(10001L);

        assertThat(response).hasSize(3);
        assertThat(response).extracting(StreakMilestoneItemResponse::getMilestoneDay)
                .containsExactly(3, 7, 14);
        assertThat(response).extracting(StreakMilestoneItemResponse::getStatus)
                .containsExactly("CLAIMED", "UNLOCKED", "LOCKED");
        assertThat(response).extracting(StreakMilestoneItemResponse::getDaysRemaining)
                .containsExactly(0, 0, 7);
    }

    @Test
    void opsCreatesStreakMilestoneWithDefaults() {
        StreakMilestoneRequest request = new StreakMilestoneRequest();
        request.setMilestoneDay(30);
        request.setMilestoneName("Day 30");
        request.setRewardType("POINTS");
        request.setRewardAmount(new BigDecimal("50.000000"));
        request.setRewardName("+50 Points");

        StreakMilestone response = service.createMilestone(request);

        assertThat(response.getMilestoneDay()).isEqualTo(30);
        assertThat(response.getSortOrder()).isEqualTo(30);
        assertThat(response.getStatus()).isEqualTo(1);
        ArgumentCaptor<StreakMilestone> milestoneCaptor = ArgumentCaptor.forClass(StreakMilestone.class);
        verify(streakMilestoneMapper).insert(milestoneCaptor.capture());
        assertThat(milestoneCaptor.getValue().getRewardAmount()).isEqualByComparingTo("50.000000");
    }

    @Test
    void opsCreateStreakMilestoneRejectsDuplicateDay() {
        StreakMilestoneRequest request = new StreakMilestoneRequest();
        request.setMilestoneDay(7);
        request.setMilestoneName("Day 7");
        request.setRewardType("POINTS");
        request.setRewardAmount(new BigDecimal("15.000000"));
        request.setRewardName("+15 Points");
        when(streakMilestoneMapper.selectOne(any())).thenReturn(milestone(2L, 7, "POINTS", "15.000000", null));

        assertThatThrownBy(() -> service.createMilestone(request))
                .isInstanceOf(BizException.class)
                .hasMessage("Streak milestone day already exists");
        verify(streakMilestoneMapper, never()).insert(any(StreakMilestone.class));
    }

    @Test
    void opsUpdatesStreakMilestoneAndRejectsDuplicateDay() {
        StreakMilestone existing = milestone(1L, 7, "POINTS", "15.000000", null);
        StreakMilestone duplicate = milestone(2L, 14, "POINTS", "25.000000", null);
        when(streakMilestoneMapper.selectOne(any())).thenReturn(existing, duplicate);
        StreakMilestoneUpdateRequest request = new StreakMilestoneUpdateRequest();
        request.setMilestoneDay(14);

        assertThatThrownBy(() -> service.updateMilestone(1L, request))
                .isInstanceOf(BizException.class)
                .hasMessage("Streak milestone day already exists");
        verify(streakMilestoneMapper, never()).updateById(any(StreakMilestone.class));
    }

    @Test
    void opsUpdatesStreakMilestoneFields() {
        StreakMilestone existing = milestone(1L, 7, "POINTS", "15.000000", null);
        when(streakMilestoneMapper.selectOne(any())).thenReturn(existing);
        StreakMilestoneUpdateRequest request = new StreakMilestoneUpdateRequest();
        request.setMilestoneName("Day 7 Pro");
        request.setRewardAmount(new BigDecimal("25.000000"));
        request.setStatus(0);

        StreakMilestone response = service.updateMilestone(1L, request);

        assertThat(response.getMilestoneDay()).isEqualTo(7);
        ArgumentCaptor<StreakMilestone> patchCaptor = ArgumentCaptor.forClass(StreakMilestone.class);
        verify(streakMilestoneMapper).updateById(patchCaptor.capture());
        assertThat(patchCaptor.getValue().getId()).isEqualTo(1L);
        assertThat(patchCaptor.getValue().getMilestoneName()).isEqualTo("Day 7 Pro");
        assertThat(patchCaptor.getValue().getRewardAmount()).isEqualByComparingTo("25.000000");
        assertThat(patchCaptor.getValue().getStatus()).isZero();
    }

    @Test
    void claimsUnlockedPointsMilestoneOnceAndWritesLedger() {
        StreakMilestone day7 = milestone(2L, 7, "POINTS", "15.000000", null);
        when(streakMilestoneMapper.selectOne(any())).thenReturn(day7);
        when(userStreakMapper.selectOne(any())).thenReturn(streak(9L, 10001L, 7, 12, LocalDate.now()));
        when(pointsLedgerMapper.sumPointsByUser(10001L)).thenReturn(150);

        StreakMilestoneClaimResponse response = service.claimMilestone(10001L, 7);

        assertThat(response.isClaimed()).isTrue();
        assertThat(response.getStatus()).isEqualTo("CLAIMED");
        assertThat(response.getMilestoneDay()).isEqualTo(7);
        assertThat(response.getRewardType()).isEqualTo("POINTS");
        assertThat(response.getAwardedPoints()).isEqualTo(15);
        assertThat(response.getTotalPoints()).isEqualTo(165);

        ArgumentCaptor<UserStreakMilestone> claimCaptor = ArgumentCaptor.forClass(UserStreakMilestone.class);
        verify(userStreakMilestoneMapper).insert(claimCaptor.capture());
        assertThat(claimCaptor.getValue().getUserId()).isEqualTo(10001L);
        assertThat(claimCaptor.getValue().getMilestoneDay()).isEqualTo(7);
        assertThat(claimCaptor.getValue().getClaimStatus()).isEqualTo("CLAIMED");
        assertThat(claimCaptor.getValue().getClaimedAt()).isNotNull();

        ArgumentCaptor<PointsLedger> ledgerCaptor = ArgumentCaptor.forClass(PointsLedger.class);
        verify(pointsLedgerMapper).insert(ledgerCaptor.capture());
        assertThat(ledgerCaptor.getValue().getBizNo()).isEqualTo("STREAK-MILESTONE-7-10001");
        assertThat(ledgerCaptor.getValue().getBizType()).isEqualTo("STREAK_MILESTONE");
        assertThat(ledgerCaptor.getValue().getPoints()).isEqualTo(15);
        assertThat(ledgerCaptor.getValue().getBalanceAfter()).isEqualTo(165);
    }

    @Test
    void claimMilestoneIsIdempotentWhenAlreadyClaimed() {
        StreakMilestone day3 = milestone(1L, 3, "POINTS", "5.000000", null);
        UserStreakMilestone claimed = userMilestone(11L, 10001L, 3, "POINTS", "5.000000");
        when(streakMilestoneMapper.selectOne(any())).thenReturn(day3);
        when(userStreakMilestoneMapper.selectOne(any())).thenReturn(claimed);
        when(userStreakMapper.selectOne(any())).thenReturn(streak(9L, 10001L, 7, 12, LocalDate.now()));
        when(pointsLedgerMapper.sumPointsByUser(10001L)).thenReturn(155);

        StreakMilestoneClaimResponse response = service.claimMilestone(10001L, 3);

        assertThat(response.isClaimed()).isFalse();
        assertThat(response.getStatus()).isEqualTo("ALREADY_CLAIMED");
        assertThat(response.getAwardedPoints()).isZero();
        assertThat(response.getTotalPoints()).isEqualTo(155);
        verify(userStreakMilestoneMapper, never()).insert(any(UserStreakMilestone.class));
        verify(pointsLedgerMapper, never()).insert(any(PointsLedger.class));
    }

    @Test
    void doesNotClaimLockedMilestone() {
        StreakMilestone day14 = milestone(3L, 14, "USDT", "1.000000", null);
        when(streakMilestoneMapper.selectOne(any())).thenReturn(day14);
        when(userStreakMapper.selectOne(any())).thenReturn(streak(9L, 10001L, 7, 12, LocalDate.now()));

        StreakMilestoneClaimResponse response = service.claimMilestone(10001L, 14);

        assertThat(response.isClaimed()).isFalse();
        assertThat(response.getStatus()).isEqualTo("LOCKED");
        assertThat(response.getDaysRemaining()).isEqualTo(7);
        verify(userStreakMilestoneMapper, never()).insert(any(UserStreakMilestone.class));
        verify(pointsLedgerMapper, never()).insert(any(PointsLedger.class));
    }

    @Test
    void claimsUnlockedAssetMilestoneWithoutWritingPointsLedger() {
        StreakMilestone day21 = milestone(4L, 21, "NEX", "100.000000", null);
        when(streakMilestoneMapper.selectOne(any())).thenReturn(day21);
        when(userStreakMapper.selectOne(any())).thenReturn(streak(9L, 10001L, 21, 21, LocalDate.now()));
        when(pointsLedgerMapper.sumPointsByUser(10001L)).thenReturn(155);

        StreakMilestoneClaimResponse response = service.claimMilestone(10001L, 21);

        assertThat(response.isClaimed()).isTrue();
        assertThat(response.getStatus()).isEqualTo("CLAIMED");
        assertThat(response.getRewardType()).isEqualTo("NEX");
        assertThat(response.getAwardedPoints()).isZero();
        assertThat(response.getTotalPoints()).isEqualTo(155);
        verify(userStreakMilestoneMapper).insert(any(UserStreakMilestone.class));
        verify(pointsLedgerMapper, never()).insert(any(PointsLedger.class));
        verify(userAchievementMapper, never()).insert(any(UserAchievement.class));
    }

    @Test
    void claimBadgeMilestoneUnlocksConfiguredAchievement() {
        StreakMilestone day100 = milestone(7L, 100, "BADGE", "1.000000", "STREAK_MASTER");
        when(streakMilestoneMapper.selectOne(any())).thenReturn(day100);
        when(userStreakMapper.selectOne(any())).thenReturn(streak(9L, 10001L, 100, 100, LocalDate.now()));
        when(achievementMapper.selectOne(any())).thenReturn(achievement(41L, "STREAK_MASTER", "Streak Master", 0, 0));

        StreakMilestoneClaimResponse response = service.claimMilestone(10001L, 100);

        assertThat(response.isClaimed()).isTrue();
        assertThat(response.getStatus()).isEqualTo("CLAIMED");
        assertThat(response.getRewardType()).isEqualTo("BADGE");
        assertThat(response.getBadgeAchievementCode()).isEqualTo("STREAK_MASTER");

        ArgumentCaptor<UserAchievement> badgeCaptor = ArgumentCaptor.forClass(UserAchievement.class);
        verify(userAchievementMapper).insert(badgeCaptor.capture());
        assertThat(badgeCaptor.getValue().getUserId()).isEqualTo(10001L);
        assertThat(badgeCaptor.getValue().getAchievementCode()).isEqualTo("STREAK_MASTER");
        assertThat(badgeCaptor.getValue().getAchievementStatus()).isEqualTo("UNLOCKED");
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

    private StreakLeaderboardEntryResponse streakLeaderboardEntry(
            int rank,
            Long userId,
            String displayName,
            String avatarUrl,
            String countryCode,
            int currentStreak,
            int longestStreak,
            LocalDate lastCheckInDate,
            boolean checkedInToday) {
        return new StreakLeaderboardEntryResponse(
                rank,
                userId,
                displayName,
                avatarUrl,
                countryCode,
                currentStreak,
                longestStreak,
                lastCheckInDate,
                checkedInToday);
    }

    private Achievement achievement(Long id, String code, String name, int triggerValue, int rewardPoints) {
        Achievement achievement = new Achievement();
        achievement.setId(id);
        achievement.setAchievementCode(code);
        achievement.setAchievementName(name);
        achievement.setDescription(name + " description");
        achievement.setCategory("LOYALTY");
        achievement.setIconKey("award");
        achievement.setAccentColor("#9EDC1D");
        achievement.setTriggerType("STREAK_DAYS");
        achievement.setTriggerValue(triggerValue);
        achievement.setRewardPoints(rewardPoints);
        achievement.setSortOrder(10);
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

    private StreakPowerUp powerUp(
            Long id,
            String code,
            String name,
            int unlockStreakDays,
            String badgeId,
            String targetPath) {
        StreakPowerUp powerUp = new StreakPowerUp();
        powerUp.setId(id);
        powerUp.setPowerUpCode(code);
        powerUp.setPowerUpName(name);
        powerUp.setI18nKey(code);
        powerUp.setTargetPath(targetPath);
        powerUp.setBadgeAchievementCode(badgeId.toUpperCase());
        powerUp.setUnlockStreakDays(unlockStreakDays);
        powerUp.setEffectType("CONVERSION");
        powerUp.setEffectValue(code);
        powerUp.setDurationDays(7);
        powerUp.setStatus(1);
        powerUp.setIsDeleted(0);
        return powerUp;
    }

    private UserStreakPowerUp userPowerUp(Long id, Long userId, String powerUpCode, String status) {
        UserStreakPowerUp userPowerUp = new UserStreakPowerUp();
        userPowerUp.setId(id);
        userPowerUp.setUserId(userId);
        userPowerUp.setPowerUpCode(powerUpCode);
        userPowerUp.setPowerUpStatus(status);
        userPowerUp.setUnlockedAt(LocalDateTime.now().minusDays(1));
        userPowerUp.setActivatedAt(LocalDateTime.now());
        userPowerUp.setIsDeleted(0);
        return userPowerUp;
    }

    private StreakMilestone milestone(
            Long id,
            int day,
            String rewardType,
            String rewardAmount,
            String badgeAchievementCode) {
        StreakMilestone milestone = new StreakMilestone();
        milestone.setId(id);
        milestone.setMilestoneDay(day);
        milestone.setMilestoneName("Day " + day);
        milestone.setRewardType(rewardType);
        milestone.setRewardAmount(new BigDecimal(rewardAmount));
        milestone.setRewardName(rewardType + " reward");
        milestone.setBadgeAchievementCode(badgeAchievementCode);
        milestone.setStatus(1);
        milestone.setIsDeleted(0);
        return milestone;
    }

    private UserStreakMilestone userMilestone(
            Long id,
            Long userId,
            int day,
            String rewardType,
            String rewardAmount) {
        UserStreakMilestone milestone = new UserStreakMilestone();
        milestone.setId(id);
        milestone.setUserId(userId);
        milestone.setMilestoneDay(day);
        milestone.setRewardType(rewardType);
        milestone.setRewardAmount(new BigDecimal(rewardAmount));
        milestone.setClaimStatus("CLAIMED");
        milestone.setClaimedAt(LocalDateTime.now());
        milestone.setIsDeleted(0);
        return milestone;
    }

    private DailyCheckInReward reward(int basePoints, String multiplier, int awardedPoints) {
        return new DailyCheckInReward(basePoints, new BigDecimal(multiplier), awardedPoints);
    }
}
