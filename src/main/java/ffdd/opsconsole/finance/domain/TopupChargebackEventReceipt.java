package ffdd.opsconsole.finance.domain;

public record TopupChargebackEventReceipt(
        String chargebackEventId,
        String requestHash,
        String paymentNo,
        String status) {
}
