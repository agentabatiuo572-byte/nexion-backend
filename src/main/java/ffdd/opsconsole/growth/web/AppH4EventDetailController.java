package ffdd.opsconsole.growth.web;

import ffdd.opsconsole.growth.application.H4EventEngine;
import ffdd.opsconsole.shared.api.ApiResult;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
public class AppH4EventDetailController {
    private final H4EventEngine events;
    @GetMapping("/api/events/{eventCode}/detail")
    public ApiResult<Map<String,Object>> detail(@PathVariable String eventCode,@RequestParam(required=false) String locale,Authentication auth){
        if(auth==null||!auth.isAuthenticated()||!(auth.getDetails() instanceof Map<?,?> details)||!"USER".equals(details.get("subjectType")))return ApiResult.fail(403,"USER_SUBJECT_REQUIRED");
        try{return events.detail(Long.valueOf(String.valueOf(auth.getPrincipal())),eventCode,locale);}
        catch(NumberFormatException ex){return ApiResult.fail(403,"USER_SUBJECT_REQUIRED");}
    }
    @PostMapping("/api/events/{eventCode}/reminder")
    public ApiResult<Map<String,Object>> reminder(@PathVariable String eventCode,Authentication auth){
        if(auth==null||!auth.isAuthenticated()||!(auth.getDetails() instanceof Map<?,?> details)||!"USER".equals(details.get("subjectType")))return ApiResult.fail(403,"USER_SUBJECT_REQUIRED");
        try{return events.remind(Long.valueOf(String.valueOf(auth.getPrincipal())),eventCode);}
        catch(NumberFormatException ex){return ApiResult.fail(403,"USER_SUBJECT_REQUIRED");}
    }
}
