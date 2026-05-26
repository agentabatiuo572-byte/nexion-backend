package ffdd.mission.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.mission.domain.Mission;
import ffdd.mission.domain.PointsLedger;
import ffdd.mission.domain.UserMission;
import ffdd.mission.dto.EarningGeneratedPayload;
import ffdd.mission.dto.MissionConsumeResult;
import ffdd.mission.mapper.MissionMapper;
import ffdd.mission.mapper.PointsLedgerMapper;
import ffdd.mission.mapper.UserMissionMapper;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class EarningGeneratedMissionServiceTest {
    private final MissionMapper missionMapper = mock(MissionMapper.class);
    private final UserMissionMapper userMissionMapper = mock(UserMissionMapper.class);
    private final PointsLedgerMapper pointsLedgerMapper = mock(PointsLedgerMapper.class);
    private final EarningGeneratedMissionService service =
            new EarningGeneratedMissionService(missionMapper, userMissionMapper, pointsLedgerMapper);

    @Test
    void completesFirstReceiptMissionAndWritesPointsLedgerOnce() {
        Mission mission = new Mission();
        mission.setId(2L);
        mission.setMissionCode("FIRST_RECEIPT");
        mission.setRewardPoints(200);
        when(missionMapper.selectOne(any())).thenReturn(mission);
        when(pointsLedgerMapper.sumPointsByUser(10001L)).thenReturn(30);

        EarningGeneratedPayload payload = new EarningGeneratedPayload();
        payload.setEventNo("EARN-POC1-USDT");
        payload.setUserId(10001L);
        payload.setReceiptNo("POC-1");
        payload.setAsset("USDT");
        payload.setAmount(new BigDecimal("0.018"));

        MissionConsumeResult result = service.consume(payload);

        assertThat(result.isCompleted()).isTrue();
        assertThat(result.getPoints()).isEqualTo(200);
        ArgumentCaptor<UserMission> missionCaptor = ArgumentCaptor.forClass(UserMission.class);
        verify(userMissionMapper).insert(missionCaptor.capture());
        assertThat(missionCaptor.getValue().getUserId()).isEqualTo(10001L);
        assertThat(missionCaptor.getValue().getMissionId()).isEqualTo(2L);
        assertThat(missionCaptor.getValue().getMissionStatus()).isEqualTo("COMPLETED");

        ArgumentCaptor<PointsLedger> ledgerCaptor = ArgumentCaptor.forClass(PointsLedger.class);
        verify(pointsLedgerMapper).insert(ledgerCaptor.capture());
        assertThat(ledgerCaptor.getValue().getBizNo()).isEqualTo("MISSION-FIRST_RECEIPT-10001");
        assertThat(ledgerCaptor.getValue().getBizType()).isEqualTo("MISSION");
        assertThat(ledgerCaptor.getValue().getPoints()).isEqualTo(200);
        assertThat(ledgerCaptor.getValue().getBalanceAfter()).isEqualTo(230);
    }

    @Test
    void skipsDuplicateFirstReceiptMission() {
        Mission mission = new Mission();
        mission.setId(2L);
        mission.setMissionCode("FIRST_RECEIPT");
        mission.setRewardPoints(200);
        when(missionMapper.selectOne(any())).thenReturn(mission);
        UserMission existing = new UserMission();
        existing.setId(3L);
        when(userMissionMapper.selectOne(any())).thenReturn(existing);

        EarningGeneratedPayload payload = new EarningGeneratedPayload();
        payload.setEventNo("EARN-POC1-NEX");
        payload.setUserId(10001L);

        MissionConsumeResult result = service.consume(payload);

        assertThat(result.isCompleted()).isFalse();
        verify(userMissionMapper, never()).insert(any(UserMission.class));
        verify(pointsLedgerMapper, never()).insert(any(PointsLedger.class));
    }
}
