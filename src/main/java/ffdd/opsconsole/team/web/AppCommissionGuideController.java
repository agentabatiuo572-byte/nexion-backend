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
@RequiredArgsConstructor(onConstructor_=@org.springframework.beans.factory.annotation.Autowired)
public class AppCommissionGuideController {
    private final CommissionGuideRuleService guideRuleService;
    private final Environment environment;
    private final ffdd.opsconsole.team.application.DirectReferralPolicyService directPolicies;
    public AppCommissionGuideController(CommissionGuideRuleService guideRuleService,Environment environment){this(guideRuleService,environment,null);}

    @GetMapping("/api/config/commission/guide")
    public ApiResult<Map<String, Object>> guide(@org.springframework.web.bind.annotation.RequestParam(required=false)Integer schemaVersion) {
        if(directPolicies!=null)directPolicies.requireSchema(schemaVersion);
        try {
            var result=guideRuleService.guide(environment);if(Integer.valueOf(2).equals(schemaVersion))result.put("schemaVersion",2);return ApiResult.ok(result);
        } catch (RuntimeException unavailable) {
            return ApiResult.fail(503, "COMMISSION_GUIDE_CONFIG_UNAVAILABLE");
        }
    }
    public ApiResult<Map<String,Object>> guide(){return guide(null);}
}
