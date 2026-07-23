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
import ffdd.opsconsole.content.domain.NotificationEventFact;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.outbox.EventOutboxService;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

class NotificationCampaignDispatchExecutorTest {
    private final NotificationCampaignRepository repository = mock(NotificationCampaignRepository.class);
    private final AuditLogService audit = mock(AuditLogService.class);
    private final EventOutboxService outbox = mock(EventOutboxService.class);
    private final NotificationCampaignDispatchExecutor executor = new NotificationCampaignDispatchExecutor(repository, audit, outbox);
    private final LocalDateTime now = LocalDateTime.of(2026, 7, 12, 10, 0);

    @Test
    void immediateDispatchJoinsDurableIdempotencyTransaction() throws Exception {
        Transactional annotation = NotificationCampaignDispatchExecutor.class
                .getMethod("dispatchImmediate", String.class, String.class, String.class, String.class,
                        String.class, String.class, long.class, LocalDateTime.class)
                .getAnnotation(Transactional.class);

        assertThat(annotation).isNotNull();
        assertThat(annotation.propagation()).isEqualTo(Propagation.REQUIRED);
    }

    @Test
    void emptyAudienceFailsBeforeAnyNotificationIsInserted() {
        NotificationCampaignRow campaign = campaign("draft");
        when(repository.findCampaign("CMP-1")).thenReturn(Optional.of(campaign));
        when(repository.claimForImmediateDispatch("CMP-1", 0L, now)).thenReturn(true);
        when(repository.estimateAudience(campaign.audienceTarget(), "P3", now)).thenReturn(0L);

        assertThatThrownBy(() -> executor.dispatchImmediate(
                "CMP-1", "biz-1", "P3", "operator", "idem-1", "立即发送系统维护通知", 0L, now))
                .isInstanceOf(NotificationCampaignDispatchExecutor.AudienceEmptyException.class);

        verify(repository, never()).dispatchCampaignNotification(any(), any(), any(), any(), any(), any());
    }

    @Test
    void concurrentRepeatedClickLosesAtomicClaimAndCannotInsertAgain() {
        when(repository.findCampaign("CMP-1")).thenReturn(Optional.of(campaign("scheduled")));
        when(repository.claimForImmediateDispatch("CMP-1", 0L, now)).thenReturn(false);

        assertThatThrownBy(() -> executor.dispatchImmediate(
                "CMP-1", "biz-1", "P3", "operator", "idem-1", "立即发送系统维护通知", 0L, now))
                .isInstanceOf(NotificationCampaignDispatchExecutor.ConcurrentDispatchException.class);

        verify(repository, never()).dispatchCampaignNotification(any(), any(), any(), any(), any(), any());
    }

    @Test
    void successfulDispatchMarksCanonicalFeedDeliveredAndAppliesCap() {
        NotificationCampaignRow campaign = campaign("draft");
        when(repository.findCampaign("CMP-1")).thenReturn(Optional.of(campaign));
        when(repository.claimForImmediateDispatch("CMP-1", 0L, now)).thenReturn(true);
        when(repository.estimateAudience(campaign.audienceTarget(), "P3", now)).thenReturn(2L);
        when(repository.dispatchCampaignNotification("CMP-1", "biz-1", "P3", "立即下发中", "operator", now))
                .thenReturn(2);
        when(repository.listNotificationEventFactsByBizNo("biz-1", "P3", now)).thenReturn(List.of(
                new NotificationEventFact(91L, 7L, "system", "high", "/pages/me/kyc", false, "P3", 4, "2026-W10"),
                new NotificationEventFact(92L, 8L, "system", "high", "/pages/me/kyc", false, "P3", 2, "2026-W20")));

        int delivered = executor.dispatchImmediate(
                "CMP-1", "biz-1", "P3", "operator", "idem-1", "立即发送系统维护通知", 0L, now);

        assertThat(delivered).isEqualTo(2);
        verify(repository).completeDispatch("CMP-1", "SENT", 2, "已进入用户通知流", "operator", now);
        verify(repository).applyRetention(now);
        verify(audit).recordRequired(any());
        verify(outbox, org.mockito.Mockito.times(2)).publishUserEvent(
                org.mockito.ArgumentMatchers.eq("NOTIFICATION"), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.eq("notification.delivered"), org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.eq("P3"), org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any());
    }

    private NotificationCampaignRow campaign(String status) {
        return new NotificationCampaignRow(
                "CMP-1", "维护通知", "system", "high", "全量", "2", status, "-", "-", "-",
                "English", "中文", "Tiếng Việt", "-", null,
                new NotificationAudienceTarget("P1", "P6", "all", 0));
    }
}
