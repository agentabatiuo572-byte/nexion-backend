package ffdd.opsconsole.shared.exception;

public class BizException extends RuntimeException {
    private final int code;

    public BizException(String message) {
        this(400, message);
    }

    public BizException(int code, String message) {
        super(message);
        this.code = code;
    }

    public int getCode() {
        return code;
    }

    /** Structured rejection payload already exposed by the exception-specific handlers. */
    public Object errorData() {
        return null;
    }
}

