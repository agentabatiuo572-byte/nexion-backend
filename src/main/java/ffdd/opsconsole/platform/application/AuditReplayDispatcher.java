package ffdd.opsconsole.platform.application;
import ffdd.opsconsole.device.application.E2TaskPricingApproval;
import ffdd.opsconsole.growth.application.H8RewardParamApproval;
import ffdd.opsconsole.finance.application.WithdrawalConfigApproval;
import ffdd.opsconsole.platform.domain.AuditReplayCommand;
import ffdd.opsconsole.platform.domain.AuditReplayContext;
import ffdd.opsconsole.platform.domain.AuditReplayable;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.team.application.TeamFundApproval;
import ffdd.opsconsole.treasury.application.TreasuryThresholdApproval;
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
    @Autowired
    private TreasuryThresholdApproval treasuryThresholdApproval;
    @Autowired
    private E2TaskPricingApproval e2TaskPricingApproval;
    @Autowired
    private TeamFundApproval teamFundApproval;
    @Autowired
    private H8RewardParamApproval h8RewardParamApproval;

    public ApiResult<?> dispatch(AuditReplayCommand cmd, AuditReplayContext ctx) {
        if (H8RewardParamApproval.supports(cmd)) {
            return h8RewardParamApproval.replay(cmd, ctx,
                    A2ReplayContext.authenticatedAdminId(), A2ReplayContext.operationId());
        }
        if (TeamFundApproval.supports(cmd)) {
            return teamFundApproval.replay(cmd, ctx, A2ReplayContext.authenticatedAdminId(), A2ReplayContext.operationId());
        }
        if (E2TaskPricingApproval.supports(cmd)) {
            return e2TaskPricingApproval.replay(cmd, ctx);
        }
        if (TreasuryThresholdApproval.supports(cmd)) {
            return treasuryThresholdApproval.replay(cmd, ctx);
        }
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
