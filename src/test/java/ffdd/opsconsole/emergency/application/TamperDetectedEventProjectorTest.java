package ffdd.opsconsole.emergency.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.emergency.domain.EmergencyControlRepository;
import ffdd.opsconsole.emergency.domain.TamperEventRecord;
import ffdd.opsconsole.risk.facade.RiskTamperSignalFacade;
import ffdd.opsconsole.shared.outbox.EventConsumerDeliveryService;
import ffdd.opsconsole.shared.outbox.EventOutboxMessage;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class TamperDetectedEventProjectorTest {
    private final EmergencyControlRepository repository = mock(EmergencyControlRepository.class);
    private final RiskTamperSignalFacade riskFacade = mock(RiskTamperSignalFacade.class);
    private final EventConsumerDeliveryService deliveryService = mock(EventConsumerDeliveryService.class);
    private final TamperDetectedEventProjector projector = new TamperDetectedEventProjector(
            repository, riskFacade, deliveryService, new ObjectMapper());

    @Test
    void persistsOnlyDownstreamAcknowledgedK4AndB5State() {
        EventOutboxMessage message = event("event-j3-1", """
                {"tamperPath":"free_trial_state","userId":42,"userNo":"U00000042",
                 "attackEffect":"无限领试用","blockedAtEndpoint":"GET /api/trial/eligibility",
                 "isServerAuthoritative":true,"eventCount":3}
                """);
        when(repository.settingValue("emergency.tamper.alert.feedK4")).thenReturn(Optional.of("true"));
        when(riskFacade.recordTamperSignal(
                "event-j3-1", 42L, "U00000042", "free_trial_state",
                "无限领试用", "GET /api/trial/eligibility", 3, true))
                .thenReturn(new RiskTamperSignalFacade.TamperProjectionResult(true, 3, true));

        projector.project(message, "event-j3-1");

        ArgumentCaptor<TamperEventRecord> event = ArgumentCaptor.forClass(TamperEventRecord.class);
        verify(repository).recordTamperEvent(event.capture());
        assertThat(event.getValue().k4Accepted()).isTrue();
        assertThat(event.getValue().k4Delta()).isEqualTo(3);
        assertThat(event.getValue().b5Accepted()).isTrue();
        verify(deliveryService).markSuccess(TamperDetectedEventConsumer.CONSUMER_GROUP, "event-j3-1", 1);
    }

    @Test
    void disablingK4StillProjectsTheCanonicalEventIntoB5() {
        EventOutboxMessage message = event("event-j3-b5", """
                {"tamperPath":"risk_disclosure_ack","userId":42,"userNo":"U00000042",
                 "attackEffect":"跳过披露","blockedAtEndpoint":"POST /api/legal/risk-disclosure/acknowledgment",
                 "isServerAuthoritative":true,"eventCount":1}
                """);
        when(repository.settingValue("emergency.tamper.alert.feedK4")).thenReturn(Optional.of("false"));
        when(riskFacade.recordTamperSignal(
                "event-j3-b5", 42L, "U00000042", "risk_disclosure_ack",
                "跳过披露", "POST /api/legal/risk-disclosure/acknowledgment", 1, false))
                .thenReturn(new RiskTamperSignalFacade.TamperProjectionResult(false, 0, true));

        projector.project(message, "event-j3-b5");

        ArgumentCaptor<TamperEventRecord> event = ArgumentCaptor.forClass(TamperEventRecord.class);
        verify(repository).recordTamperEvent(event.capture());
        assertThat(event.getValue().k4Accepted()).isFalse();
        assertThat(event.getValue().k4Delta()).isZero();
        assertThat(event.getValue().b5Accepted()).isTrue();
    }

    @Test
    void rejectsNonCanonicalPayloadBeforeAnyProjectionWrite() {
        EventOutboxMessage message = event("event-j3-bad", """
                {"tamperPath":"invented_path","userId":42,"isServerAuthoritative":false}
                """);

        assertThatThrownBy(() -> projector.project(message, "event-j3-bad"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("TAMPER_EVENT_NOT_SERVER_CANONICAL");
        verify(repository, never()).recordTamperEvent(any());
        verify(deliveryService, never()).markSuccess(any(), any(), any(Integer.class));
    }

    private EventOutboxMessage event(String eventId, String payload) {
        EventOutboxMessage message = new EventOutboxMessage();
        message.setEventId(eventId);
        message.setEventType(TamperDetectedEventConsumer.EVENT_TYPE);
        message.setAggregateType("RISK_TAMPER");
        message.setAggregateId("U00000042");
        message.setPayload(payload);
        return message;
    }
}
