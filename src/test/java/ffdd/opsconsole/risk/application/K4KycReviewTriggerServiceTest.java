package ffdd.opsconsole.risk.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.risk.domain.KycReviewTicketContext;
import ffdd.opsconsole.risk.domain.RiskOpsRepository;
import ffdd.opsconsole.risk.domain.RiskScoreUserView;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;

class K4KycReviewTriggerServiceTest {
    private final RiskOpsRepository repository = mock(RiskOpsRepository.class);
    private final AuditLogService auditLogService = mock(AuditLogService.class);
    private final K4KycReviewTriggerService service = new K4KycReviewTriggerService(repository, auditLogService);

    @Test
    void scoreBelowCurrentK5ThresholdDoesNotCreateOrMergeATicket() {
        when(repository.kycReviewTriggerScore()).thenReturn(86);

        when(repository.transitionK4KycReviewTriggerState("U1", 85, 86, "fact:U1:9")).thenReturn(false);

        service.triggerIfThresholdReached(user(70), user(85), K4KycReviewTriggerService.SOURCE_FACT_REFRESH,
                "facts refreshed", "system", "fact:U1:9");

        verify(repository, never()).findOpenKycReviewTicketByUserForUpdate(anyString());
        verify(repository, never()).createScoreTriggeredKycReviewTicket(
                anyString(), anyString(), eq(85), eq(86), anyString(), anyString(), anyString());
        verify(auditLogService, never()).recordRequired(org.mockito.ArgumentMatchers.any());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            K4KycReviewTriggerService.SOURCE_SCORE_OVERRIDE,
            K4KycReviewTriggerService.SOURCE_SCORE_RECOMPUTE,
            K4KycReviewTriggerService.SOURCE_BATCH_RECOMPUTE,
            K4KycReviewTriggerService.SOURCE_MODEL_PUBLISH,
            K4KycReviewTriggerService.SOURCE_FACT_REFRESH,
            K4KycReviewTriggerService.SOURCE_REVIEW_THRESHOLD_CHANGE
    })
    void everyK4MutationSourceCreatesOneK5TicketWithExactSource(String source) {
        when(repository.kycReviewTriggerScore()).thenReturn(85);
        when(repository.transitionK4KycReviewTriggerState("U1", 85, 85, "idem-1")).thenReturn(true);
        when(repository.findOpenKycReviewTicketByUserForUpdate("U1")).thenReturn(Optional.empty());

        service.triggerIfThresholdReached(user(84), user(85), source,
                "score reached review line", "superadmin", "idem-1");

        verify(repository).createScoreTriggeredKycReviewTicket(
                anyString(), eq("U1"), eq(85), eq(85), eq(source),
                eq("score reached review line"), eq("superadmin"));
        ArgumentCaptor<AuditLogWriteRequest> audit = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
        verify(auditLogService).recordRequired(audit.capture());
        assertThat(audit.getValue().getAction()).isEqualTo("K5_KYC_REVIEW_TRIGGERED_BY_SCORE");
        assertThat(audit.getValue().getDetail().toString()).contains(source, "idem-1");
    }

    @Test
    void existingOpenTicketIsLockedAndMergedInsteadOfDuplicated() {
        when(repository.kycReviewTriggerScore()).thenReturn(80);
        when(repository.transitionK4KycReviewTriggerState("U1", 92, 80, "batch-1:U1")).thenReturn(true);
        KycReviewTicketContext open = new KycReviewTicketContext(
                "KR-OPEN", "手动触发", "U1", "in-review", "[]", 4L);
        when(repository.findOpenKycReviewTicketByUserForUpdate("U1")).thenReturn(Optional.of(open));
        when(repository.mergeOpenKycReviewTicket("KR-OPEN", 4L, "batch refresh", "superadmin"))
                .thenReturn(true);

        service.triggerIfThresholdReached(user(70), user(92), K4KycReviewTriggerService.SOURCE_BATCH_RECOMPUTE,
                "batch refresh", "superadmin", "batch-1:U1");

        verify(repository, never()).createScoreTriggeredKycReviewTicket(
                anyString(), anyString(), eq(92), eq(80), anyString(), anyString(), anyString());
        ArgumentCaptor<AuditLogWriteRequest> audit = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
        verify(auditLogService).recordRequired(audit.capture());
        assertThat(audit.getValue().getAction()).isEqualTo("K5_KYC_REVIEW_SCORE_TRIGGER_MERGED");
        assertThat(audit.getValue().getDetail().toString())
                .contains(K4KycReviewTriggerService.SOURCE_BATCH_RECOMPUTE, "batch-1:U1");
    }

    @Test
    void concurrentInsertWinnerIsLockedAndMergedWithoutASecondTicket() {
        when(repository.kycReviewTriggerScore()).thenReturn(85);
        when(repository.transitionK4KycReviewTriggerState("U1", 91, 85, "publish-1:U1")).thenReturn(true);
        KycReviewTicketContext winner = new KycReviewTicketContext(
                "KR-WINNER", "风险分触发", "U1", "in-review", "[]", 0L);
        when(repository.findOpenKycReviewTicketByUserForUpdate("U1"))
                .thenReturn(Optional.empty(), Optional.of(winner));
        doThrow(new DuplicateKeyException("open_user_key"))
                .when(repository).createScoreTriggeredKycReviewTicket(
                        anyString(), eq("U1"), eq(91), eq(85),
                        eq(K4KycReviewTriggerService.SOURCE_MODEL_PUBLISH), anyString(), anyString());
        when(repository.mergeOpenKycReviewTicket("KR-WINNER", 0L, "model published", "superadmin"))
                .thenReturn(true);

        service.triggerIfThresholdReached(user(80), user(91), K4KycReviewTriggerService.SOURCE_MODEL_PUBLISH,
                "model published", "superadmin", "publish-1:U1");

        verify(repository).mergeOpenKycReviewTicket("KR-WINNER", 0L, "model published", "superadmin");
        ArgumentCaptor<AuditLogWriteRequest> audit = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
        verify(auditLogService).recordRequired(audit.capture());
        assertThat(audit.getValue().getResourceId()).isEqualTo("KR-WINNER");
        assertThat(audit.getValue().getDetail().toString())
                .contains(K4KycReviewTriggerService.SOURCE_MODEL_PUBLISH);
    }

    @Test
    void unknownSourceFailsClosedBeforeAnyTicketMutation() {
        assertThatThrownBy(() -> service.triggerIfThresholdReached(
                user(80), user(99), "K4_UNKNOWN", "unknown", "system", "idem"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("K4_K5_TRIGGER_SOURCE_INVALID");

        verify(repository, never()).kycReviewTriggerScore();
    }

    @Test
    void persistentHighStateSuppressesRepeatedRefreshAndMerge() {
        when(repository.kycReviewTriggerScore()).thenReturn(85);
        when(repository.transitionK4KycReviewTriggerState("U1", 91, 85, "fact:U1:10"))
                .thenReturn(false);

        boolean triggered = service.triggerIfThresholdReached(
                user(90), user(91), K4KycReviewTriggerService.SOURCE_FACT_REFRESH,
                "same high score facts refreshed", "system", "fact:U1:10");

        assertThat(triggered).isFalse();
        verify(repository, never()).findOpenKycReviewTicketByUserForUpdate(anyString());
        verify(repository, never()).mergeOpenKycReviewTicket(anyString(), org.mockito.ArgumentMatchers.anyLong(),
                anyString(), anyString());
    }

    private RiskScoreUserView user(int score) {
        return new RiskScoreUserView(
                "U1", score, score, false, "高风险", "bad", "k4-v1", 9L,
                "2026-07-22 21:00:00", "刚刚", List.of());
    }
}
