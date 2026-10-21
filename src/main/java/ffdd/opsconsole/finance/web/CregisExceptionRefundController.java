package ffdd.opsconsole.finance.web;

import ffdd.opsconsole.finance.cregis.CregisExceptionRefundService;
import ffdd.opsconsole.shared.api.ApiResult;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController @RequiredArgsConstructor
@RequestMapping("/api/admin/finance/cregis/refunds")
public class CregisExceptionRefundController {
    private static final String READ="hasAuthority('finance_d1_read')";
    private static final String WRITE=READ+" and hasAuthority('finance_d1_channel_manage') and hasAuthority('finance_d2_withdrawal_approve')";
    private final CregisExceptionRefundService service;
    @GetMapping("/fee-policy") @PreAuthorize(READ)
    public ApiResult<Map<String,Object>> policy(){return ApiResult.ok(service.policy());}
    @GetMapping("/fee-policy/cases") @PreAuthorize(READ)
    public ApiResult<List<Map<String,Object>>> policyCases(){return ApiResult.ok(service.policyCases());}
    @PostMapping("/fee-policy/cases") @PreAuthorize(WRITE)
    public ApiResult<Map<String,Object>> proposePolicy(@RequestHeader("Idempotency-Key")String key,@RequestBody PolicyProposal r){
        return ApiResult.ok(service.proposePolicy(key,r.expectedVersion(),r.mode(),r.feeUsdt(),r.reason()));}
    @PostMapping("/fee-policy/cases/{id}/decision") @PreAuthorize(WRITE)
    public ApiResult<Map<String,Object>> decidePolicy(@PathVariable long id,@RequestHeader("Idempotency-Key")String key,@RequestBody Decision r){
        return ApiResult.ok(service.decidePolicy(key,id,r.expectedVersion(),r.decision(),r.reason()));}
    @PostMapping("/preview") @PreAuthorize(WRITE)
    public ApiResult<Map<String,Object>> preview(@RequestBody Ownership r){return ApiResult.ok(service.preview(r.challengeId(),r.signature()));}
    @GetMapping("/cases") @PreAuthorize(READ)
    public ApiResult<Map<String,Object>> cases(@RequestParam(defaultValue="0")long beforeId,@RequestParam(defaultValue="20")int limit){
        return ApiResult.ok(service.cases(beforeId,limit));}
    @GetMapping("/cases/{id}") @PreAuthorize(READ)
    public ApiResult<Map<String,Object>> detail(@PathVariable long id){return ApiResult.ok(service.detail(id));}
    @PostMapping("/cases") @PreAuthorize(WRITE)
    public ApiResult<Map<String,Object>> propose(@RequestHeader("Idempotency-Key")String key,@RequestBody Proposal r){
        return ApiResult.ok(service.propose(key,r.challengeId(),r.signature(),r.evidenceHash(),r.reason()));}
    @PostMapping("/cases/{id}/decision") @PreAuthorize(WRITE)
    public ApiResult<Map<String,Object>> decide(@PathVariable long id,@RequestHeader("Idempotency-Key")String key,@RequestBody Decision r){
        return ApiResult.ok(service.decide(key,id,r.expectedVersion(),r.decision(),r.reason()));}
    @PostMapping("/cases/{id}/recover") @PreAuthorize(WRITE)
    public ApiResult<Map<String,Object>> recover(@PathVariable long id,@RequestHeader("Idempotency-Key")String key,@RequestBody Recovery r){
        return ApiResult.ok(service.recover(key,id,r.expectedVersion(),r.providerCid(),r.reason()));}
    @PostMapping("/cases/{id}/retry") @PreAuthorize(WRITE)
    public ApiResult<Map<String,Object>> retry(@PathVariable long id,@RequestHeader("Idempotency-Key")String key,@RequestBody Retry r){
        return ApiResult.ok(service.retry(key,id,r.expectedVersion(),r.reason()));}
    public record PolicyProposal(long expectedVersion,String mode,String feeUsdt,String reason){}
    public record Decision(long expectedVersion,String decision,String reason){}
    public record Ownership(String challengeId,String signature){}
    public record Proposal(String challengeId,String signature,String evidenceHash,String reason){}
    public record Recovery(long expectedVersion,String providerCid,String reason){}
    public record Retry(long expectedVersion,String reason){}
}
