package ffdd.opsconsole.shared.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.platform.application.A2RuntimePolicy;
import ffdd.opsconsole.platform.dto.H3OutboxRedriveRequest;
import ffdd.opsconsole.platform.dto.H3OutboxRedriveView;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyTransactionExecutor;
import ffdd.opsconsole.shared.outbox.mapper.EventConsumerDeliveryMapper;
import ffdd.opsconsole.shared.outbox.mapper.EventOutboxMapper;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

class H3DeadLetterRedriveServiceTest {
    private static final String EVENT_ID = "fddbdea83e5a47e3a26b27e8cfb0d959";
    private static final String EVENT_TYPE = "H3_STOREFRONT_THREE_PRODUCTS_VIEWED";
    private static final String DELIVERY_CONSUMER = "h3-quest-completion";

    private final EventOutboxMapper outboxMapper = Mockito.mock(EventOutboxMapper.class);
    private final EventConsumerDeliveryMapper deliveryMapper = Mockito.mock(EventConsumerDeliveryMapper.class);
    private final AdminIdempotencyService idempotency = Mockito.mock(AdminIdempotencyService.class);
    private final A2RuntimePolicy a2Policy = Mockito.mock(A2RuntimePolicy.class);
    private final AuditLogService audit = Mockito.mock(AuditLogService.class);
    private final H3DeadLetterRedriveService service =
            new H3DeadLetterRedriveService(outboxMapper, deliveryMapper, idempotency, a2Policy, audit);

    H3DeadLetterRedriveServiceTest() {
        when(idempotency.execute(anyString(), anyString(), anyString(), eq(H3OutboxRedriveView.class), any()))
                .thenAnswer(invocation -> ((Supplier<?>) invocation.getArgument(4)).get());
    }

    @Test
    void redrivesOnlyTheLockedDeadH3SourceAndItsCanonicalCompletionDeliveryAndAuditsBoth() {
        EventOutboxMapper.H3DeadLetterRow outbox = outbox();
        EventConsumerDeliveryMapper.H3DeadLetterDeliveryRow delivery = delivery();
        when(outboxMapper.lockDeadH3ThresholdEvent(EVENT_ID)).thenReturn(outbox);
        when(deliveryMapper.lockDeadH3QuestCompletionDelivery(EVENT_ID)).thenReturn(delivery);
        when(deliveryMapper.redriveDeadH3QuestCompletionDelivery(EVENT_ID, 5)).thenReturn(1);
        when(outboxMapper.redriveDeadH3ThresholdEvent(EVENT_ID, 5)).thenReturn(1);

        H3OutboxRedriveView view = service.redrive(EVENT_ID, "redrive-fddb-1", request(5, 5));

        assertThat(view).isEqualTo(new H3OutboxRedriveView(
                EVENT_ID, EVENT_TYPE, "PENDING", 5, "A4_SCHEMA_PROPERTY_NOT_REGISTERED",
                "FAILED", 5, "A4_SCHEMA_PROPERTY_NOT_REGISTERED"));
        verify(outboxMapper).lockDeadH3ThresholdEvent(EVENT_ID);
        verify(deliveryMapper).lockDeadH3QuestCompletionDelivery(EVENT_ID);
        verify(deliveryMapper).redriveDeadH3QuestCompletionDelivery(EVENT_ID, 5);
        verify(outboxMapper).redriveDeadH3ThresholdEvent(EVENT_ID, 5);
        ArgumentCaptor<AuditLogWriteRequest> auditWrite = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
        verify(audit).recordRequired(auditWrite.capture());
        assertThat(auditWrite.getValue().getAction()).isEqualTo("A4_H3_OUTBOX_REDRIVEN");
        assertThat(auditWrite.getValue().getResourceId()).isEqualTo(EVENT_ID);
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> detail = (java.util.Map<String, Object>) auditWrite.getValue().getDetail();
        assertThat(detail).containsEntry("statusBefore", "DEAD")
                .containsEntry("statusAfter", "PENDING")
                .containsEntry("retryCountBefore", 5)
                .containsEntry("expectedRetryCount", 5)
                .containsEntry("lastErrorBefore", "A4_SCHEMA_PROPERTY_NOT_REGISTERED")
                .containsEntry("deliveryConsumerGroup", DELIVERY_CONSUMER)
                .containsEntry("deliveryStatusBefore", "DEAD")
                .containsEntry("deliveryStatusAfter", "FAILED")
                .containsEntry("expectedDeliveryStatus", "DEAD")
                .containsEntry("deliveryAttemptCountBefore", 5)
                .containsEntry("expectedDeliveryAttemptCount", 5)
                .containsEntry("deliveryLastErrorBefore", "A4_SCHEMA_PROPERTY_NOT_REGISTERED")
                .containsEntry("failureEvidencePreserved", true)
                .containsEntry("payloadUnchanged", true)
                .containsEntry("sourceUnchanged", true);
        assertThat(detail).doesNotContainKeys("payload", "source", "eventTs", "publishedAt");
    }

