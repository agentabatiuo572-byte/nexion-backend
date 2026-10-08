package ffdd.opsconsole.promotion.web;

import ffdd.opsconsole.promotion.application.*;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.exception.BizException;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import static ffdd.opsconsole.promotion.domain.PromotionValues.*;

@RestController
@RequestMapping("/api/admin/growth")
@RequiredArgsConstructor
public class OpsPromotionController {
    private final PromotionAdminService admin;
    private final PromotionPolicyService policies;
    private final PromotionRewardService rewards;
    private final PromotionMetricsService metrics;

    @ExceptionHandler(BizException.class)
    public org.springframework.http.ResponseEntity<ApiResult<Map<String,Object>>> rejected(BizException failure){
        String message=failure.getMessage();boolean field=message!=null&&message.startsWith("PROMOTION_FIELD_INVALID:");
        var data=field?values("commandId",null,"retryable",false,"fieldErrors",List.of(values("field",message.substring("PROMOTION_FIELD_INVALID:".length()),"reason","INVALID_OR_MISSING"))):null;
        return org.springframework.http.ResponseEntity.status(failure.getCode()).body(ApiResult.fail(failure.getCode(),message,data));
    }
    @ExceptionHandler(org.springframework.security.access.AccessDeniedException.class)
    public org.springframework.http.ResponseEntity<ApiResult<Void>> forbidden(){return org.springframework.http.ResponseEntity.status(403).body(ApiResult.fail(403,"PROMOTION_PERMISSION_DENIED"));}
    @ExceptionHandler(org.springframework.web.bind.MissingRequestHeaderException.class)
    public org.springframework.http.ResponseEntity<ApiResult<Void>> missingHeader(){return org.springframework.http.ResponseEntity.badRequest().body(ApiResult.fail(400,"PROMOTION_IDEMPOTENCY_KEY_REQUIRED"));}

    @GetMapping("/promotion-catalog") @PreAuthorize("hasAuthority('growth_promotion_read')")
    public ApiResult<Map<String,Object>> catalog(){return ApiResult.ok(admin.catalog());}
    @GetMapping("/promotion-commands/{idempotencyKey}") @PreAuthorize("hasAuthority('growth_promotion_read')")
    public ApiResult<Map<String,Object>> command(@PathVariable String idempotencyKey,@RequestParam String operation,@RequestParam String targetId){return ApiResult.ok(admin.recover(idempotencyKey,operation,targetId));}
    @GetMapping("/promotion-policies") @PreAuthorize("hasAuthority('growth_promotion_policy_read')")
    public ApiResult<Map<String,Object>> policyList(@RequestParam(required=false) String cursor,@RequestParam(defaultValue="20") int limit){return ApiResult.ok(policies.page(null,null,cursor,limit));}
    @GetMapping("/promotion-policies/{policyId}/versions/{version}") @PreAuthorize("hasAuthority('growth_promotion_policy_read')")
    public ApiResult<Map<String,Object>> policy(@PathVariable String policyId,@PathVariable long version){return ApiResult.ok(policies.get(policyId,version));}
    @GetMapping("/device-rights-profiles") @PreAuthorize("hasAuthority('growth_promotion_policy_read')")
    public ApiResult<Map<String,Object>> profiles(@RequestParam(required=false) String cursor,@RequestParam(defaultValue="20") int limit){return ApiResult.ok(policies.page("DEVICE_RIGHTS",null,cursor,limit));}
    @GetMapping("/device-rights-profiles/{policyId}/versions/{version}") @PreAuthorize("hasAuthority('growth_promotion_policy_read')")
    public ApiResult<Map<String,Object>> profile(@PathVariable String policyId,@PathVariable long version){var p=policies.get(policyId,version);if(!"DEVICE_RIGHTS".equals(map(p.get("content")).get("kind")))throw new BizException(404,"PROMOTION_RESOURCE_NOT_FOUND");return ApiResult.ok(p);}
    @PostMapping("/promotion-policies") @PreAuthorize("hasAuthority('growth_promotion_policy_write')")
    public ApiResult<Map<String,Object>> createPolicy(@RequestHeader("Idempotency-Key") String key,@RequestBody Map<String,Object> request){return ApiResult.ok(admin.command("createPolicy","promotion-policies",key,request,()->policies.create(null,request)));}
    @PostMapping("/promotion-policies/{policyId}/versions") @PreAuthorize("hasAuthority('growth_promotion_policy_write')")
    public ApiResult<Map<String,Object>> createPolicyVersion(@PathVariable String policyId,@RequestHeader("Idempotency-Key") String key,@RequestBody Map<String,Object> request){return ApiResult.ok(admin.command("createPolicyVersion",policyId,key,request,()->policies.create(policyId,request)));}

