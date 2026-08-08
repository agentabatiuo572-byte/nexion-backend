package ffdd.opsconsole.finance.cregis;

public final class CregisGatewayException extends RuntimeException {
    public enum Kind {
        CONFIGURATION,
        REJECTED,
        CONFLICT,
        UNAVAILABLE,
        INVALID_RESPONSE,
        SUBMISSION_UNKNOWN
    }

    private final Kind kind;

    public CregisGatewayException(Kind kind, String safeCode) {
        super(safeCode);
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }
}
