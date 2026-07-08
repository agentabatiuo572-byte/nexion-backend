package ffdd.opsconsole.platform.domain;
import ffdd.opsconsole.shared.api.ApiResult;
public interface AuditReplayable {
    String domain();
    ApiResult<?> replay(AuditReplayCommand cmd, AuditReplayContext ctx);
}
