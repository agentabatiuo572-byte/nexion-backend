package ffdd.opsconsole.platform.application;
import ffdd.opsconsole.finance.application.WithdrawalConfigApproval;
import ffdd.opsconsole.platform.domain.AuditReplayCommand;
import ffdd.opsconsole.platform.domain.AuditReplayContext;
import ffdd.opsconsole.platform.domain.AuditReplayable;
import ffdd.opsconsole.shared.api.ApiResult;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class AuditReplayDispatcher {
    private final List<AuditReplayable> replayables;
    @Autowired
    private WithdrawalConfigApproval withdrawalConfigApproval;

    public ApiResult<?> dispatch(AuditReplayCommand cmd, AuditReplayContext ctx) {
        if (WithdrawalConfigApproval.supports(cmd)) {
            return withdrawalConfigApproval.replay(cmd, ctx);
        }
        AuditReplayable target = replayables.stream()
                .filter(candidate -> candidate.domain().equals(cmd.domain()))
                .findFirst()
                .orElse(null);
        if (target == null) {
            return ApiResult.fail(422, "UNKNOWN_REPLAY_DOMAIN:" + cmd.domain());
        }
        return target.replay(cmd, ctx);
    }
}
