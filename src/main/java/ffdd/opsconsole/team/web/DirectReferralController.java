package ffdd.opsconsole.team.web;

import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.team.application.DirectReferralPolicyService;
import ffdd.opsconsole.team.application.DirectReferralService;
import ffdd.opsconsole.team.dto.DirectReferralPolicyRequest;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController @RequiredArgsConstructor
public class DirectReferralController {
    private final DirectReferralPolicyService policies;
    private final DirectReferralService service;
    @GetMapping("/api/config/commission/direct-referral")
    public ApiResult<Map<String,Object>> policy(){return ApiResult.ok(policies.current());}
    @GetMapping("/api/admin/teams/direct-referral-policy")
    @PreAuthorize("hasAuthority('network_f2_read')")
    public ApiResult<Map<String,Object>> adminPolicy(){return policy();}
    @PutMapping("/api/admin/teams/direct-referral-policy")
    @PreAuthorize("hasAuthority('network_f2_royalty_rate')")
    public ApiResult<Map<String,Object>> update(@RequestHeader(value="Idempotency-Key",required=false)String key,@RequestBody DirectReferralPolicyRequest request){return ApiResult.ok(policies.publish(key,request));}
    @GetMapping("/api/app/team/insights/direct-referral")
    public ApiResult<Map<String,Object>> insights(Authentication auth,@RequestParam(defaultValue="month")String period,
            @RequestParam(defaultValue="1")long page,@RequestParam(defaultValue="20")long pageSize,@RequestParam(required=false)String snapshotAt){
        if(auth==null||!auth.isAuthenticated()||!(auth.getDetails() instanceof Map<?,?> detail)||!"USER".equals(detail.get("subjectType")))return ApiResult.fail(403,"USER_AUTH_REQUIRED");
        Long id;try{id=Long.valueOf(String.valueOf(auth.getPrincipal()));}catch(NumberFormatException e){return ApiResult.fail(403,"USER_AUTH_REQUIRED");}
        return ApiResult.ok(service.insights(id,period,page,pageSize,snapshotAt));
    }
}
