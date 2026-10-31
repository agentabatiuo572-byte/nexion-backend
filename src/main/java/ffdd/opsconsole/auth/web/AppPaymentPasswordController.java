package ffdd.opsconsole.auth.web;

import ffdd.opsconsole.auth.application.AppPaymentPasswordService;
import ffdd.opsconsole.auth.facade.ExternalPurchaseCommandQuery;
import ffdd.opsconsole.auth.application.AppPaymentPasswordService.Change;
import ffdd.opsconsole.auth.application.AppPaymentPasswordService.Reset;
import ffdd.opsconsole.auth.application.AppPaymentPasswordService.Setup;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.exception.BizException;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor(onConstructor_ = @org.springframework.beans.factory.annotation.Autowired)
public class AppPaymentPasswordController {
    private final AppPaymentPasswordService service;
    private final ObjectProvider<ExternalPurchaseCommandQuery> externalCommands;
    public AppPaymentPasswordController(AppPaymentPasswordService service) {this(service,null);}
    @GetMapping("/api/app/security/payment-password")
    public ApiResult<AppPaymentPasswordService.State> state(Authentication auth) {
        return ApiResult.ok(service.state(user(auth).id()));
    }
    @PostMapping("/api/app/security/payment-password/setup")
    public ApiResult<Map<String,Object>> setup(Authentication auth,@RequestHeader("Idempotency-Key") String key,
                                              @RequestBody(required=false) Setup request) {
        return service.setup(user(auth).id(),key,request);
    }
    @PostMapping("/api/app/security/payment-password/change")
    public ApiResult<Map<String,Object>> change(Authentication auth,@RequestHeader("Idempotency-Key") String key,
                                               @RequestBody(required=false) Change request) {
        return service.change(user(auth).id(),key,request);
    }
    @GetMapping("/api/app/security/payment-password/commands/{commandKey}")
    public ApiResult<Map<String,Object>> command(Authentication auth,@PathVariable String commandKey) {
        return ApiResult.ok(service.securityCommand(user(auth).id(),commandKey));
    }
    @GetMapping("/api/app/payment-commands/{kind}/{commandKey}")
    public ApiResult<Map<String,Object>> purchase(Authentication auth,@PathVariable String kind,@PathVariable String commandKey) {
        long user=user(auth).id();
        ExternalPurchaseCommandQuery external=externalCommands==null?null:externalCommands.getIfAvailable();
        Map<String,Object> receipt=external==null?null:external.find(user,kind,commandKey);
        return ApiResult.ok(receipt==null?service.query(user,kind,commandKey):receipt);
    }
    @PostMapping("/api/app/security/payment-password/reset/challenge")
    public ApiResult<Map<String,Object>> challenge(Authentication auth) {
        User user=user(auth);
        return ApiResult.ok(service.resetChallenge(user.id(),user.session()));
    }
    @PostMapping("/api/app/security/payment-password/reset/verify")
    public ApiResult<Map<String,Object>> verify(Authentication auth,@RequestBody Verify request) {
        User user=user(auth);
        return service.resetVerify(user.id(),user.session(),request.challengeNo(),request.code());
    }
    @PostMapping("/api/app/security/payment-password/reset")
    public ApiResult<Map<String,Object>> reset(Authentication auth,@RequestHeader("Idempotency-Key") String key,
                                              @RequestBody(required=false) Reset request) {
        User user=user(auth);
        return service.reset(user.id(),user.session(),key,request);
    }
    public record Verify(String challengeNo,String code) {
        @Override public String toString() { return "Verify[REDACTED]"; }
    }
    private record User(long id,String session) {}
    private User user(Authentication auth) {
        if (auth==null || !auth.isAuthenticated() || !(auth.getDetails() instanceof Map<?,?> details)
                || !"USER".equals(details.get("subjectType")) || !(details.get("sessionId") instanceof String session)
                || session.isBlank()) throw new BizException(401,"USER_AUTH_REQUIRED");
        try {
            long id=Long.parseLong(auth.getName());
            if (id<=0) throw new NumberFormatException();
            return new User(id,session);
        } catch (NumberFormatException invalid) { throw new BizException(401,"USER_AUTH_REQUIRED"); }
    }
}
