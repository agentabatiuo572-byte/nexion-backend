package ffdd.opsconsole.finance.hdpay;

public final class HdPayGatewayException extends RuntimeException {
    private final boolean ambiguous;

    public HdPayGatewayException(String message, boolean ambiguous) {
        super(message);
        this.ambiguous = ambiguous;
    }

    public HdPayGatewayException(String message, boolean ambiguous, Throwable cause) {
        super(message, cause);
        this.ambiguous = ambiguous;
    }

    public boolean ambiguous() {
        return ambiguous;
    }
}
