package ffdd.opsconsole.content.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.content.domain.NotificationAudienceTarget;
import ffdd.opsconsole.content.domain.NotificationCampaignRepository;
import ffdd.opsconsole.content.domain.NotificationCampaignRow;
import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import ffdd.opsconsole.shared.audit.AuditLogService;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

class ContentNotificationDispatchFacadeAdapterTest {
    private final NotificationCampaignRepository repository = mock(NotificationCampaignRepository.class);
    private final AuditLogService audit = mock(AuditLogService.class);
    private final PlatformConfigFacade config = mock(PlatformConfigFacade.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-07-15T10:00:00Z"), ZoneOffset.UTC);
    private final ContentNotificationDispatchFacadeAdapter adapter =
            new ContentNotificationDispatchFacadeAdapter(repository, audit, clock, config);

    @Test
    void emergencyDispatchUsesIndependentTransactionAndAtomicScheduledClaim() throws Exception {
        Transactional annotation = ContentNotificationDispatchFacadeAdapter.class
                .getMethod("dispatchEmergencyCampaign", String.class, String.class, String.class, String.class, String.class)
                .getAnnotation(Transactional.class);
        assertThat(annotation).isNotNull();
        assertThat(annotation.propagation()).isEqualTo(Propagation.REQUIRES_NEW);

        when(repository.findCampaign("CMP-1")).thenReturn(Optional.of(campaign()));
        when(repository.claimScheduled("CMP-1", LocalDateTime.of(2026, 7, 15, 10, 0))).thenReturn(false);

        assertThat(adapter.dispatchEmergencyCampaign("CMP-1", "SOP-1", "EXEC-1", "operator", "incident reason"))
                .isEmpty();
        verify(repository, never()).dispatchCampaignNotification(any(), any(), any(), any(), any(), any());
    }

    @Test
    void claimedCampaignIsDispatchedOnlyOnceAndCompleted() {
        LocalDateTime now = LocalDateTime.of(2026, 7, 15, 10, 0);
        when(repository.findCampaign("CMP-1")).thenReturn(Optional.of(campaign()));
        when(repository.claimScheduled("CMP-1", now)).thenReturn(true);
        when(config.activeValue("growth.phase.current")).thenReturn(Optional.of("P3"));
        when(repository.estimateAudience(campaign().audienceTarget(), "P3", now)).thenReturn(2L);
        when(repository.dispatchCampaignNotification("CMP-1", "j4:EXEC-1:notify:CMP-1", "P3",
                "J4 SOP-1 emergency dispatch", "operator", now)).thenReturn(2);

        var result = adapter.dispatchEmergencyCampaign("CMP-1", "SOP-1", "EXEC-1", "operator", "incident reason");

        assertThat(result).isPresent();
        assertThat(result.orElseThrow().notificationCount()).isEqualTo(2);
        verify(repository).applyRetention(now);
        verify(repository).completeDispatch("CMP-1", "SENT", 2, "应急下发", "operator", now);
        verify(audit).recordRequired(any());
    }

    @Test
    void emergencyDispatchCanBeReconciledByItsExactExecutionBusinessNumber() {
        when(repository.countNotificationsByBizNo("j4:EXEC-1:notify:CMP-1")).thenReturn(2);
        when(repository.findCampaign("CMP-1")).thenReturn(Optional.of(campaign()));

        var reconciled = adapter.findEmergencyDispatch("CMP-1", "EXEC-1");

        assertThat(reconciled).isPresent();
        assertThat(reconciled.orElseThrow().notificationCount()).isEqualTo(2);
    }

    private NotificationCampaignRow campaign() {
        return new NotificationCampaignRow(
                "CMP-1", "维护通知", "system", "critical", "全量", "2", "scheduled", "-", "-", "-",
                "English", "中文", "Tiếng Việt", "-", null,
                new NotificationAudienceTarget("P1", "P6", "all", 0));
    }
}
