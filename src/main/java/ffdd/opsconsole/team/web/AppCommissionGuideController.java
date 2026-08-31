package ffdd.opsconsole.team.web;

import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.team.application.CommissionGuideRuleService;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.core.env.Environment;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Public read-only commission-guide configuration. No account or settlement state is exposed. */
@RestController
@RequiredArgsConstructor
public class AppCommissionGuideController {
    private final CommissionGuideRuleService guideRuleService;
    private final Environment environment;

    @GetMapping("/api/config/commission/guide")
    public ApiResult<Map<String, Object>> guide() {
        try {
            return ApiResult.ok(guideRuleService.guide(environment));
        } catch (RuntimeException unavailable) {
            return ApiResult.fail(503, "COMMISSION_GUIDE_CONFIG_UNAVAILABLE");
        }
    }
}
