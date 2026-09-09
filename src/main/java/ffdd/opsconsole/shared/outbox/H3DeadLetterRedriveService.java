package ffdd.opsconsole.shared.outbox;

import ffdd.opsconsole.platform.application.A2RuntimePolicy;
import ffdd.opsconsole.platform.dto.H3OutboxRedriveRequest;
import ffdd.opsconsole.platform.dto.H3OutboxRedriveView;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import ffdd.opsconsole.shared.outbox.mapper.EventConsumerDeliveryMapper;
import ffdd.opsconsole.shared.outbox.mapper.EventOutboxMapper;
import ffdd.opsconsole.shared.security.AdminActorResolver;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * One-way recovery boundary for governed H3 facts which exhausted ordinary
 * outbox retries. It preserves the original event and reopens only the paired
 * canonical completion delivery after both DEAD snapshots are CAS-verified.
 */
@Service
@RequiredArgsConstructor
public class H3DeadLetterRedriveService {
    private static final String IDEMPOTENCY_SCOPE_PREFIX = "A4_H3_OUTBOX_REDRIVE:";
    private static final String DEAD_STATUS = "DEAD";
    private static final String PENDING_STATUS = "PENDING";
    private static final String FAILED_STATUS = "FAILED";
    private static final String H3_QUEST_COMPLETION_CONSUMER = "h3-quest-completion";
    private static final String ELIGIBILITY_ERROR = "A4_H3_OUTBOX_REDRIVE_NOT_ELIGIBLE";
    private static final String STALE_ERROR = "A4_H3_OUTBOX_REDRIVE_STATE_STALE";

    private final EventOutboxMapper outboxMapper;
    private final EventConsumerDeliveryMapper deliveryMapper;
    private final AdminIdempotencyService idempotencyService;
    private final A2RuntimePolicy a2RuntimePolicy;
    private final AuditLogService auditLogService;

    public H3OutboxRedriveView redrive(String eventId, String idempotencyKey, H3OutboxRedriveRequest request) {
        String normalizedEventId = normalizeEventId(eventId);
        if (!StringUtils.hasText(idempotencyKey)) throw new BizException(400, "IDEMPOTENCY_KEY_REQUIRED");
        if (request == null) throw new BizException(422, "REASON_REQUIRED");
        a2RuntimePolicy.validateReason(request.reason());
        int expectedRetryCount = requiredCount(request.expectedRetryCount(), "A4_H3_OUTBOX_RETRY_COUNT_INVALID");
        String expectedDeliveryStatus = requiredDeadDeliveryStatus(request.expectedDeliveryStatus());
        int expectedDeliveryAttemptCount = requiredCount(
                request.expectedDeliveryAttemptCount(), "A4_H3_OUTBOX_DELIVERY_ATTEMPT_COUNT_INVALID");
        String actor = StringUtils.hasText(AdminActorResolver.resolve(null)) ? AdminActorResolver.resolve(null) : "unknown";
        String normalizedReason = request.reason().trim();
        String normalizedKey = idempotencyKey.trim();
        return idempotencyService.execute(IDEMPOTENCY_SCOPE_PREFIX + normalizedEventId, normalizedKey,
                requestHash(actor, normalizedEventId, normalizedReason, expectedRetryCount,
                        expectedDeliveryStatus, expectedDeliveryAttemptCount), H3OutboxRedriveView.class,
                () -> redriveLocked(actor, normalizedKey, normalizedEventId, normalizedReason, expectedRetryCount,
                        expectedDeliveryStatus, expectedDeliveryAttemptCount));
    }

    public H3OutboxRedriveView preview(String eventId) {
        String normalizedEventId = normalizeEventId(eventId);
        EventOutboxMapper.H3DeadLetterRow outbox = outboxMapper.findDeadH3ThresholdEvent(normalizedEventId);
        EventConsumerDeliveryMapper.H3DeadLetterDeliveryRow delivery =
                deliveryMapper.findDeadH3QuestCompletionDelivery(normalizedEventId);
        if (outbox == null || delivery == null) throw new BizException(409, ELIGIBILITY_ERROR);
        return view(outbox, DEAD_STATUS, delivery, DEAD_STATUS);
    }

