package ffdd.opsconsole.platform.application;
import ffdd.opsconsole.content.application.LearningCourseActivationApproval;
import ffdd.opsconsole.device.application.E2TaskPricingApproval;
import ffdd.opsconsole.growth.application.H8RewardParamApproval;
import ffdd.opsconsole.growth.application.H5GrowthApproval;
import ffdd.opsconsole.growth.application.H3GrowthApproval;
import ffdd.opsconsole.growth.application.H4GrowthApproval;
import ffdd.opsconsole.risk.application.K2OtpInitialApproval;
import ffdd.opsconsole.finance.application.WithdrawalConfigApproval;
import ffdd.opsconsole.market.application.G3MarketApproval;
import ffdd.opsconsole.market.application.G4CatalogApproval;
import ffdd.opsconsole.market.application.G4DividendApproval;
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
    private G3MarketApproval g3MarketApproval;
    @Autowired
    private G4CatalogApproval g4CatalogApproval;
    @Autowired
    private G4DividendApproval g4DividendApproval;
    @Autowired
    private H8RewardParamApproval h8RewardParamApproval;
    @Autowired
    private H5GrowthApproval h5GrowthApproval;
    @Autowired
    private H3GrowthApproval h3GrowthApproval;
    @Autowired
    private H4GrowthApproval h4GrowthApproval;
    @Autowired
    private K2OtpInitialApproval k2OtpInitialApproval;
    @Autowired
    private LearningCourseActivationApproval learningCourseActivationApproval;

    public ApiResult<?> dispatch(AuditReplayCommand cmd, AuditReplayContext ctx) {
        if (G4CatalogApproval.supports(cmd)) {
            return g4CatalogApproval.replay(cmd, ctx);
        }
        if (LearningCourseActivationApproval.supports(cmd)) {
            return learningCourseActivationApproval.replay(cmd, ctx);
        }
        if (H4GrowthApproval.supports(cmd)) {
            return h4GrowthApproval.replay(cmd, ctx,
                    A2ReplayContext.authenticatedAdminId(), A2ReplayContext.operationId());
        }
        if (G4DividendApproval.supports(cmd)) {
            return g4DividendApproval.replay(cmd, ctx,
                    A2ReplayContext.authenticatedAdminId(), A2ReplayContext.operationId());
        }
        if (H3GrowthApproval.supports(cmd)) {
            return h3GrowthApproval.replay(cmd, ctx,
                    A2ReplayContext.authenticatedAdminId(), A2ReplayContext.operationId());
        }
        if (K2OtpInitialApproval.supports(cmd)) {
            return k2OtpInitialApproval.replay(cmd, ctx,
                    A2ReplayContext.authenticatedAdminId(), A2ReplayContext.operationId());
        }
        if (G3MarketApproval.supports(cmd)) {
            return g3MarketApproval.replay(cmd, ctx, A2ReplayContext.authenticatedAdminId(), A2ReplayContext.operationId());
        }
        if (H5GrowthApproval.supports(cmd)) {
            return h5GrowthApproval.replay(cmd, ctx,
                    A2ReplayContext.authenticatedAdminId(), A2ReplayContext.operationId());
        }
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