    @Test
    void rejectsAnyEventThatIsNotAnEligibleDeadH3ThresholdFact() {
        when(outboxMapper.lockDeadH3ThresholdEvent(EVENT_ID)).thenReturn(null);

        BizException failure = org.assertj.core.api.Assertions.catchThrowableOfType(
                () -> service.redrive(EVENT_ID, "redrive-fddb-2", request(5, 5)), BizException.class);
        assertThat(failure).hasMessage("A4_H3_OUTBOX_REDRIVE_NOT_ELIGIBLE");
        assertThat(failure.getCode()).isEqualTo(409);

        verify(deliveryMapper, never()).lockDeadH3QuestCompletionDelivery(anyString());
        verify(outboxMapper, never()).redriveDeadH3ThresholdEvent(anyString(), anyInt());
        verify(deliveryMapper, never()).redriveDeadH3QuestCompletionDelivery(anyString(), anyInt());
        verify(audit, never()).recordRequired(any());
    }

    @Test
    void rejectsWhenTheExactCanonicalCompletionDeliveryIsNotDead() {
        when(outboxMapper.lockDeadH3ThresholdEvent(EVENT_ID)).thenReturn(outbox());
        when(deliveryMapper.lockDeadH3QuestCompletionDelivery(EVENT_ID)).thenReturn(null);

        assertThatThrownBy(() -> service.redrive(EVENT_ID, "redrive-fddb-2b", request(5, 5)))
                .isInstanceOf(BizException.class)
                .hasMessage("A4_H3_OUTBOX_REDRIVE_NOT_ELIGIBLE");

        verify(outboxMapper, never()).redriveDeadH3ThresholdEvent(anyString(), anyInt());
        verify(deliveryMapper, never()).redriveDeadH3QuestCompletionDelivery(anyString(), anyInt());
        verify(audit, never()).recordRequired(any());
    }

    @Test
    void staleDualSnapshotDoesNotMutateOrAudit() {
        when(outboxMapper.lockDeadH3ThresholdEvent(EVENT_ID)).thenReturn(outbox());
        when(deliveryMapper.lockDeadH3QuestCompletionDelivery(EVENT_ID)).thenReturn(delivery());

        assertThatThrownBy(() -> service.redrive(EVENT_ID, "redrive-fddb-3", request(4, 5)))
                .isInstanceOf(BizException.class)
                .hasMessage("A4_H3_OUTBOX_REDRIVE_STATE_STALE");

        verify(outboxMapper, never()).redriveDeadH3ThresholdEvent(anyString(), anyInt());
        verify(deliveryMapper, never()).redriveDeadH3QuestCompletionDelivery(anyString(), anyInt());
        verify(audit, never()).recordRequired(any());
    }

    @Test
    void rejectsAStaleCanonicalCompletionDeliveryAttemptWithoutTouchingEitherLayer() {
        when(outboxMapper.lockDeadH3ThresholdEvent(EVENT_ID)).thenReturn(outbox());
        when(deliveryMapper.lockDeadH3QuestCompletionDelivery(EVENT_ID)).thenReturn(delivery());

        assertThatThrownBy(() -> service.redrive(EVENT_ID, "redrive-fddb-delivery-stale", request(5, 4)))
                .isInstanceOf(BizException.class)
                .hasMessage("A4_H3_OUTBOX_REDRIVE_STATE_STALE");

        verify(outboxMapper, never()).redriveDeadH3ThresholdEvent(anyString(), anyInt());
        verify(deliveryMapper, never()).redriveDeadH3QuestCompletionDelivery(anyString(), anyInt());
        verify(audit, never()).recordRequired(any());
    }

