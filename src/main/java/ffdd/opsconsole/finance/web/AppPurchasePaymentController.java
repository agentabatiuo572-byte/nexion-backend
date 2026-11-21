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
    /** B58-BE-01: 凭原付款单号的本人只读快照 —— 不 refresh、不查供应商、不建单、不扣款。 */
    // 独立静态前缀 snapshots，避免与 /by-order/{orderNo} 形成「两段、字面量位置不同」的等权匹配。
    @GetMapping("/purchase-payment-intents/snapshots/{intentNo}")
    public ApiResult<Map<String,Object>> snapshot(Authentication auth,@PathVariable String intentNo){return ApiResult.ok(service.snapshot(owner(auth),intentNo));}
    @GetMapping("/purchase-payment-intents")
    public ApiResult<Map<String,Object>> lookup(Authentication auth,@RequestParam String kind,@RequestParam String commandKey){return ApiResult.ok(service.lookup(owner(auth),kind,commandKey));}
    /** 按本人订单号只读找回进行中的付款单:不要求本机原命令键,他人订单与不存在的订单同为 404。 */
    @GetMapping("/purchase-payment-intents/by-order/{orderNo}")
    public ApiResult<Map<String,Object>> activeByOrder(Authentication auth,@PathVariable String orderNo){return ApiResult.ok(service.activeByOrder(owner(auth),orderNo));}
    private long owner(Authentication auth){
        if(auth==null||!auth.isAuthenticated()||!(auth.getDetails() instanceof Map<?,?> details)||!"USER".equals(details.get("subjectType")))
            throw new BizException(403,"USER_SUBJECT_REQUIRED");
        try{long owner=Long.parseLong(String.valueOf(auth.getPrincipal()));if(owner>0)return owner;}catch(NumberFormatException ignored){}
        throw new BizException(403,"USER_SUBJECT_REQUIRED");
    }
}
