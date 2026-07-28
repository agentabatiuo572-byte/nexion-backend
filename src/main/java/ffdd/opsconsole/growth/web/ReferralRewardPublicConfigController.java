package ffdd.opsconsole.growth.web;

import ffdd.opsconsole.growth.application.OpsReferralRewardService;
import ffdd.opsconsole.growth.domain.ReferralRewardPublicConfigView;
import ffdd.opsconsole.shared.api.ApiResult;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** H8 给 App/H5 的只读配置入口；真实结算仍只允许 A2 批准后回放。 */
@RestController
@RequiredArgsConstructor
public class ReferralRewardPublicConfigController {
    private final OpsReferralRewardService service;

    @GetMapping("/api/config/referral-rewards")
    public ApiResult<ReferralRewardPublicConfigView> referralRewards() {
        return ApiResult.ok(service.publicConfig());
    }
}
