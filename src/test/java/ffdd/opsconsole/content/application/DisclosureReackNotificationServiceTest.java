package ffdd.opsconsole.content.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.content.mapper.NotificationCampaignMapper;
import ffdd.opsconsole.shared.outbox.EventOutboxService;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class DisclosureReackNotificationServiceTest {
    private final NotificationCampaignMapper mapper = mock(NotificationCampaignMapper.class);
    private final EventOutboxService eventOutboxService = mock(EventOutboxService.class);
    private final DisclosureReackNotificationService service =
            new DisclosureReackNotificationService(mapper, eventOutboxService);

    @Test
    void deliversIdempotentCriticalI3NotificationsForAffectedCountryAliases() {
        LocalDateTime now = LocalDateTime.of(2026, 7, 13, 0, 0);
        String bizNo = "i5:reack:SBV:v14:"
                + DisclosureContentHash.ofParts("matrix-change-1").substring(0, 24);
        when(mapper.insertDisclosureReackNotifications(
                eq(bizNo), eq("SBV"), eq("v14"),
                org.mockito.ArgumentMatchers.anyList(), eq(now))).thenReturn(12);

        int queued = service.notifyPublished("sbv", "v13", "v14", List.of("VN"), "matrix-change-1", now);

        assertThat(queued).isEqualTo(12);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> aliases = ArgumentCaptor.forClass(List.class);
        verify(mapper).insertDisclosureReackNotifications(
                eq(bizNo), eq("SBV"), eq("v14"), aliases.capture(), eq(now));
        verify(mapper).markCampaignNotificationsDelivered(bizNo, now);
        ArgumentCaptor<Object> eventPayload = ArgumentCaptor.forClass(Object.class);
        verify(eventOutboxService).publish(
                eq("DISCLOSURE_REACK"), eq(bizNo), eq("disclosure.reack_triggered"), eventPayload.capture());
        assertThat(eventPayload.getValue()).isInstanceOf(Map.class);
        Map<?, ?> payload = (Map<?, ?>) eventPayload.getValue();
        assertThat(payload.get("jurisdiction")).isEqualTo("SBV");
        assertThat(payload.get("from_version")).isEqualTo("v13");
        assertThat(payload.get("to_version")).isEqualTo("v14");
        assertThat(payload.get("affected_user_count")).isEqualTo(12);
        assertThat(aliases.getValue()).contains("VN", "84", "+84", "VIỆT NAM", "越南");
    }

    @Test
    void rejectsPublicationWithoutTargetCountries() {
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> service.notifyPublished(
                                "SBV", "v13", "v14", List.of(), "matrix-change-1", LocalDateTime.now()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("DISCLOSURE_REACK_COUNTRIES_REQUIRED");
    }

    @Test
    void repeatedActivationOfTheSameVersionUsesANewNotificationEventKey() {
        LocalDateTime now = LocalDateTime.of(2026, 7, 13, 0, 0);

        service.notifyPublished("SBV", "v13", "v14", List.of("VN"), "matrix-change-1", now);
        service.notifyPublished("SBV", "v13", "v14", List.of("VN"), "matrix-change-2", now.plusMinutes(1));

        ArgumentCaptor<String> bizNo = ArgumentCaptor.forClass(String.class);
        verify(mapper, org.mockito.Mockito.times(2)).insertDisclosureReackNotifications(
                bizNo.capture(), eq("SBV"), eq("v14"),
                org.mockito.ArgumentMatchers.anyList(), org.mockito.ArgumentMatchers.any(LocalDateTime.class));
        assertThat(bizNo.getAllValues()).hasSize(2).doesNotHaveDuplicates();
        assertThat(bizNo.getAllValues()).allMatch(value -> value.startsWith("i5:reack:SBV:v14:"));
    }
}
