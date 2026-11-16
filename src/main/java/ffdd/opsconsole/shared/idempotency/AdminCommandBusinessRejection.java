package ffdd.opsconsole.shared.idempotency;

import ffdd.opsconsole.shared.exception.BizException;

/**
 * A business rejection that must still roll back the caller's transaction while its trusted
 * server-side result is retained for command recovery. It is deliberately distinct from a
 * technical failure: only this exception carries a result worth persisting.
 */
public class AdminCommandBusinessRejection extends BizException {
    private final Object response;

    public AdminCommandBusinessRejection(int code, String message, Object response) {
        super(code, message);
        this.response = response;
    }

    public Object response() {
        return response;
    }
}
