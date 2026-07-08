package ffdd.opsconsole.platform.application;
import ffdd.opsconsole.platform.domain.AuditReplayCommand;
import ffdd.opsconsole.platform.domain.AuditReplayContext;
import ffdd.opsconsole.platform.domain.AuditReplayable;
import ffdd.opsconsole.shared.api.ApiResult;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

@Component
public class AuditReplayDispatcher {
    private final Map<String, AuditReplayable> byDomain;

    public AuditReplayDispatcher(List<AuditReplayable> replayables) {
        this.byDomain = replayables.stream()
                .collect(Collectors.toMap(AuditReplayable::domain, r -> r, (a, b) -> a));
    }

    public ApiResult<?> dispatch(AuditReplayCommand cmd, AuditReplayContext ctx) {
        AuditReplayable target = byDomain.get(cmd.domain());
        if (target == null) {
            return ApiResult.fail(422, "UNKNOWN_REPLAY_DOMAIN:" + cmd.domain());
        }
        return target.replay(cmd, ctx);
    }
}
