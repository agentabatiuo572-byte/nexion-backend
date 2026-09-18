package ffdd.opsconsole.user.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.shared.outbox.*;
import ffdd.opsconsole.user.mapper.C1AuditEvidenceMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class C1AuditEvidenceConsumerTest {
    private final C1AuditEvidenceMapper evidence = mock(C1AuditEvidenceMapper.class);
    private final EventConsumerDeliveryService deliveries = mock(EventConsumerDeliveryService.class);
    private final C1AuditEvidenceConsumer consumer = new C1AuditEvidenceConsumer(evidence, deliveries, new ObjectMapper());

    @Test void exactProfileEvidenceCreatesReceiptAndDuplicateDoesNotWriteAgain() {
        EventOutboxMessage event = event(true);
        when(evidence.countProfileEvidence("evt", "U52", 52L, "operator", "SUPPORT", "[\"profile\"]")).thenReturn(1);
        when(deliveries.claim(any(), anyString(), anyString(), anyString(), eq(0)))
                .thenReturn(new EventConsumerDeliveryService.ConsumerClaim(true, "evt", "PROCESSING", 1),
                        new EventConsumerDeliveryService.ConsumerClaim(false, "evt", "SUCCESS", 1));
        EventConsumerDelivery receipt = new EventConsumerDelivery();
        receipt.setStatus("SUCCESS");
        when(deliveries.getByEvent("c1-audit-evidence", "evt")).thenReturn(receipt);
        consumer.onOutboxMessage(event);
        consumer.onOutboxMessage(event);
        verify(deliveries, times(1)).markSuccess("c1-audit-evidence", "evt", 1);
        verify(evidence, times(2)).countProfileEvidence("evt", "U52", 52L, "operator", "SUPPORT", "[\"profile\"]");
        verifyNoMoreInteractions(evidence);
    }

    @Test void legacyExportMatchesUniqueJobAndOriginalMaskedAudit() {
        EventOutboxMessage event = event(false);
        when(evidence.countExportEvidence("job-52", "operator", "a".repeat(64), 3)).thenReturn(1);
        when(deliveries.claim(any(), anyString(), anyString(), anyString(), eq(0)))
                .thenReturn(new EventConsumerDeliveryService.ConsumerClaim(false, "evt", "SUCCESS", 1));
        consumer.onOutboxMessage(event);
        verify(evidence).countExportEvidence("job-52", "operator", "a".repeat(64), 3);
        verify(deliveries, never()).markSuccess(anyString(), anyString(), anyInt());
    }

    @ParameterizedTest @ValueSource(ints = {0, 2})
    void absentOrAmbiguousEvidenceCannotClaimOrPublish(int matches) {
        when(evidence.countProfileEvidence(anyString(), anyString(), anyLong(), anyString(), anyString(), anyString())).thenReturn(matches);
        assertThatThrownBy(() -> consumer.onOutboxMessage(event(true)))
                .hasMessage("C1_AUDIT_EVIDENCE_NOT_UNIQUE");
        verifyNoInteractions(deliveries);
    }

    @ParameterizedTest @ValueSource(strings = {"aggregate", "canonical", "authority", "schema", "payload-id", "payload-authority", "type-case"})
    void supportedTypeWithMismatchedEnvelopeFailsClosed(String corruption) {
        EventOutboxMessage event = event(true);
        switch (corruption) {
            case "aggregate" -> event.setAggregateType("WALLET_LEDGER");
            case "canonical" -> event.setEventName("admin.unrelated");
            case "authority" -> event.setServerAuthoritative(false);
            case "schema" -> event.setSchemaRegistered(false);
            case "payload-id" -> event.setPayload(event.getPayload().replace("evt", "other"));
            case "payload-authority" -> event.setPayload(event.getPayload().replace("true", "false"));
            case "type-case" -> event.setEventType(event.getEventType().toLowerCase(java.util.Locale.ROOT));
        }
        assertThatThrownBy(() -> consumer.onOutboxMessage(event)).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(evidence, deliveries);
    }

    @Test void unsupportedEventsAreIgnored() {
        EventOutboxMessage event = event(true);
        event.setEventType("UNRELATED");
        consumer.onOutboxMessage(event);
        verifyNoInteractions(evidence, deliveries);
    }

    @Test void missingDurableReceiptCannotBeReportedSuccessful() {
        when(evidence.countProfileEvidence(anyString(), anyString(), anyLong(), anyString(), anyString(), anyString())).thenReturn(1);
        when(deliveries.claim(any(), anyString(), anyString(), anyString(), eq(0)))
                .thenReturn(new EventConsumerDeliveryService.ConsumerClaim(true, "evt", "PROCESSING", 1));
        assertThatThrownBy(() -> consumer.onOutboxMessage(event(true))).hasMessage("C1_AUDIT_RECEIPT_NOT_PERSISTED");
        verify(deliveries).markFailure("c1-audit-evidence", "evt", 0, "C1_AUDIT_RECEIPT_NOT_PERSISTED");
    }

    @ParameterizedTest @ValueSource(strings = {"PROCESSING", "DEAD", "SKIPPED", "FAILED"})
    void incompleteExistingReceiptCannotAcknowledge(String state) {
        when(evidence.countProfileEvidence(anyString(), anyString(), anyLong(), anyString(), anyString(), anyString())).thenReturn(1);
        when(deliveries.claim(any(), anyString(), anyString(), anyString(), eq(0)))
                .thenReturn(new EventConsumerDeliveryService.ConsumerClaim(false, "evt", state, 1));
        assertThatThrownBy(() -> consumer.onOutboxMessage(event(true))).hasMessage("C1_AUDIT_DELIVERY_NOT_COMPLETE");
        verify(deliveries, never()).markSuccess(anyString(), anyString(), anyInt());
    }

    private EventOutboxMessage event(boolean profile) {
        EventOutboxMessage e = new EventOutboxMessage();
        e.setEventId("evt");
        e.setEventType(profile ? "ADMIN_USER_PROFILE_VIEWED" : "ADMIN_USER_LIST_EXPORTED");
        e.setEventName(profile ? "admin.user_profile_viewed" : "admin.user_list_exported");
        e.setAggregateType(profile ? "USER_PROFILE" : "USER_PROFILE_EXPORT");
        e.setAggregateId(profile ? "U52" : "job-52");
        e.setServerAuthoritative(true); e.setAnalyticsEvent(true); e.setSchemaRegistered(true);
        e.setPayload("{\"event_id\":\"evt\",\"event_name\":\"" + e.getEventName()
                + "\",\"is_server_authoritative\":true,\"target_user_id\":52,\"viewer_operator\":\"operator\",\"viewer_role\":\"SUPPORT\",\"cards_viewed\":[\"profile\"],"
                + "\"exporter_operator\":\"operator\",\"filter_hash\":\"" + "a".repeat(64) + "\",\"row_count\":3}");
        return e;
    }
}
