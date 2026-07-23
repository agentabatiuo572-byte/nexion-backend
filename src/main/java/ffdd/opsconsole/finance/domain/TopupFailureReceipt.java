package ffdd.opsconsole.finance.domain;

public record TopupFailureReceipt(
        String failureEventId,
        String requestHash,
        String paymentNo,
        String status) {
}
