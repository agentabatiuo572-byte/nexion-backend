package ffdd.opsconsole.finance.hdpay;

public enum HdPayPayoutOutcome {
    PENDING, PAID, FAILED, MANUAL_REVIEW;
    public static HdPayPayoutOutcome from(int status) {
        return switch (status) {
            case 1, 2 -> PENDING;
            case 3 -> PAID;
            case 4, 5 -> FAILED; // Provider-authoritative query: returned / payout failed.
            default -> throw new HdPayGatewayException("HDPAY_PAYOUT_STATUS_INVALID", false);
        };
    }
}
