package ffdd.opsconsole.auth.web;

import ffdd.opsconsole.auth.application.OpsPaymentSecurityService;
import ffdd.opsconsole.shared.api.ApiResult;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/content/support-workbench/customers/{userId}/payment-password")
@PreAuthorize("hasAnyAuthority('service_m1_read','service_m3_read')")
@RequiredArgsConstructor
public class SupportPaymentSecurityController {
    private final OpsPaymentSecurityService service;
    @GetMapping("/summary")
    public ApiResult<Map<String,Object>> summary(@PathVariable long userId) {return ApiResult.ok(service.summary(userId,true));}
    @GetMapping("/records")
    public ApiResult<Map<String,Object>> list(@PathVariable long userId,@RequestParam(defaultValue="") String keyword,@RequestParam(defaultValue="") String state,
            @RequestParam(defaultValue="") String from,@RequestParam(defaultValue="") String to,@RequestParam(defaultValue="1") int pageNum,@RequestParam(defaultValue="10") int pageSize) {
        return ApiResult.ok(service.list(userId,true,keyword,state,from,to,pageNum,pageSize));
    }
    @GetMapping("/records/{recordId}")
    public ApiResult<Map<String,Object>> detail(@PathVariable long userId,@PathVariable String recordId) {return ApiResult.ok(service.detail(recordId,userId,true));}
    @GetMapping("/records/{recordId}/attempts")
    public ApiResult<Map<String,Object>> attempts(@PathVariable long userId,@PathVariable String recordId,@RequestParam(defaultValue="1") int pageNum,@RequestParam(defaultValue="10") int pageSize) {return ApiResult.ok(service.recordAttempts(recordId,userId,true,pageNum,pageSize));}
    @PostMapping("/records/{recordId}/release")
    @PreAuthorize("hasAuthority('user_c5_payment_password_release') and hasAnyAuthority('service_m1_read','service_m3_read')")
    public ApiResult<Map<String,Object>> release(@PathVariable long userId,@PathVariable String recordId,@RequestHeader("Idempotency-Key") String key,@RequestBody OpsPaymentSecurityService.ReleaseRequest request) {return ApiResult.ok(service.release(recordId,userId,true,key,request));}
    @GetMapping("/records/commands/{key}")
    @PreAuthorize("hasAuthority('user_c5_payment_password_release') and hasAnyAuthority('service_m1_read','service_m3_read')")
    public ApiResult<Map<String,Object>> receipt(@PathVariable long userId,@PathVariable String key) {return ApiResult.ok(service.releaseReceipt(userId,true,key));}
}
