package ffdd.opsconsole.common.api;

public enum OpsErrorCode {
    VALIDATION_FAILED(400),
    IDEMPOTENCY_KEY_REQUIRED(400),
    UNAUTHORIZED(401),
    FORBIDDEN(403),
    INVALID_STATE_TRANSITION(409),
    REASON_REQUIRED(422),
    COVERAGE_BELOW_REDLINE(422),
    PHASE_PARAM_READONLY(422),
    RETIRED_FEATURE(422),
    INTERNAL_ERROR(500);

    private final int httpStatus;

    OpsErrorCode(int httpStatus) {
        this.httpStatus = httpStatus;
    }

    public int httpStatus() {
        return httpStatus;
    }
}
