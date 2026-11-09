package ffdd.opsconsole.finance.web;

import ffdd.opsconsole.finance.cregis.CregisAutoForwardService;
import ffdd.opsconsole.finance.cregis.CregisNetwork;
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
    public ApiResult<Map<String,Object>> status(@RequestParam(defaultValue="BEP20")String network){return ApiResult.ok(service.status(CregisNetwork.fromNetwork(network)));}
    @GetMapping("/tasks") @PreAuthorize(READ)
    public ApiResult<Map<String,Object>> tasks(@RequestParam(defaultValue="BEP20")String network,@RequestParam(defaultValue="0")long beforeId,@RequestParam(defaultValue="20")int limit){return ApiResult.ok(service.tasks(CregisNetwork.fromNetwork(network),beforeId,limit));}
    @PostMapping("/policy") @PreAuthorize(WRITE)
    public ApiResult<Map<String,Object>> policy(@RequestParam(defaultValue="BEP20")String network,@RequestHeader("Idempotency-Key")String key,@RequestBody Policy request){
        return ApiResult.ok(service.policy(CregisNetwork.fromNetwork(network),key,request.action(),request.expectedVersion(),request.collectionEnabled(),request.paymentEnabled(),request.reason()));}
    @PostMapping("/tasks/{id}/recover") @PreAuthorize(WRITE)
    public ApiResult<Map<String,Object>> recover(@RequestParam(defaultValue="BEP20")String network,@RequestHeader("Idempotency-Key")String key,@PathVariable long id,@RequestBody Recovery request){
        return ApiResult.ok(service.recover(CregisNetwork.fromNetwork(network),key,id,request.expectedVersion(),request.stage(),request.cid(),request.retry(),request.reason()));}
    public record Policy(String action,long expectedVersion,Boolean collectionEnabled,Boolean paymentEnabled,String reason){}
    public record Recovery(long expectedVersion,String stage,String cid,boolean retry,String reason){}
}
