package ffdd.opsconsole.finance.dto;

public record TopupCardChargebackResult(
        String chargebackEventId,
        String paymentNo,
        String status,
        boolean replay) {
}
