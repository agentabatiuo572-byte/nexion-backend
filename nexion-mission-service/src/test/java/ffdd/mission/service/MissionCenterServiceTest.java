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
import ffdd.mission.domain.DailyCheckIn;
import ffdd.mission.domain.Mission;
import ffdd.mission.domain.PointsLedger;
import ffdd.mission.domain.UserMission;
import ffdd.mission.dto.DailyCheckInResponse;
import ffdd.mission.dto.MissionItemResponse;
import ffdd.mission.dto.MissionListResponse;
import ffdd.mission.dto.PointsSummaryResponse;
import ffdd.mission.mapper.DailyCheckInMapper;
import ffdd.mission.mapper.MissionMapper;
import ffdd.mission.mapper.PointsLedgerMapper;
import ffdd.mission.mapper.UserMissionMapper;
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
    private final MissionCenterService service =
            new MissionCenterService(missionMapper, userMissionMapper, pointsLedgerMapper, dailyCheckInMapper);

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

        ArgumentCaptor<DailyCheckIn> checkInCaptor = ArgumentCaptor.forClass(DailyCheckIn.class);
        verify(dailyCheckInMapper).insert(checkInCaptor.capture());
        assertThat(checkInCaptor.getValue().getUserId()).isEqualTo(10001L);
        assertThat(checkInCaptor.getValue().getMissionId()).isEqualTo(3L);
        assertThat(checkInCaptor.getValue().getRewardPoints()).isEqualTo(30);

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
}
