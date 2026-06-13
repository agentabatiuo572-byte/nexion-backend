package ffdd.mission.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.common.exception.BizException;
import ffdd.mission.domain.EventQuest;
import ffdd.mission.domain.MonthlyChallenge;
import ffdd.mission.domain.PointsLedger;
import ffdd.mission.domain.UserEventQuest;
import ffdd.mission.domain.UserMonthlyChallenge;
import ffdd.mission.dto.CampaignClaimResponse;
import ffdd.mission.dto.EventQuestItemResponse;
import ffdd.mission.dto.EventQuestRequest;
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
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class MissionCampaignServiceTest {
    private final MonthlyChallengeMapper monthlyChallengeMapper = mock(MonthlyChallengeMapper.class);
    private final UserMonthlyChallengeMapper userMonthlyChallengeMapper = mock(UserMonthlyChallengeMapper.class);
    private final EventQuestMapper eventQuestMapper = mock(EventQuestMapper.class);
    private final UserEventQuestMapper userEventQuestMapper = mock(UserEventQuestMapper.class);
    private final PointsLedgerMapper pointsLedgerMapper = mock(PointsLedgerMapper.class);
    private final MissionCampaignService service = new MissionCampaignService(
            monthlyChallengeMapper,
            userMonthlyChallengeMapper,
            eventQuestMapper,
            userEventQuestMapper,
            pointsLedgerMapper);

    @Test
    void listsMonthlyChallengesWithDerivedProgressStatus() {
        MonthlyChallenge starter = monthly(1L, "MONTHLY_STARTER", 5, "1000.000000");
        MonthlyChallenge growth = monthly(2L, "MONTHLY_GROWTH", 7, "2000.000000");
        UserMonthlyChallenge progress = userMonthly(10001L, "MONTHLY_STARTER", 3, "LOCKED");
        UserMonthlyChallenge claimed = userMonthly(10001L, "MONTHLY_GROWTH", 7, "CLAIMED");
        when(monthlyChallengeMapper.selectList(any())).thenReturn(List.of(starter, growth));
        when(userMonthlyChallengeMapper.selectList(any())).thenReturn(List.of(progress, claimed));

        List<MonthlyChallengeItemResponse> response = service.listMonthly(10001L);

        assertThat(response).hasSize(2);
        assertThat(response).extracting(MonthlyChallengeItemResponse::getChallengeCode)
                .containsExactly("MONTHLY_STARTER", "MONTHLY_GROWTH");
        assertThat(response).extracting(MonthlyChallengeItemResponse::getStatus)
                .containsExactly("LOCKED", "CLAIMED");
        assertThat(response.get(0).getProgressPercent()).isEqualTo(60);
    }

    @Test
    void claimsUnlockedMonthlyPointsRewardOnceAndWritesLedger() {
        MonthlyChallenge challenge = monthly(1L, "MONTHLY_STARTER", 5, "1000.000000");
        UserMonthlyChallenge progress = userMonthly(10001L, "MONTHLY_STARTER", 5, "UNLOCKED");
        when(monthlyChallengeMapper.selectOne(any())).thenReturn(challenge);
        when(userMonthlyChallengeMapper.selectOne(any())).thenReturn(progress);
        when(pointsLedgerMapper.sumPointsByUser(10001L)).thenReturn(120);

        CampaignClaimResponse response = service.claimMonthly(10001L, "monthly_starter");

        assertThat(response.isClaimed()).isTrue();
        assertThat(response.getStatus()).isEqualTo("CLAIMED");
        assertThat(response.getAwardedPoints()).isEqualTo(1000);
        assertThat(response.getTotalPoints()).isEqualTo(1120);

        ArgumentCaptor<UserMonthlyChallenge> claimCaptor = ArgumentCaptor.forClass(UserMonthlyChallenge.class);
        verify(userMonthlyChallengeMapper).updateById(claimCaptor.capture());
        assertThat(claimCaptor.getValue().getClaimStatus()).isEqualTo("CLAIMED");
        assertThat(claimCaptor.getValue().getClaimedAt()).isNotNull();

        ArgumentCaptor<PointsLedger> ledgerCaptor = ArgumentCaptor.forClass(PointsLedger.class);
        verify(pointsLedgerMapper).insert(ledgerCaptor.capture());
        assertThat(ledgerCaptor.getValue().getBizNo()).isEqualTo("MONTHLY-CHALLENGE-MONTHLY_STARTER-10001");
        assertThat(ledgerCaptor.getValue().getBizType()).isEqualTo("MONTHLY_CHALLENGE");
        assertThat(ledgerCaptor.getValue().getPoints()).isEqualTo(1000);
        assertThat(ledgerCaptor.getValue().getBalanceAfter()).isEqualTo(1120);
    }

    @Test
    void monthlyClaimIsIdempotentWhenAlreadyClaimed() {
        MonthlyChallenge challenge = monthly(1L, "MONTHLY_STARTER", 5, "1000.000000");
        UserMonthlyChallenge claimed = userMonthly(10001L, "MONTHLY_STARTER", 5, "CLAIMED");
        when(monthlyChallengeMapper.selectOne(any())).thenReturn(challenge);
        when(userMonthlyChallengeMapper.selectOne(any())).thenReturn(claimed);
        when(pointsLedgerMapper.sumPointsByUser(10001L)).thenReturn(1120);

        CampaignClaimResponse response = service.claimMonthly(10001L, "MONTHLY_STARTER");

        assertThat(response.isClaimed()).isFalse();
        assertThat(response.getStatus()).isEqualTo("ALREADY_CLAIMED");
        assertThat(response.getAwardedPoints()).isZero();
        verify(userMonthlyChallengeMapper, never()).insert(any(UserMonthlyChallenge.class));
        verify(userMonthlyChallengeMapper, never()).updateById(any(UserMonthlyChallenge.class));
        verify(pointsLedgerMapper, never()).insert(any(PointsLedger.class));
    }

    @Test
    void monthlyClaimRemainsLockedWhenProgressIsShort() {
        MonthlyChallenge challenge = monthly(1L, "MONTHLY_STARTER", 5, "1000.000000");
        UserMonthlyChallenge progress = userMonthly(10001L, "MONTHLY_STARTER", 4, "LOCKED");
        when(monthlyChallengeMapper.selectOne(any())).thenReturn(challenge);
        when(userMonthlyChallengeMapper.selectOne(any())).thenReturn(progress);

        CampaignClaimResponse response = service.claimMonthly(10001L, "MONTHLY_STARTER");

        assertThat(response.isClaimed()).isFalse();
        assertThat(response.getStatus()).isEqualTo("LOCKED");
        verify(pointsLedgerMapper, never()).insert(any(PointsLedger.class));
    }

    @Test
    void listsActiveEventQuestsAndFiltersExpiredInQuery() {
        EventQuest quest = event(1L, "EVENT_GENESIS_WEEK", 3, "1500.000000");
        UserEventQuest progress = userEvent(10001L, "EVENT_GENESIS_WEEK", 3, "UNLOCKED");
        when(eventQuestMapper.selectList(any())).thenReturn(List.of(quest));
        when(userEventQuestMapper.selectList(any())).thenReturn(List.of(progress));

        List<EventQuestItemResponse> response = service.listEvents(10001L);

        assertThat(response).hasSize(1);
        assertThat(response.get(0).getQuestCode()).isEqualTo("EVENT_GENESIS_WEEK");
        assertThat(response.get(0).getStatus()).isEqualTo("UNLOCKED");
        assertThat(response.get(0).getProgressPercent()).isEqualTo(100);
    }

    @Test
    void eventClaimRejectsExpiredQuestWithoutLedger() {
        EventQuest quest = event(1L, "EVENT_GENESIS_WEEK", 3, "1500.000000");
        quest.setEndsAt(LocalDateTime.now().minusDays(1));
        UserEventQuest progress = userEvent(10001L, "EVENT_GENESIS_WEEK", 3, "UNLOCKED");
        when(eventQuestMapper.selectOne(any())).thenReturn(quest);
        when(userEventQuestMapper.selectOne(any())).thenReturn(progress);

        CampaignClaimResponse response = service.claimEvent(10001L, "EVENT_GENESIS_WEEK");

        assertThat(response.isClaimed()).isFalse();
        assertThat(response.getStatus()).isEqualTo("EXPIRED");
        verify(pointsLedgerMapper, never()).insert(any(PointsLedger.class));
    }

    @Test
    void opsCreateMonthlyValidatesDuplicateCode() {
        MonthlyChallengeRequest request = new MonthlyChallengeRequest();
        request.setChallengeCode("MONTHLY_STARTER");
        request.setChallengeName("Starter");
        request.setTargetType("MISSION_ACTIONS");
        request.setTargetValue(5);
        request.setRewardType("POINTS");
        request.setRewardAmount(new BigDecimal("1000.000000"));
        request.setRewardName("+1,000 Points");
        when(monthlyChallengeMapper.selectOne(any())).thenReturn(monthly(1L, "MONTHLY_STARTER", 5, "1000.000000"));

        assertThatThrownBy(() -> service.createMonthly(request))
                .isInstanceOf(BizException.class)
                .hasMessage("Monthly challenge code already exists");
        verify(monthlyChallengeMapper, never()).insert(any(MonthlyChallenge.class));
    }

    @Test
    void opsUpdateMonthlyRejectsInvalidMonthWindow() {
        MonthlyChallenge existing = monthly(1L, "MONTHLY_STARTER", 5, "1000.000000");
        existing.setMonthsFrom(0);
        existing.setMonthsTo(3);
        when(monthlyChallengeMapper.selectOne(any())).thenReturn(existing);
        MonthlyChallengeUpdateRequest request = new MonthlyChallengeUpdateRequest();
        request.setMonthsFrom(5);

        assertThatThrownBy(() -> service.updateMonthly(1L, request))
                .isInstanceOf(BizException.class)
                .hasMessage("Monthly challenge month window is invalid");
        verify(monthlyChallengeMapper, never()).updateById(any(MonthlyChallenge.class));
    }

    @Test
    void opsSoftDeletesMonthlyChallengeConfigOnly() {
        when(monthlyChallengeMapper.selectOne(any())).thenReturn(monthly(1L, "MONTHLY_STARTER", 5, "1000.000000"));

        service.deleteMonthly(1L);

        ArgumentCaptor<MonthlyChallenge> patchCaptor = ArgumentCaptor.forClass(MonthlyChallenge.class);
        verify(monthlyChallengeMapper).updateById(patchCaptor.capture());
        assertThat(patchCaptor.getValue().getId()).isEqualTo(1L);
        assertThat(patchCaptor.getValue().getStatus()).isZero();
        assertThat(patchCaptor.getValue().getIsDeleted()).isEqualTo(1);
        verify(userMonthlyChallengeMapper, never()).updateById(any(UserMonthlyChallenge.class));
        verify(pointsLedgerMapper, never()).insert(any(PointsLedger.class));
    }

    @Test
    void opsUpdatesMonthlyProgressAndDerivesUnlockedStatus() {
        MonthlyChallenge challenge = monthly(1L, "MONTHLY_STARTER", 5, "1000.000000");
        when(monthlyChallengeMapper.selectOne(any())).thenReturn(challenge);
        MissionProgressUpdateRequest request = new MissionProgressUpdateRequest();
        request.setProgressValue(5);

        MonthlyChallengeItemResponse response = service.updateMonthlyProgress(10001L, "MONTHLY_STARTER", request);

        assertThat(response.getStatus()).isEqualTo("UNLOCKED");
        ArgumentCaptor<UserMonthlyChallenge> recordCaptor = ArgumentCaptor.forClass(UserMonthlyChallenge.class);
        verify(userMonthlyChallengeMapper).insert(recordCaptor.capture());
        assertThat(recordCaptor.getValue().getProgressValue()).isEqualTo(5);
    }

    @Test
    void opsCreateEventRejectsInvalidWindow() {
        EventQuestRequest request = new EventQuestRequest();
        request.setQuestCode("EVENT_BAD_WINDOW");
        request.setQuestName("Bad Window");
        request.setStartsAt(LocalDateTime.now());
        request.setEndsAt(LocalDateTime.now().minusDays(1));
        request.setTargetType("EVENT_ACTIONS");
        request.setTargetValue(3);
        request.setRewardType("POINTS");
        request.setRewardAmount(new BigDecimal("100.000000"));
        request.setRewardName("+100 Points");

        assertThatThrownBy(() -> service.createEvent(request))
                .isInstanceOf(BizException.class)
                .hasMessage("Event quest time window is invalid");
        verify(eventQuestMapper, never()).insert(any(EventQuest.class));
    }

    @Test
    void opsSoftDeletesEventQuestConfigOnly() {
        when(eventQuestMapper.selectOne(any())).thenReturn(event(1L, "EVENT_GENESIS_WEEK", 3, "1500.000000"));

        service.deleteEvent(1L);

        ArgumentCaptor<EventQuest> patchCaptor = ArgumentCaptor.forClass(EventQuest.class);
        verify(eventQuestMapper).updateById(patchCaptor.capture());
        assertThat(patchCaptor.getValue().getId()).isEqualTo(1L);
        assertThat(patchCaptor.getValue().getStatus()).isZero();
        assertThat(patchCaptor.getValue().getIsDeleted()).isEqualTo(1);
        verify(userEventQuestMapper, never()).updateById(any(UserEventQuest.class));
        verify(pointsLedgerMapper, never()).insert(any(PointsLedger.class));
    }

    private MonthlyChallenge monthly(Long id, String code, int targetValue, String rewardAmount) {
        MonthlyChallenge challenge = new MonthlyChallenge();
        challenge.setId(id);
        challenge.setChallengeCode(code);
        challenge.setChallengeName(code);
        challenge.setDescription(code + " description");
        challenge.setTheme("TEST");
        challenge.setMonthsFrom(0);
        challenge.setMonthsTo(999);
        challenge.setTargetType("MISSION_ACTIONS");
        challenge.setTargetValue(targetValue);
        challenge.setRewardType("POINTS");
        challenge.setRewardAmount(new BigDecimal(rewardAmount));
        challenge.setRewardName("Points");
        challenge.setStatus(1);
        challenge.setIsDeleted(0);
        return challenge;
    }

    private UserMonthlyChallenge userMonthly(Long userId, String code, int progressValue, String status) {
        UserMonthlyChallenge record = new UserMonthlyChallenge();
        record.setId(11L);
        record.setUserId(userId);
        record.setChallengeId(1L);
        record.setChallengeCode(code);
        record.setProgressValue(progressValue);
        record.setClaimStatus(status);
        record.setRewardType("POINTS");
        record.setRewardAmount(new BigDecimal("1000.000000"));
        record.setClaimedAt("CLAIMED".equals(status) ? LocalDateTime.now() : null);
        record.setIsDeleted(0);
        return record;
    }

    private EventQuest event(Long id, String code, int targetValue, String rewardAmount) {
        EventQuest quest = new EventQuest();
        quest.setId(id);
        quest.setQuestCode(code);
        quest.setQuestName(code);
        quest.setDescription(code + " description");
        quest.setStartsAt(LocalDateTime.now().minusDays(1));
        quest.setEndsAt(LocalDateTime.now().plusDays(1));
        quest.setTargetType("EVENT_ACTIONS");
        quest.setTargetValue(targetValue);
        quest.setRewardType("POINTS");
        quest.setRewardAmount(new BigDecimal(rewardAmount));
        quest.setRewardName("Points");
        quest.setStatus(1);
        quest.setIsDeleted(0);
        return quest;
    }

    private UserEventQuest userEvent(Long userId, String code, int progressValue, String status) {
        UserEventQuest record = new UserEventQuest();
        record.setId(21L);
        record.setUserId(userId);
        record.setQuestId(1L);
        record.setQuestCode(code);
        record.setProgressValue(progressValue);
        record.setClaimStatus(status);
        record.setRewardType("POINTS");
        record.setRewardAmount(new BigDecimal("1500.000000"));
        record.setClaimedAt("CLAIMED".equals(status) ? LocalDateTime.now() : null);
        record.setIsDeleted(0);
        return record;
    }
}
