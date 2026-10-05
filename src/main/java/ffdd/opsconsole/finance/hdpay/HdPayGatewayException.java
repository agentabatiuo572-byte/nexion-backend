package ffdd.opsconsole.finance.hdpay;

public final class HdPayGatewayException extends RuntimeException {
    private final boolean ambiguous;
    private final String providerReason;

    public HdPayGatewayException(String message, boolean ambiguous) {
        this(message, ambiguous, null, "");
    }

    public HdPayGatewayException(String message, boolean ambiguous, Throwable cause) {
        this(message, ambiguous, cause, "");
    }

    public HdPayGatewayException(String message, boolean ambiguous, Throwable cause, String providerReason) {
        super(message, cause);
        this.ambiguous = ambiguous;
        this.providerReason = providerReason;
    }

    public boolean ambiguous() {
        return ambiguous;
    }

    public String providerReason() {
        return providerReason;
    }
}
