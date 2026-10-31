package ffdd.opsconsole.finance.web;

import ffdd.opsconsole.finance.purchase.*;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.exception.BizException;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/app") @RequiredArgsConstructor
public class AppPurchasePaymentController {
    private final PurchasePaymentService service;
    private final PurchasePaymentPolicyService policy;
    @GetMapping("/purchase-payment-policy")
    public ApiResult<Map<String,Object>> policy(Authentication auth){owner(auth);return ApiResult.ok(policy.current());}
    @PostMapping("/purchase-payment-quotes")
    public ApiResult<Map<String,Object>> quote(Authentication auth,@RequestBody Map<String,Object> body){return ApiResult.ok(service.quote(owner(auth),body));}
    @PostMapping("/purchase-payment-intents")
    public ApiResult<Map<String,Object>> create(Authentication auth,@RequestHeader("Idempotency-Key") String key,@RequestBody Map<String,Object> body){
        return ApiResult.ok(service.create(owner(auth),key,String.valueOf(body.getOrDefault("paymentQuoteId",""))));
    }
    @GetMapping("/purchase-payment-intents/{intentNo}")
    public ApiResult<Map<String,Object>> get(Authentication auth,@PathVariable String intentNo){return ApiResult.ok(service.get(owner(auth),intentNo));}
    @GetMapping("/purchase-payment-intents")
    public ApiResult<Map<String,Object>> lookup(Authentication auth,@RequestParam String kind,@RequestParam String commandKey){return ApiResult.ok(service.lookup(owner(auth),kind,commandKey));}
    private long owner(Authentication auth){
        if(auth==null||!auth.isAuthenticated()||!(auth.getDetails() instanceof Map<?,?> details)||!"USER".equals(details.get("subjectType")))
            throw new BizException(403,"USER_SUBJECT_REQUIRED");
        try{long owner=Long.parseLong(String.valueOf(auth.getPrincipal()));if(owner>0)return owner;}catch(NumberFormatException ignored){}
        throw new BizException(403,"USER_SUBJECT_REQUIRED");
    }
}
