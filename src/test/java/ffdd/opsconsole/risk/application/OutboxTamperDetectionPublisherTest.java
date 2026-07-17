package ffdd.opsconsole.risk.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.shared.outbox.EventOutboxService;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class OutboxTamperDetectionPublisherTest {
    private final EventOutboxService outboxService = mock(EventOutboxService.class);
    private final OutboxTamperDetectionPublisher publisher = new OutboxTamperDetectionPublisher(outboxService);

    @Test
    @SuppressWarnings("unchecked")
    void createsCanonicalServerAuthoritativeEventWithoutTrustingClientFlags() {
        when(outboxService.publish(eq("USER"), eq("42"), eq("RISK_TAMPER_DETECTED"), org.mockito.ArgumentMatchers.any()))
                .thenReturn("evt-42");

        String eventId = publisher.publish(
                42L, "risk_disclosure_ack", "server rejected stale version", "/api/legal/risk-disclosure/acknowledgment");

        assertThat(eventId).isEqualTo("evt-42");
        ArgumentCaptor<Object> payloadCaptor = ArgumentCaptor.forClass(Object.class);
        verify(outboxService).publish(eq("USER"), eq("42"), eq("RISK_TAMPER_DETECTED"), payloadCaptor.capture());
        Map<String, Object> payload = (Map<String, Object>) payloadCaptor.getValue();
        assertThat(payload)
                .containsEntry("userId", 42L)
                .containsEntry("tamperPath", "risk_disclosure_ack")
                .containsEntry("eventCount", 1)
                .containsEntry("isServerAuthoritative", true)
                .containsKeys("occurredAt", "blockedAtEndpoint", "attackEffect");
        assertThat(payload.get("userNo")).isNull();
    }

    @Test
    void rejectsUnknownPathsBeforeWritingTheOutbox() {
        assertThatThrownBy(() -> publisher.publish(42L, "client_supplied_path", "attempt", "/api/test"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("TAMPER_PATH_INVALID");
    }
}
