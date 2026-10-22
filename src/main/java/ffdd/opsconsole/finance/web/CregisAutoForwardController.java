package ffdd.opsconsole.finance.web;

import ffdd.opsconsole.finance.cregis.CregisAutoForwardService;
import ffdd.opsconsole.shared.api.ApiResult;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController @RequiredArgsConstructor
@RequestMapping("/api/admin/finance/cregis/auto-forward")
public class CregisAutoForwardController {
    private static final String READ="hasAuthority('finance_d1_read')";
    private static final String WRITE=READ+" and hasAuthority('finance_d1_channel_manage') and hasAuthority('finance_d2_withdrawal_approve')";
    private final CregisAutoForwardService service;
    @GetMapping("/status") @PreAuthorize(READ)
    public ApiResult<Map<String,Object>> status(){return ApiResult.ok(service.status());}
    @GetMapping("/tasks") @PreAuthorize(READ)
    public ApiResult<Map<String,Object>> tasks(@RequestParam(defaultValue="0")long beforeId,@RequestParam(defaultValue="20")int limit){return ApiResult.ok(service.tasks(beforeId,limit));}
    @PostMapping("/policy") @PreAuthorize(WRITE)
    public ApiResult<Map<String,Object>> policy(@RequestHeader("Idempotency-Key")String key,@RequestBody Policy request){
        return ApiResult.ok(service.policy(key,request.action(),request.expectedVersion(),request.collectionEnabled(),request.paymentEnabled(),request.reason()));}
    @PostMapping("/tasks/{id}/recover") @PreAuthorize(WRITE)
    public ApiResult<Map<String,Object>> recover(@RequestHeader("Idempotency-Key")String key,@PathVariable long id,@RequestBody Recovery request){
        return ApiResult.ok(service.recover(key,id,request.expectedVersion(),request.stage(),request.cid(),request.retry(),request.reason()));}
    public record Policy(String action,long expectedVersion,Boolean collectionEnabled,Boolean paymentEnabled,String reason){}
    public record Recovery(long expectedVersion,String stage,String cid,boolean retry,String reason){}
}