    @Test
    void rejectsAnyDeliveryStatusOtherThanTheFrozenDeadState() {
        assertThatThrownBy(() -> service.redrive(EVENT_ID, "redrive-fddb-delivery-status",
                new H3OutboxRedriveRequest("拒绝非终态消费者投递恢复", 5, "SUCCESS", 5)))
                .isInstanceOf(BizException.class)
                .hasMessage("A4_H3_OUTBOX_DELIVERY_STATUS_INVALID");

        verify(outboxMapper, never()).lockDeadH3ThresholdEvent(anyString());
        verify(deliveryMapper, never()).lockDeadH3QuestCompletionDelivery(anyString());
    }

    @Test
    void previewReturnsOnlyTheDualDeadSnapshotWithoutCallingMutationMappers() {
        when(outboxMapper.findDeadH3ThresholdEvent(EVENT_ID)).thenReturn(outbox());
        when(deliveryMapper.findDeadH3QuestCompletionDelivery(EVENT_ID)).thenReturn(delivery());

        H3OutboxRedriveView preview = service.preview(EVENT_ID);

        assertThat(preview.status()).isEqualTo("DEAD");
        assertThat(preview.retryCount()).isEqualTo(5);
        assertThat(preview.deliveryStatus()).isEqualTo("DEAD");
        assertThat(preview.deliveryAttemptCount()).isEqualTo(5);
        verify(outboxMapper, never()).lockDeadH3ThresholdEvent(anyString());
        verify(deliveryMapper, never()).lockDeadH3QuestCompletionDelivery(anyString());
        verify(outboxMapper, never()).redriveDeadH3ThresholdEvent(anyString(), anyInt());
        verify(deliveryMapper, never()).redriveDeadH3QuestCompletionDelivery(anyString(), anyInt());
    }

    @Test
    void previewDoesNotReflectUntrustedFailureText() {
        when(outboxMapper.findDeadH3ThresholdEvent(EVENT_ID)).thenReturn(new EventOutboxMapper.H3DeadLetterRow(
                EVENT_ID, EVENT_TYPE, 5, "sql failed payload=private"));
        when(deliveryMapper.findDeadH3QuestCompletionDelivery(EVENT_ID)).thenReturn(new EventConsumerDeliveryMapper.H3DeadLetterDeliveryRow(
                EVENT_ID, DELIVERY_CONSUMER, "DEAD", 5, "stacktrace customer@example.test"));

        H3OutboxRedriveView preview = service.preview(EVENT_ID);

        assertThat(preview.lastError()).isEqualTo("OUTBOX_DELIVERY_FAILED");
        assertThat(preview.deliveryLastError()).isEqualTo("OUTBOX_DELIVERY_FAILED");
    }

    @Test
    void auditFailureEscapesTheClaimedActionSoTheTransactionalExecutorRollsBackBothCasUpdates() throws Exception {
        when(outboxMapper.lockDeadH3ThresholdEvent(EVENT_ID)).thenReturn(outbox());
        when(deliveryMapper.lockDeadH3QuestCompletionDelivery(EVENT_ID)).thenReturn(delivery());
        when(deliveryMapper.redriveDeadH3QuestCompletionDelivery(EVENT_ID, 5)).thenReturn(1);
        when(outboxMapper.redriveDeadH3ThresholdEvent(EVENT_ID, 5)).thenReturn(1);
        org.mockito.Mockito.doThrow(new IllegalStateException("audit unavailable"))
                .when(audit).recordRequired(any());

        assertThatThrownBy(() -> service.redrive(EVENT_ID, "redrive-fddb-audit-failure", request(5, 5)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("audit unavailable");
        assertThat(AdminIdempotencyTransactionExecutor.class
                .getMethod("runClaimed", Long.class, java.util.function.Supplier.class)
                .getAnnotation(org.springframework.transaction.annotation.Transactional.class)).isNotNull();
    }

    private H3OutboxRedriveRequest request(int retryCount, int deliveryAttemptCount) {
        return new H3OutboxRedriveRequest("修复 schema 后恢复同一阈值事实投递", retryCount, "DEAD", deliveryAttemptCount);
    }

    private EventOutboxMapper.H3DeadLetterRow outbox() {
        return new EventOutboxMapper.H3DeadLetterRow(
                EVENT_ID, EVENT_TYPE, 5, "A4_SCHEMA_PROPERTY_NOT_REGISTERED");
    }

    private EventConsumerDeliveryMapper.H3DeadLetterDeliveryRow delivery() {
        return new EventConsumerDeliveryMapper.H3DeadLetterDeliveryRow(
                EVENT_ID, DELIVERY_CONSUMER, "DEAD", 5, "A4_SCHEMA_PROPERTY_NOT_REGISTERED");
    }
}