    @PostMapping("/promotion-policies/{policyId}/versions/{version}/approve") @PreAuthorize("hasAuthority('growth_promotion_policy_approve')")
    public ApiResult<Map<String,Object>> approvePolicy(@PathVariable String policyId,@PathVariable long version,@RequestHeader("Idempotency-Key") String key,@RequestBody Map<String,Object> request){return ApiResult.ok(admin.command("approvePolicy",policyId+":"+version,key,request,()->policies.transition(policyId,version,"approve",request,PromotionAdminService.actor())));}

    @PostMapping("/promotion-policies/{policyId}/versions/{version}/revoke") @PreAuthorize("hasAuthority('growth_promotion_policy_approve')")
    public ApiResult<Map<String,Object>> revokePolicy(@PathVariable String policyId,@PathVariable long version,@RequestHeader("Idempotency-Key") String key,@RequestBody Map<String,Object> request){return ApiResult.ok(admin.command("revokePolicy",policyId+":"+version,key,request,()->policies.transition(policyId,version,"revoke",request,PromotionAdminService.actor())));}

    @GetMapping("/promotions") @PreAuthorize("hasAuthority('growth_promotion_read')")
    public ApiResult<Map<String,Object>> list(@RequestParam(required=false) String cursor,@RequestParam(defaultValue="20") int limit,@RequestParam(required=false) String querySnapshot,@RequestParam(required=false) String query,@RequestParam(required=false) String name,@RequestParam(required=false) String activityId,@RequestParam(required=false) String category,@RequestParam(required=false) String template,@RequestParam(required=false) String state,@RequestParam(required=false) String from,@RequestParam(required=false) String to,@RequestParam(required=false) String timezone,@RequestParam(required=false) String sort){return ApiResult.ok(admin.page(values("query",query,"name",name,"activityId",activityId,"category",category,"template",template,"state",state,"from",from,"to",to,"timezone",timezone,"sort",sort),querySnapshot,cursor,limit));}
    @GetMapping("/promotions/{activityId}") @PreAuthorize("hasAuthority('growth_promotion_read')")
    public ApiResult<Map<String,Object>> get(@PathVariable String activityId){return ApiResult.ok(admin.get(activityId));}
    @GetMapping("/promotions/{activityId}/versions") @PreAuthorize("hasAuthority('growth_promotion_read')")
    public ApiResult<Map<String,Object>> versions(@PathVariable String activityId,@RequestParam(required=false) String cursor,@RequestParam(defaultValue="20") int limit){return ApiResult.ok(admin.versions(activityId,cursor,limit));}
    @GetMapping("/promotions/{activityId}/versions/{version}") @PreAuthorize("hasAuthority('growth_promotion_read')")
    public ApiResult<Map<String,Object>> version(@PathVariable String activityId,@PathVariable long version){return ApiResult.ok(admin.version(activityId,version));}
    @PostMapping("/promotions") @PreAuthorize("hasAuthority('growth_promotion_edit')")
    public ApiResult<Map<String,Object>> create(@RequestHeader("Idempotency-Key") String key,@RequestBody Map<String,Object> request){return ApiResult.ok(admin.command("createPromotion","promotions",key,request,()->admin.create(request)));}
    @PutMapping("/promotions/{activityId}/draft") @PreAuthorize("hasAuthority('growth_promotion_edit')")
    public ApiResult<Map<String,Object>> save(@PathVariable String activityId,@RequestHeader("Idempotency-Key") String key,@RequestBody Map<String,Object> request){return ApiResult.ok(admin.command("saveDraft",activityId,key,request,()->admin.save(activityId,request)));}
    @PostMapping("/promotions/{activityId}/draft-versions") @PreAuthorize("hasAuthority('growth_promotion_edit')")
    public ApiResult<Map<String,Object>> newVersion(@PathVariable String activityId,@RequestHeader("Idempotency-Key") String key,@RequestBody Map<String,Object> request){return ApiResult.ok(admin.command("createDraftVersion",activityId,key,request,()->admin.newVersion(activityId,request,false)));}
    @PostMapping("/promotions/{activityId}/copies") @PreAuthorize("hasAuthority('growth_promotion_edit')")
    public ApiResult<Map<String,Object>> copy(@PathVariable String activityId,@RequestHeader("Idempotency-Key") String key,@RequestBody Map<String,Object> request){return ApiResult.ok(admin.command("copyPromotion",activityId,key,request,()->admin.newVersion(activityId,request,true)));}
    @PostMapping("/promotions/{activityId}/simulate") @PreAuthorize("hasAuthority('growth_promotion_simulate')")
    public ApiResult<Map<String,Object>> simulate(@PathVariable String activityId,@RequestBody Map<String,Object> request){return ApiResult.ok(admin.simulate(activityId,request));}
    @PostMapping("/promotions/{activityId}/audience-preview") @PreAuthorize("hasAuthority('growth_promotion_simulate')")
    public ApiResult<Map<String,Object>> audience(@PathVariable String activityId,@RequestBody Map<String,Object> request){return ApiResult.ok(admin.audiencePreview(activityId,request));}

