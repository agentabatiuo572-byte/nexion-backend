package ffdd.opsconsole.content.web;

import ffdd.opsconsole.content.application.SupportAcceptanceSandboxProfileGuard;
import ffdd.opsconsole.content.application.SupportAcceptanceSandboxService;
import ffdd.opsconsole.shared.api.ApiResult;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Acceptance proof endpoint.  The subsequent sandbox projection/command routes
 * are intentionally separate from production AppSupportController routes.
 */
@RestController
@Profile({"dev", "test"})
@RequestMapping("/api/app/support/acceptance")
@RequiredArgsConstructor
public class SupportAcceptanceSandboxController {
    private final SupportAcceptanceSandboxProfileGuard guard;
    private final SupportAcceptanceSandboxService service;

    @GetMapping("/projection")
    public ApiResult<Map<String, Object>> projection(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) return ApiResult.fail(403, "AUTHENTICATION_REQUIRED");
        return ApiResult.ok(service.proof(user(authentication)));
    }
    @GetMapping("/tickets") public ApiResult<Map<String,Object>> tickets(Authentication a){return ApiResult.ok(service.tickets(user(a)));}
    @GetMapping("/tickets/{id}") public ApiResult<Map<String,Object>> ticket(@PathVariable String id,Authentication a){return ApiResult.ok(service.ticket(user(a),id));}
    @PostMapping("/tickets") public ApiResult<Object> createTicket(@RequestHeader("Idempotency-Key") String key,@RequestBody Map<String,Object> b,Authentication a){return ApiResult.ok(service.createTicket(user(a),key,b).get("result"));}
    @PostMapping("/tickets/{id}/replies") public ApiResult<Object> replyTicket(@PathVariable String id,@RequestHeader("Idempotency-Key") String key,@RequestBody Map<String,Object> b,Authentication a){return ApiResult.ok(service.replyTicket(user(a),id,key,b).get("result"));}
    @PostMapping("/tickets/{id}/close") public ApiResult<Object> closeTicket(@PathVariable String id,@RequestHeader("Idempotency-Key") String key,@RequestBody Map<String,Object> b,Authentication a){return ApiResult.ok(service.closeTicket(user(a),id,key,b).get("result"));}
    @GetMapping("/conversations") public ApiResult<Map<String,Object>> conversations(Authentication a){return ApiResult.ok(service.conversations(user(a)));}
    @GetMapping("/conversations/{id}") public ApiResult<Map<String,Object>> conversation(@PathVariable String id,Authentication a){return ApiResult.ok(service.conversation(user(a),id));}
    @PostMapping("/conversations") public ApiResult<Object> start(@RequestHeader("Idempotency-Key") String key,@RequestBody Map<String,Object> b,Authentication a){return ApiResult.ok(service.startConversation(user(a),key,b).get("result"));}
    @PostMapping("/conversations/{id}/replies") public ApiResult<Object> reply(@PathVariable String id,@RequestHeader("Idempotency-Key") String key,@RequestBody Map<String,Object> b,Authentication a){return ApiResult.ok(service.replyConversation(user(a),id,key,b).get("result"));}
    @PostMapping("/conversations/{id}/read") public ApiResult<Map<String,Object>> read(@PathVariable String id,@RequestBody Map<String,Object> b,Authentication a){return ApiResult.ok(service.read(user(a),id,b));}
    @PostMapping("/conversations/{id}/ticket") public ApiResult<Object> convert(@PathVariable String id,@RequestHeader("Idempotency-Key") String key,@RequestBody Map<String,Object> b,Authentication a){return ApiResult.ok(service.toTicket(user(a),id,key,b).get("result"));}
    @GetMapping("/commands/{key}") public ApiResult<Map<String,Object>> command(@PathVariable String key,Authentication a){Map<String,Object> r=service.commandResult(user(a),key);return r==null?ApiResult.fail(404,"SUPPORT_ACCEPTANCE_COMMAND_NOT_FOUND"):ApiResult.ok(r);}
    private Long user(Authentication a){if(a==null||!a.isAuthenticated()||!(a.getDetails() instanceof Map<?,?> details)||!"USER".equals(String.valueOf(details.get("subjectType"))))throw new ffdd.opsconsole.shared.exception.BizException(403,"AUTHENTICATION_REQUIRED");try{return Long.valueOf(String.valueOf(a.getPrincipal()));}catch(NumberFormatException e){throw new ffdd.opsconsole.shared.exception.BizException(403,"USER_SUBJECT_REQUIRED");}}
}
