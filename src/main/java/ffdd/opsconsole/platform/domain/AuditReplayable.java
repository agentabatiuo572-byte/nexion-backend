package ffdd.opsconsole.platform.domain;
import ffdd.opsconsole.shared.api.ApiResult;
public interface AuditReplayable {
    String domain();
    /** Current actor/object authorization only; never starts a replay or acquires business locks. */
    default ApiResult<Void> validateReplayPermission(AuditReplayCommand cmd) { return ApiResult.ok(); }
    ApiResult<?> replay(AuditReplayCommand cmd, AuditReplayContext ctx);
}
