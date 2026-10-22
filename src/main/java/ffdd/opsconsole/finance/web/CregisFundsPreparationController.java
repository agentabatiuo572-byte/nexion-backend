package ffdd.opsconsole.finance.web;

import ffdd.opsconsole.finance.cregis.CregisFundsPreparationService;
import ffdd.opsconsole.shared.api.ApiResult;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/admin/finance/cregis/funds-preparation")
public class CregisFundsPreparationController {
    private static final String READ = "hasAuthority('finance_d1_read')";
    private static final String WRITE = READ + " and hasAuthority('finance_d1_channel_manage') and hasAuthority('finance_d2_withdrawal_approve')";
    private final CregisFundsPreparationService service;
    @GetMapping("/status") @PreAuthorize(READ)
    public ApiResult<Map<String,Object>> status() { return ApiResult.ok(service.status()); }
    @GetMapping("/plans") @PreAuthorize(READ)
    public ApiResult<Map<String,Object>> plans(@RequestParam(defaultValue="0") long beforeId,@RequestParam(defaultValue="20") int limit) {
        return ApiResult.ok(service.plans(beforeId,limit));
    }
    @PostMapping("/preflight") @PreAuthorize(WRITE)
    public ApiResult<Map<String,Object>> preflight(@RequestBody Preparation request) {
        return ApiResult.ok(service.preflight(request.kind(),request.sourceAddress(),request.amountUsdt()));
    }
    @PostMapping("/plans") @PreAuthorize(WRITE)
    public ApiResult<Map<String,Object>> create(@RequestHeader("Idempotency-Key") String key,@RequestBody Preparation request) {
        return ApiResult.ok(service.create(key,request.kind(),request.sourceAddress(),request.amountUsdt(),request.businessReference(),request.reason()));
    }
    @PostMapping("/plans/{no}/decision") @PreAuthorize(WRITE)
    public ApiResult<Map<String,Object>> decide(@PathVariable String no,@RequestHeader("Idempotency-Key") String key,@RequestBody Decision request) {
        return ApiResult.ok(service.decide(key,no,request.expectedVersion(),request.decision(),request.reason()));
    }
    public record Preparation(String kind,String sourceAddress,String amountUsdt,String businessReference,String reason) { }
    public record Decision(long expectedVersion,String decision,String reason) { }
}