    @PostMapping("/promotions/{activityId}/submit") @PreAuthorize("hasAuthority('growth_promotion_submit')")
    public ApiResult<Map<String,Object>> submit(@PathVariable String activityId,@RequestHeader("Idempotency-Key") String key,@RequestBody Map<String,Object> request){return ApiResult.ok(admin.command("submitPromotion",activityId,key,request,()->admin.versionAction(activityId,"submit",request)));}

    @PostMapping("/promotions/{activityId}/withdraw") @PreAuthorize("hasAuthority('growth_promotion_submit')")
    public ApiResult<Map<String,Object>> withdraw(@PathVariable String activityId,@RequestHeader("Idempotency-Key") String key,@RequestBody Map<String,Object> request){return ApiResult.ok(admin.command("withdrawPromotion",activityId,key,request,()->admin.versionAction(activityId,"withdraw",request)));}

    @PostMapping("/promotions/{activityId}/approve") @PreAuthorize("hasAuthority('growth_promotion_approve')")
    public ApiResult<Map<String,Object>> approve(@PathVariable String activityId,@RequestHeader("Idempotency-Key") String key,@RequestBody Map<String,Object> request){return ApiResult.ok(admin.command("approvePromotion",activityId,key,request,()->admin.versionAction(activityId,"approve",request)));}

    @PostMapping("/promotions/{activityId}/reject") @PreAuthorize("hasAuthority('growth_promotion_approve')")
    public ApiResult<Map<String,Object>> reject(@PathVariable String activityId,@RequestHeader("Idempotency-Key") String key,@RequestBody Map<String,Object> request){return ApiResult.ok(admin.command("rejectPromotion",activityId,key,request,()->admin.versionAction(activityId,"reject",request)));}

    @PostMapping("/promotions/{activityId}/publish") @PreAuthorize("hasAuthority('growth_promotion_publish')")
    public ApiResult<Map<String,Object>> publish(@PathVariable String activityId,@RequestHeader("Idempotency-Key") String key,@RequestBody Map<String,Object> request){return ApiResult.ok(admin.command("publishPromotion",activityId,key,request,()->admin.versionAction(activityId,"publish",request)));}

    @PostMapping("/promotions/{activityId}/pause") @PreAuthorize("hasAuthority('growth_promotion_pause')")
    public ApiResult<Map<String,Object>> pause(@PathVariable String activityId,@RequestHeader("Idempotency-Key") String key,@RequestBody Map<String,Object> request){return ApiResult.ok(admin.command("pausePromotion",activityId,key,request,()->admin.state(activityId,"pause",request)));}

    @PostMapping("/promotions/{activityId}/resume") @PreAuthorize("hasAuthority('growth_promotion_pause')")
    public ApiResult<Map<String,Object>> resume(@PathVariable String activityId,@RequestHeader("Idempotency-Key") String key,@RequestBody Map<String,Object> request){return ApiResult.ok(admin.command("resumePromotion",activityId,key,request,()->admin.state(activityId,"resume",request)));}

    @PostMapping("/promotions/{activityId}/end") @PreAuthorize("hasAuthority('growth_promotion_end')")
    public ApiResult<Map<String,Object>> end(@PathVariable String activityId,@RequestHeader("Idempotency-Key") String key,@RequestBody Map<String,Object> request){return ApiResult.ok(admin.command("endPromotion",activityId,key,request,()->admin.state(activityId,"end",request)));}

    @PostMapping("/promotions/{activityId}/archive") @PreAuthorize("hasAuthority('growth_promotion_archive')")
    public ApiResult<Map<String,Object>> archive(@PathVariable String activityId,@RequestHeader("Idempotency-Key") String key,@RequestBody Map<String,Object> request){return ApiResult.ok(admin.command("archivePromotion",activityId,key,request,()->admin.state(activityId,"archive",request)));}

    @GetMapping("/promotion-rewards") @PreAuthorize("hasAuthority('growth_promotion_reward_read')")
    public ApiResult<Map<String,Object>> rewardList(@RequestParam(required=false) String cursor,@RequestParam(defaultValue="20") int limit,@RequestParam(required=false) String activityId,@RequestParam(required=false) String state,@RequestParam(required=false) String orderNo,@RequestParam(required=false) Long beneficiaryId,@RequestParam(required=false) String type){return ApiResult.ok(rewards.page(null,true,cursor,limit,activityId,state,orderNo,beneficiaryId,type));}
    @GetMapping("/promotion-rewards/{obligationId}") @PreAuthorize("hasAuthority('growth_promotion_reward_read')")
    public ApiResult<Map<String,Object>> reward(@PathVariable String obligationId){return ApiResult.ok(rewards.get(null,obligationId,true));}

