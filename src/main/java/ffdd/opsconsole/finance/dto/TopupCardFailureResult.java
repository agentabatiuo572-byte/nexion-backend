package ffdd.opsconsole.finance.dto;

public record TopupCardFailureResult(
        String failureEventId,
        String paymentNo,
        String status,
        boolean replay) {
}
