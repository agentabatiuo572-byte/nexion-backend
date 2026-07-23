package ffdd.opsconsole.content.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.content.domain.AppNotificationPage;
import ffdd.opsconsole.content.domain.NotificationActionReceipt;
import ffdd.opsconsole.content.domain.NotificationCampaignRepository;
import ffdd.opsconsole.content.domain.NotificationEventFact;
import ffdd.opsconsole.shared.outbox.EventOutboxService;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class AppNotificationServiceTest {
    private final NotificationCampaignRepository repository = mock(NotificationCampaignRepository.class);
    private final EventOutboxService outbox = mock(EventOutboxService.class);
    private final AppNotificationService service = new AppNotificationService(repository, outbox);

    @Test
    void pageIsServerCanonicalAndAppliesRetentionBeforeReading() {
        when(repository.pageUserNotifications(7L, null, "high", 20))
                .thenReturn(new AppNotificationPage(List.of(), null, 0));

        var result = service.page(7L, null, "high", 20);

        assertThat(result.getCode()).isZero();
        verify(repository).applyRetentionForUser(7L);
        verify(repository).pageUserNotifications(7L, null, "high", 20);
    }

    @Test
    void markReadCannotMutateAnotherUsersNotification() {
        when(repository.lockNotificationEventFact(7L, 99L)).thenReturn(Optional.empty());

        var result = service.markRead(7L, 99L);

        assertThat(result.getCode()).isEqualTo(404);
        verify(repository, never()).markNotificationRead(7L, 99L);
        verify(outbox, never()).publishUserEvent(
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void markReadPublishesOneServerCanonicalEventInsideTheMutationBoundary() {
        when(repository.lockNotificationEventFact(7L, 99L)).thenReturn(Optional.of(fact(false, "system", "/pages/me/kyc")));
        when(repository.markNotificationRead(7L, 99L)).thenReturn(true);

        var result = service.markRead(7L, 99L);

        assertThat(result.getCode()).isZero();
        verify(outbox).publishUserEvent(
                org.mockito.ArgumentMatchers.eq("NOTIFICATION"), org.mockito.ArgumentMatchers.eq("99"),
                org.mockito.ArgumentMatchers.eq("notification.read"), org.mockito.ArgumentMatchers.eq(7L),
                org.mockito.ArgumentMatchers.eq("P3"), org.mockito.ArgumentMatchers.eq(4),
                org.mockito.ArgumentMatchers.eq("2026-W10"),
                org.mockito.ArgumentMatchers.argThat(payload -> payload.toString().contains("notification_id=99")));
    }

    @Test
    void repeatedMarkReadIsIdempotentAndDoesNotDoubleCount() {
        when(repository.lockNotificationEventFact(7L, 99L)).thenReturn(Optional.of(fact(true, "system", "/pages/me/kyc")));

        var result = service.markRead(7L, 99L);

        assertThat(result.getCode()).isZero();
        verify(repository, never()).markNotificationRead(7L, 99L);
        verify(outbox, never()).publishUserEvent(
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void ctaActionUsesTheServerRouteAndPersistsBeforePublishing() {
        when(repository.lockNotificationEventFact(7L, 99L)).thenReturn(Optional.of(fact(false, "commission", "/pages/me/wallet-repurchase")));
        when(repository.findNotificationActionReceipt("idem-action-001")).thenReturn(Optional.empty());
        when(repository.recordNotificationAction(7L, 99L, "cta", "/pages/me/wallet-repurchase", "idem-action-001"))
                .thenReturn(true);

        var result = service.recordAction(7L, 99L, "cta", "idem-action-001");

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().route()).isEqualTo("/pages/me/wallet-repurchase");
        assertThat(result.getData().recorded()).isTrue();
        verify(outbox).publishUserEvent(
                org.mockito.ArgumentMatchers.eq("NOTIFICATION"), org.mockito.ArgumentMatchers.eq("99"),
                org.mockito.ArgumentMatchers.eq("notification.swipe_action_taken"), org.mockito.ArgumentMatchers.eq(7L),
                org.mockito.ArgumentMatchers.eq("P3"), org.mockito.ArgumentMatchers.eq(4),
                org.mockito.ArgumentMatchers.eq("2026-W10"), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void duplicateBusinessActionDoesNotPublishASecondEvent() {
        when(repository.lockNotificationEventFact(7L, 99L)).thenReturn(Optional.of(fact(false, "commission", "/pages/me/wallet-repurchase")));
        when(repository.findNotificationActionReceipt("idem-action-002")).thenReturn(Optional.empty());
        when(repository.recordNotificationAction(7L, 99L, "cta", "/pages/me/wallet-repurchase", "idem-action-002"))
                .thenReturn(false);

        var result = service.recordAction(7L, 99L, "cta", "idem-action-002");

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().recorded()).isFalse();
        verify(outbox, never()).publishUserEvent(
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void reusedIdempotencyKeyCannotBeReboundToAnotherNotification() {
        when(repository.lockNotificationEventFact(7L, 99L)).thenReturn(Optional.of(fact(false, "commission", "/pages/me/wallet-repurchase")));
        when(repository.findNotificationActionReceipt("idem-action-003")).thenReturn(Optional.of(
                new NotificationActionReceipt(8L, 100L, "cta", "/pages/team/team", "idem-action-003")));

        var result = service.recordAction(7L, 99L, "cta", "idem-action-003");

        assertThat(result.getCode()).isEqualTo(409);
        verify(repository, never()).recordNotificationAction(
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void systemNotificationCannotInventASwipeConversionRoute() {
        when(repository.lockNotificationEventFact(7L, 99L)).thenReturn(Optional.of(fact(false, "system", "")));

        var result = service.recordAction(7L, 99L, "swipe_conversion", "idem-action-004");

        assertThat(result.getCode()).isEqualTo(422);
        verify(repository, never()).recordNotificationAction(
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString());
    }

    private NotificationEventFact fact(boolean read, String kind, String ctaHref) {
        return new NotificationEventFact(99L, 7L, kind, "high", ctaHref, read, "P3", 4, "2026-W10");
    }
}
