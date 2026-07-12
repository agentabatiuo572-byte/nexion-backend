package ffdd.opsconsole.content.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.content.domain.NotificationAudienceTarget;
import ffdd.opsconsole.content.domain.NotificationCampaignRepository;
import ffdd.opsconsole.content.domain.NotificationCampaignRow;
import ffdd.opsconsole.shared.audit.AuditLogService;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

class NotificationCampaignDispatchExecutorTest {
    private final NotificationCampaignRepository repository = mock(NotificationCampaignRepository.class);
    private final AuditLogService audit = mock(AuditLogService.class);
    private final NotificationCampaignDispatchExecutor executor = new NotificationCampaignDispatchExecutor(repository, audit);
    private final LocalDateTime now = LocalDateTime.of(2026, 7, 12, 10, 0);

    @Test
    void dispatchRunsInIndependentTransactionSoCrashRollsBackClaimAndInsert() throws Exception {
        Transactional annotation = NotificationCampaignDispatchExecutor.class
                .getMethod("dispatchImmediate", String.class, String.class, String.class, String.class,
                        String.class, String.class, LocalDateTime.class)
                .getAnnotation(Transactional.class);

        assertThat(annotation).isNotNull();
        assertThat(annotation.propagation()).isEqualTo(Propagation.REQUIRES_NEW);
    }

    @Test
    void emptyAudienceFailsBeforeAnyNotificationIsInserted() {
        NotificationCampaignRow campaign = campaign("draft");
        when(repository.findCampaign("CMP-1")).thenReturn(Optional.of(campaign));
        when(repository.claimForImmediateDispatch("CMP-1", now)).thenReturn(true);
        when(repository.estimateAudience(campaign.audienceTarget(), "P3", now)).thenReturn(0L);

        assertThatThrownBy(() -> executor.dispatchImmediate(
                "CMP-1", "biz-1", "P3", "operator", "idem-1", "立即发送系统维护通知", now))
                .isInstanceOf(NotificationCampaignDispatchExecutor.AudienceEmptyException.class);

        verify(repository, never()).dispatchCampaignNotification(any(), any(), any(), any(), any(), any());
    }

    @Test
    void concurrentRepeatedClickLosesAtomicClaimAndCannotInsertAgain() {
        when(repository.findCampaign("CMP-1")).thenReturn(Optional.of(campaign("scheduled")));
        when(repository.claimForImmediateDispatch("CMP-1", now)).thenReturn(false);

        assertThatThrownBy(() -> executor.dispatchImmediate(
                "CMP-1", "biz-1", "P3", "operator", "idem-1", "立即发送系统维护通知", now))
                .isInstanceOf(NotificationCampaignDispatchExecutor.ConcurrentDispatchException.class);

        verify(repository, never()).dispatchCampaignNotification(any(), any(), any(), any(), any(), any());
    }

    @Test
    void successfulDispatchMarksCanonicalFeedDeliveredAndAppliesCap() {
        NotificationCampaignRow campaign = campaign("draft");
        when(repository.findCampaign("CMP-1")).thenReturn(Optional.of(campaign));
        when(repository.claimForImmediateDispatch("CMP-1", now)).thenReturn(true);
        when(repository.estimateAudience(campaign.audienceTarget(), "P3", now)).thenReturn(2L);
        when(repository.dispatchCampaignNotification("CMP-1", "biz-1", "P3", "立即下发中", "operator", now))
                .thenReturn(2);

        int delivered = executor.dispatchImmediate(
                "CMP-1", "biz-1", "P3", "operator", "idem-1", "立即发送系统维护通知", now);

        assertThat(delivered).isEqualTo(2);
        verify(repository).completeDispatch("CMP-1", "SENT", 2, "已进入用户通知流", "operator", now);
        verify(repository).applyRetention(now);
        verify(audit).record(any());
    }

    private NotificationCampaignRow campaign(String status) {
        return new NotificationCampaignRow(
                "CMP-1", "维护通知", "system", "high", "全量", "2", status, "-", "-", "-",
                "English", "中文", "Tiếng Việt", "-", null,
                new NotificationAudienceTarget("P1", "P6", "all", 0));
    }
}
