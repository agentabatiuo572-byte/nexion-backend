package ffdd.opsconsole.finance.dto;

import java.time.LocalDateTime;

public record TopupCardFailureRequest(
        String failureEventId,
        String admissionEventId,
        String paymentNo,
        String orderNo,
        Long userId,
        String provider,
        String providerPaymentId,
        String failureStatus,
        String failureReason,
        LocalDateTime occurredAt) {
}
