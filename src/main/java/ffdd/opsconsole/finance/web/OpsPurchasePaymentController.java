package ffdd.opsconsole.finance.web;

import ffdd.opsconsole.finance.purchase.*;
import ffdd.opsconsole.shared.api.ApiResult;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/admin/finance") @RequiredArgsConstructor
public class OpsPurchasePaymentController {
    private final PurchasePaymentService service;
    private final PurchasePaymentPolicyService policy;
    @GetMapping("/purchase-payment-policy") @PreAuthorize("hasAuthority('finance_d1_read')")
    public ApiResult<Map<String,Object>> policy(){return ApiResult.ok(policy.current());}
    @PatchMapping("/purchase-payment-policy") @PreAuthorize("hasAuthority('finance_d1_fx_manage')")
    public ApiResult<Map<String,Object>> update(@RequestHeader("Idempotency-Key") String key,@RequestBody Map<String,Object> body){return ApiResult.ok(policy.update(key,body));}
    @GetMapping("/purchase-payment-records") @PreAuthorize("hasAuthority('finance_d1_read')")
    public ApiResult<Map<String,Object>> records(@RequestParam(required=false) String status,@RequestParam(required=false) String paymentMethod,
            @RequestParam(defaultValue="1") int pageNum,@RequestParam(defaultValue="20") int pageSize){return ApiResult.ok(service.records(status,paymentMethod,pageNum,pageSize));}
    @GetMapping("/purchase-payment-records/{intentNo}") @PreAuthorize("hasAuthority('finance_d1_read')")
    public ApiResult<Map<String,Object>> detail(@PathVariable String intentNo){return ApiResult.ok(service.detail(intentNo));}
    @PostMapping("/purchase-payment-records/{intentNo}/revalidate-deliver") @PreAuthorize("hasAuthority('finance_d1_bank_reconcile')")
    public ApiResult<Map<String,Object>> revalidate(@PathVariable String intentNo,@RequestHeader("Idempotency-Key") String key,@RequestBody Map<String,Object> body){
        return ApiResult.ok(service.revalidate(intentNo,key,body));
    }
}