    @PostMapping("/promotion-rewards/{obligationId}/retry") @PreAuthorize("hasAuthority('growth_promotion_reward_retry')")
    public ApiResult<Map<String,Object>> retryReward(@PathVariable String obligationId,@RequestHeader("Idempotency-Key") String key,@RequestBody Map<String,Object> request){return ApiResult.ok(admin.command("retryReward",obligationId,key,request,()->admin.rewardAction(obligationId,"retry",request,"PC-"+sha256(PromotionAdminService.actor()+":retry:"+obligationId+":"+key).substring(0,48))));}

    @PostMapping("/promotion-rewards/{obligationId}/reconcile") @PreAuthorize("hasAuthority('growth_promotion_reward_reconcile')")
    public ApiResult<Map<String,Object>> reconcileReward(@PathVariable String obligationId,@RequestHeader("Idempotency-Key") String key,@RequestBody Map<String,Object> request){return ApiResult.ok(admin.command("reconcileReward",obligationId,key,request,()->admin.rewardAction(obligationId,"reconcile",request,"PC-"+sha256(PromotionAdminService.actor()+":reconcile:"+obligationId+":"+key).substring(0,48))));}

    @PostMapping("/promotion-rewards/{obligationId}/cancel") @PreAuthorize("hasAuthority('growth_promotion_reward_cancel')")
    public ApiResult<Map<String,Object>> cancelReward(@PathVariable String obligationId,@RequestHeader("Idempotency-Key") String key,@RequestBody Map<String,Object> request){return ApiResult.ok(admin.command("cancelReward",obligationId,key,request,()->admin.rewardAction(obligationId,"cancel",request,"PC-"+sha256(PromotionAdminService.actor()+":cancel:"+obligationId+":"+key).substring(0,48))));}

    @PostMapping("/promotion-rewards/{obligationId}/reverse") @PreAuthorize("hasAuthority('growth_promotion_reward_reverse')")
    public ApiResult<Map<String,Object>> reverseReward(@PathVariable String obligationId,@RequestHeader("Idempotency-Key") String key,@RequestBody Map<String,Object> request){return ApiResult.ok(admin.command("reverseReward",obligationId,key,request,()->admin.rewardAction(obligationId,"reverse",request,"PC-"+sha256(PromotionAdminService.actor()+":reverse:"+obligationId+":"+key).substring(0,48))));}

    @PostMapping("/promotion-rewards/{obligationId}/resolve") @PreAuthorize("hasAuthority('growth_promotion_reward_resolve')")
    public ApiResult<Map<String,Object>> resolveReward(@PathVariable String obligationId,@RequestHeader("Idempotency-Key") String key,@RequestBody Map<String,Object> request){return ApiResult.ok(admin.command("resolveReward",obligationId,key,request,()->admin.rewardAction(obligationId,"resolve",request,"PC-"+sha256(PromotionAdminService.actor()+":resolve:"+obligationId+":"+key).substring(0,48))));}

    @GetMapping("/promotions/{activityId}/metrics") @PreAuthorize("hasAuthority('growth_promotion_metrics_read')")
    public ApiResult<Map<String,Object>> metrics(@PathVariable String activityId,@RequestParam String from,@RequestParam String to,@RequestParam String timezone,@RequestParam(required=false) Long version,@RequestParam(defaultValue="SKU") String groupBy,@RequestParam(required=false) String querySnapshot,@RequestParam(required=false) String cursor,@RequestParam(defaultValue="20") int limit){return ApiResult.ok(metrics.metrics(activityId,version,from,to,timezone,groupBy,querySnapshot,cursor,limit));}
    @PostMapping("/promotions/{activityId}/metrics/exports") @PreAuthorize("hasAuthority('growth_promotion_metrics_export')")
    public ApiResult<Map<String,Object>> createExport(@PathVariable String activityId,@RequestHeader("Idempotency-Key") String key,@RequestBody Map<String,Object> request){return ApiResult.ok(admin.command("createExport",activityId,key,request,()->metrics.createExport(activityId,request)));}
    @GetMapping("/promotion-exports/{exportId}") @PreAuthorize("hasAuthority('growth_promotion_metrics_export')")
    public ApiResult<Map<String,Object>> export(@PathVariable String exportId){return ApiResult.ok(metrics.export(exportId,false));}
    @GetMapping("/promotion-exports/{exportId}/download") @PreAuthorize("hasAuthority('growth_promotion_metrics_export')")
    public ApiResult<Map<String,Object>> download(@PathVariable String exportId){return ApiResult.ok(metrics.export(exportId,true));}
}