    private H3OutboxRedriveView redriveLocked(
            String actor, String idempotencyKey, String eventId, String reason, int expectedRetryCount,
            String expectedDeliveryStatus, int expectedDeliveryAttemptCount) {
        EventOutboxMapper.H3DeadLetterRow outbox = outboxMapper.lockDeadH3ThresholdEvent(eventId);
        if (outbox == null) throw new BizException(409, ELIGIBILITY_ERROR);
        EventConsumerDeliveryMapper.H3DeadLetterDeliveryRow delivery =
                deliveryMapper.lockDeadH3QuestCompletionDelivery(eventId);
        if (delivery == null) throw new BizException(409, ELIGIBILITY_ERROR);
        if (outbox.retryCount() != expectedRetryCount
                || !expectedDeliveryStatus.equals(delivery.status())
                || delivery.attemptCount() != expectedDeliveryAttemptCount) {
            throw new BizException(409, STALE_ERROR);
        }
        int deliveryRecovered = deliveryMapper.redriveDeadH3QuestCompletionDelivery(eventId, expectedDeliveryAttemptCount);
        int outboxRedriven = outboxMapper.redriveDeadH3ThresholdEvent(eventId, expectedRetryCount);
        if (deliveryRecovered != 1 || outboxRedriven != 1) {
            throw new BizException(409, STALE_ERROR);
        }
        auditLogService.recordRequired(AuditLogWriteRequest.builder()
                .action("A4_H3_OUTBOX_REDRIVEN")
                .resourceType("EVENT_OUTBOX")
                .resourceId(eventId)
                .actorType("ADMIN")
                .actorUsername(actor)
                .result("SUCCESS")
                .riskLevel("HIGH")
                .detail(auditDetail(reason, idempotencyKey, outbox, delivery))
                .build());
        return view(outbox, PENDING_STATUS, delivery, FAILED_STATUS);
    }

    private H3OutboxRedriveView view(
            EventOutboxMapper.H3DeadLetterRow outbox, String outboxStatus,
            EventConsumerDeliveryMapper.H3DeadLetterDeliveryRow delivery, String deliveryStatus) {
        return new H3OutboxRedriveView(outbox.eventId(), outbox.eventType(), outboxStatus,
                outbox.retryCount(), safeFailureSummary(outbox.lastError()), deliveryStatus,
                delivery.attemptCount(), safeFailureSummary(delivery.lastError()));
    }

    private Map<String, Object> auditDetail(
            String reason, String idempotencyKey, EventOutboxMapper.H3DeadLetterRow outbox,
            EventConsumerDeliveryMapper.H3DeadLetterDeliveryRow delivery) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("reason", reason);
        detail.put("idempotencyKey", idempotencyKey);
        detail.put("eventType", outbox.eventType());
        detail.put("statusBefore", DEAD_STATUS);
        detail.put("statusAfter", PENDING_STATUS);
        detail.put("retryCountBefore", outbox.retryCount());
        detail.put("expectedRetryCount", outbox.retryCount());
        detail.put("lastErrorBefore", outbox.lastError());
        detail.put("deliveryConsumerGroup", H3_QUEST_COMPLETION_CONSUMER);
        detail.put("deliveryStatusBefore", delivery.status());
        detail.put("deliveryStatusAfter", FAILED_STATUS);
        detail.put("expectedDeliveryStatus", DEAD_STATUS);
        detail.put("deliveryAttemptCountBefore", delivery.attemptCount());
        detail.put("expectedDeliveryAttemptCount", delivery.attemptCount());
        detail.put("deliveryLastErrorBefore", delivery.lastError());
        detail.put("failureEvidencePreserved", true);
        detail.put("payloadUnchanged", true);
        detail.put("sourceUnchanged", true);
        return detail;
    }

    private String normalizeEventId(String value) {
        if (!StringUtils.hasText(value) || !value.trim().matches("[A-Za-z0-9][A-Za-z0-9._:-]{7,63}")) {
            throw new BizException(422, "A4_H3_OUTBOX_EVENT_ID_INVALID");
        }
        return value.trim();
    }

    private int requiredCount(Integer value, String errorCode) {
        if (value == null || value < 0 || value > 1000) throw new BizException(422, errorCode);
        return value;
    }

    private String requiredDeadDeliveryStatus(String value) {
        if (!DEAD_STATUS.equals(value)) throw new BizException(422, "A4_H3_OUTBOX_DELIVERY_STATUS_INVALID");
        return DEAD_STATUS;
    }

    /** Durable errors may be written by arbitrary consumers; API responses never mirror untrusted error text. */
    private String safeFailureSummary(String value) {
        String normalized = value == null ? "" : value.trim();
        return normalized.matches("[A-Z][A-Z0-9_:-]{0,127}")
                ? normalized
                : "OUTBOX_DELIVERY_FAILED";
    }

    private String requestHash(String actor, String eventId, String reason, int expectedRetryCount,
                               String expectedDeliveryStatus, int expectedDeliveryAttemptCount) {
        try {
            String value = "A4_H3_OUTBOX_REDRIVE\u001f" + actor + "\u001f" + eventId + "\u001f"
                    + expectedRetryCount + "\u001f" + expectedDeliveryStatus + "\u001f"
                    + expectedDeliveryAttemptCount + "\u001f" + reason;
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new BizException(422, "A4_H3_OUTBOX_REDRIVE_HASH_FAILED");
        }
    }
}
