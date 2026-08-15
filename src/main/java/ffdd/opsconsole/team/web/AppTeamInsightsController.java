package ffdd.opsconsole.team.web;

import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.team.application.AppTeamInsightsService;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/app/team/insights")
@RequiredArgsConstructor
public class AppTeamInsightsController {
    private final AppTeamInsightsService service;
    @GetMapping("/leaderboard") public ApiResult<Map<String,Object>> leaderboard(@RequestParam(defaultValue="week") String period, Authentication auth){Long id=userId(auth);return id==null?ApiResult.fail(403,"USER_AUTH_REQUIRED"):service.leaderboard(id,period);}
    @GetMapping("/commissions") public ApiResult<Map<String,Object>> commissions(Authentication auth){Long id=userId(auth);return id==null?ApiResult.fail(403,"USER_AUTH_REQUIRED"):service.commissions(id);}
    @GetMapping("/unilevel") public ApiResult<Map<String,Object>> unilevel(@RequestParam(defaultValue="week") String period, Authentication auth){Long id=userId(auth);return id==null?ApiResult.fail(403,"USER_AUTH_REQUIRED"):service.unilevel(id,period);}
    @GetMapping("/leadership-pool") public ApiResult<Map<String,Object>> pool(Authentication auth){Long id=userId(auth);return id==null?ApiResult.fail(403,"USER_AUTH_REQUIRED"):service.leadershipPool(id);}
    private Long userId(Authentication authentication){if(authentication==null||!authentication.isAuthenticated()||authentication.getPrincipal()==null||!(authentication.getDetails() instanceof Map<?,?> details)||!"USER".equals(String.valueOf(details.get("subjectType"))))return null;try{long id=Long.parseLong(String.valueOf(authentication.getPrincipal()));return id>0?id:null;}catch(NumberFormatException ignored){return null;}}
}
