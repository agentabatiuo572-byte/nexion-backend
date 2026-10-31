package ffdd.opsconsole.auth.web;

import ffdd.opsconsole.auth.application.OpsPaymentSecurityService;
import ffdd.opsconsole.shared.api.ApiResult;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/users")
@RequiredArgsConstructor
public class OpsPaymentSecurityController {
    private final OpsPaymentSecurityService service;
    @GetMapping("/security/payment-password-policy")
    @PreAuthorize("hasAuthority('user_c5_read')")
    public ApiResult<Map<String,Object>> policy() {return ApiResult.ok(service.policy());}
    @PostMapping("/security/payment-password-policy")
    @PreAuthorize("hasAuthority('user_c5_payment_password_policy_write')")
    public ApiResult<Map<String,Object>> policy(@RequestHeader("Idempotency-Key") String key,@RequestBody OpsPaymentSecurityService.PolicyRequest request) {return ApiResult.ok(service.changePolicy(key,request));}
    @GetMapping("/security/payment-password-policy/commands/{key}")
    @PreAuthorize("hasAuthority('user_c5_read')")
    public ApiResult<Map<String,Object>> policyReceipt(@PathVariable String key) {return ApiResult.ok(service.policyReceipt(key));}
    @GetMapping("/profiles/{userId}/security/payment-password")
    @PreAuthorize("hasAnyAuthority('user_c1_read','user_c5_read')")
    public ApiResult<Map<String,Object>> status(@PathVariable long userId) {return ApiResult.ok(service.status(userId,false));}
    @GetMapping("/profiles/{userId}/security/payment-password/summary")
    @PreAuthorize("hasAnyAuthority('user_c1_read','user_c5_read')")
    public ApiResult<Map<String,Object>> summary(@PathVariable long userId) {return ApiResult.ok(service.summary(userId,false));}
    @GetMapping("/profiles/{userId}/security/payment-password/attempts")
    @PreAuthorize("hasAnyAuthority('user_c1_read','user_c5_read')")
    public ApiResult<Map<String,Object>> attempts(@PathVariable long userId,@RequestParam(defaultValue="1") int pageNum,@RequestParam(defaultValue="10") int pageSize) {return ApiResult.ok(service.attempts(userId,false,pageNum,pageSize));}
    @GetMapping("/security/payment-password-records")
    @PreAuthorize("hasAuthority('user_c5_read')")
    public ApiResult<Map<String,Object>> list(@RequestParam(defaultValue="") String keyword,@RequestParam(defaultValue="") String state,
            @RequestParam(defaultValue="") String from,@RequestParam(defaultValue="") String to,@RequestParam(defaultValue="1") int pageNum,@RequestParam(defaultValue="10") int pageSize) {
        return ApiResult.ok(service.list(null,false,keyword,state,from,to,pageNum,pageSize));
    }
    @GetMapping("/security/payment-password-records/{recordId}")
    @PreAuthorize("hasAnyAuthority('user_c1_read','user_c5_read')")
    public ApiResult<Map<String,Object>> detail(@PathVariable String recordId) {return ApiResult.ok(service.detail(recordId,null,false));}
    @GetMapping("/security/payment-password-records/{recordId}/attempts")
    @PreAuthorize("hasAnyAuthority('user_c1_read','user_c5_read')")
    public ApiResult<Map<String,Object>> recordAttempts(@PathVariable String recordId,@RequestParam(defaultValue="1") int pageNum,@RequestParam(defaultValue="10") int pageSize) {return ApiResult.ok(service.recordAttempts(recordId,null,false,pageNum,pageSize));}
    @PostMapping("/security/payment-password-records/{recordId}/release")
    @PreAuthorize("hasAuthority('user_c5_payment_password_release')")
    public ApiResult<Map<String,Object>> release(@PathVariable String recordId,@RequestHeader("Idempotency-Key") String key,@RequestBody OpsPaymentSecurityService.ReleaseRequest request) {return ApiResult.ok(service.release(recordId,null,false,key,request));}
    @GetMapping("/security/payment-password-records/commands/{key}")
    @PreAuthorize("hasAuthority('user_c5_payment_password_release')")
    public ApiResult<Map<String,Object>> receipt(@PathVariable String key) {return ApiResult.ok(service.releaseReceipt(null,false,key));}
}
