package ffdd.opsconsole.developer.web;

import ffdd.opsconsole.developer.application.AppDeveloperAccessService;
import ffdd.opsconsole.shared.api.ApiResult;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/app/developer/access-requests") @RequiredArgsConstructor
public class AppDeveloperAccessController {
    private final AppDeveloperAccessService service;
    @PostMapping public ApiResult<Map<String,Object>> submit(@RequestBody Request request,@RequestHeader("Idempotency-Key") String key,Authentication auth){Long id=userId(auth);return id==null?ApiResult.fail(403,"USER_AUTH_REQUIRED"):service.submit(id,request==null?null:request.company(),request==null?null:request.email(),request==null?null:request.useCase(),key);}
    @GetMapping("/latest") public ApiResult<Map<String,Object>> latest(Authentication auth){Long id=userId(auth);return id==null?ApiResult.fail(403,"USER_AUTH_REQUIRED"):service.latest(id);}
    private Long userId(Authentication auth){if(auth==null||!auth.isAuthenticated()||auth.getPrincipal()==null||!(auth.getDetails() instanceof Map<?,?> d)||!"USER".equals(String.valueOf(d.get("subjectType"))))return null;try{long v=Long.parseLong(String.valueOf(auth.getPrincipal()));return v>0?v:null;}catch(NumberFormatException e){return null;}}
    public record Request(String company,String email,String useCase){}
}
