package ffdd.opsconsole.finance.web;

import ffdd.opsconsole.finance.application.EarningsReleaseService;
import ffdd.opsconsole.shared.api.ApiResult;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class EarningsReleaseController {
    private final EarningsReleaseService service;
    @GetMapping("/api/earnings/release-status") public ApiResult<Map<String,Object>> status(Authentication authentication){Long id=userId(authentication);return id==null?ApiResult.fail(403,"USER_SUBJECT_REQUIRED"):service.status(id);}
    private Long userId(Authentication a){if(a==null||!a.isAuthenticated()||a.getPrincipal()==null||!(a.getDetails() instanceof Map<?,?> d)||!"USER".equals(String.valueOf(d.get("subjectType"))))return null;try{long id=Long.parseLong(String.valueOf(a.getPrincipal()));return id>0?id:null;}catch(Exception e){return null;}}
}
