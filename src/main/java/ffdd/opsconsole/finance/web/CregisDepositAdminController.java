package ffdd.opsconsole.finance.web;

import ffdd.opsconsole.common.api.OpsAdminApi;
import ffdd.opsconsole.finance.cregis.CregisDepositService;
import ffdd.opsconsole.finance.cregis.CregisDepositReconciliation;
import ffdd.opsconsole.finance.cregis.CregisDepositReviewService;
import ffdd.opsconsole.finance.cregis.CregisDepositSwitchService;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.exception.BizException;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestHeader;
import static ffdd.opsconsole.promotion.domain.PromotionValues.hash;
import static ffdd.opsconsole.promotion.domain.PromotionValues.values;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(OpsAdminApi.ADMIN_PREFIX + "/finance/cregis")
@RequiredArgsConstructor
@Slf4j
public class CregisDepositAdminController {
    private final CregisDepositService deposits;
    private final CregisDepositReconciliation reconciliation;
    private final CregisDepositReviewService reviews;
    private final CregisDepositSwitchService switches;
    private final ffdd.opsconsole.shared.idempotency.AdminIdempotencyService commands;
    private final ffdd.opsconsole.finance.cregis.CregisAddressPoolPolicyService poolPolicies;
    private final ffdd.opsconsole.finance.cregis.CregisExceptionAdminGuard admins;
    private final ffdd.opsconsole.shared.idempotency.mapper.AdminIdempotencyRecordMapper commandRecords;

    private final ffdd.opsconsole.finance.cregis.CregisDepositPolicyService depositPolicies;

    @GetMapping("/deposit-policy")
    @PreAuthorize("hasAuthority('finance_d1_read')")
    public ApiResult<ffdd.opsconsole.finance.cregis.CregisDepositPolicy> depositPolicy() {
        return ApiResult.ok(depositPolicies.current());
    }

    @GetMapping("/addresses/policy")
    @PreAuthorize("hasAuthority('finance_d1_read')")
    public ApiResult<ffdd.opsconsole.finance.cregis.CregisAddressPoolPolicy> poolPolicy() {
        return ApiResult.ok(poolPolicies.read());
    }

    @PostMapping("/addresses/policy")
    @PreAuthorize("hasAuthority('finance_d1_channel_manage')")
    public ApiResult<ffdd.opsconsole.finance.cregis.CregisAddressPoolPolicy> savePoolPolicy(@RequestBody PoolPolicyRequest request,
            @RequestHeader(value="Idempotency-Key",required=false) String key) {
        long actor=admins.require(true);
        return ApiResult.ok(commands.executeRetained("CREGIS_POOL_POLICY:"+actor,key,hash(request),
                ffdd.opsconsole.finance.cregis.CregisAddressPoolPolicy.class,
                ()->poolPolicies.save(new ffdd.opsconsole.finance.cregis.CregisAddressPoolPolicy(
                request.enabled(),request.target(),request.lowWatermark(),request.batchSize(),request.concurrency(),request.version()),request.reason())));
    }
    public record PoolPolicyRequest(boolean enabled,int target,int lowWatermark,int batchSize,int concurrency,long version,String reason) { }

    @GetMapping("/exceptions")
    @PreAuthorize("hasAuthority('finance_d1_read')")
    public ApiResult<Map<String, Object>> exceptions() {
        return ApiResult.ok(deposits.exceptions());
    }

    @GetMapping("/deposits")
    @PreAuthorize("hasAuthority('finance_d1_read')")
    public ApiResult<Map<String, Object>> deposits(@RequestParam(defaultValue = "0") long beforeId,
                                                   @RequestParam(defaultValue = "20") int limit) {
        return ApiResult.ok(deposits.adminDeposits(beforeId, limit));
    }

    @GetMapping("/risk-alerts/{beforeId}")
    @PreAuthorize("hasAuthority('finance_d1_read')")
    public ApiResult<List<Map<String, Object>>> riskAlertsBefore(@PathVariable long beforeId) {
        return ApiResult.ok(deposits.riskAlertsBefore(beforeId));
    }

    @PostMapping("/addresses/provision")
    @PreAuthorize("hasAuthority('finance_d1_channel_manage')")
    public ApiResult<Map<String, Object>> provisionAddress(@RequestParam(defaultValue="BEP20") String network,
            @RequestHeader(value="Idempotency-Key",required=false) String key,
            @RequestBody(required=false) Map<String,Object> request, Authentication auth) {
        long actor=admin(auth);
        ffdd.opsconsole.finance.cregis.CregisNetwork selected;
        try { selected=ffdd.opsconsole.finance.cregis.CregisNetwork.fromNetwork(network); }
        catch(IllegalArgumentException unsupported) { return ApiResult.fail(422,"DEPOSIT_NETWORK_UNAVAILABLE"); }
        return originalResult(commands.executeRetained("CREGIS_ADDRESS_PROVISION:"+actor,key,
                hash(values("network",selected.network(),"body",request)),ApiResult.class,()->{
                    try { return ApiResult.ok(deposits.provisionPoolAddress(selected)); }
                    catch(BizException rejected) {
                        // A blocked enqueue may have tripped the risk gate. Commit that gate with the rejection receipt.
                        if(!"CREGIS_PROVISION_GATE_BLOCKED".equals(rejected.getMessage())) throw rejected;
                        return ApiResult.fail(rejected.getCode(),rejected.getMessage());
                    }
                }));
    }

    @GetMapping("/reconciliation")
    @PreAuthorize("hasAuthority('finance_d1_read')")
    public ApiResult<List<Map<String, Object>>> reconciliation() {
        return ApiResult.ok(reconciliation.runs());
    }

    @PostMapping("/reconciliation/run")
    @PreAuthorize("hasAuthority('finance_d1_channel_manage')")
    public ApiResult<Map<String, Object>> reconcileNow(@RequestParam(defaultValue="BEP20") String network,
            @RequestHeader(value="Idempotency-Key",required=false) String key,
            @RequestBody(required=false) Map<String,Object> request, Authentication auth) {
        long actor=admin(auth);
        ffdd.opsconsole.finance.cregis.CregisNetwork selected;
        try { selected=ffdd.opsconsole.finance.cregis.CregisNetwork.fromNetwork(network); }
        catch(IllegalArgumentException unsupported) { return ApiResult.fail(422,"DEPOSIT_NETWORK_UNAVAILABLE"); }
        return retainedIndependent("CREGIS_RECONCILE:"+actor,key,hash(values("network",selected.network(),"body",request)),
                ()->reconciliation.runOnce(selected));
    }

    @GetMapping("/commands/{operation}")
    @PreAuthorize("hasAuthority('finance_d1_channel_manage')")
    public ApiResult<Map<String,Object>> commandResult(@PathVariable String operation,
            @RequestParam(required=false) String commandKey,Authentication auth) {
        long actor=admin(auth);
        admins.requireActive(actor);
        if(List.of("pool-policy","ownership-challenge").contains(operation)) admins.require(true);
        String scope=switch(operation) {
            case "address-provision" -> "CREGIS_ADDRESS_PROVISION:";
            case "pool-policy" -> "CREGIS_POOL_POLICY:";
            case "reconcile" -> "CREGIS_RECONCILE:";
            case "review-propose" -> "CREGIS_REVIEW:PROPOSE:";
            case "review-decision" -> "CREGIS_REVIEW:DECIDE:";
            case "ownership-challenge" -> "CREGIS_OWNERSHIP_CHALLENGE:";
            default -> throw new BizException(422,"CREGIS_COMMAND_OPERATION_INVALID");
        };
        if(commandKey==null || commandKey.isBlank()) throw new BizException(400,"IDEMPOTENCY_KEY_REQUIRED");
        if(commandKey.trim().length()>128) throw new BizException(400,"IDEMPOTENCY_KEY_INVALID");
        scope+=actor;
        var original=commandRecords.selectCurrent(scope,commandKey.trim());
        if(original==null) return ApiResult.ok(values("status","NOT_FOUND","result",null));
        var recovered=commands.recoveryResult(scope,commandKey,original.getRequestHash(),Map.class);
        Map<String,Object> result=recovered.response();
        if(result==null) return ApiResult.ok(values("status",recovered.status().name(),"result",null));
        if(List.of("address-provision","reconcile","review-propose","review-decision").contains(operation)) {
            int code=((Number)result.get("code")).intValue();
            if(code!=0) return ApiResult.ok(values("status","FAILED","result",null,
                    "error",values("code",code,"message",result.get("message"))));
            result=(Map<String,Object>)result.get("data");
        }
        List<String> fields=switch(operation) {
            case "address-provision" -> List.of("state","count");
            case "pool-policy" -> List.of("enabled","target","lowWatermark","batchSize","concurrency","version");
            case "reconcile" -> List.of("runId","status","completeThrough","windowStart","windowEnd","total",
                    "fullRowHash","chainCursorBlock","chainCursorHash","bootstrap");
            case "review-propose","review-decision" -> List.of("caseId","status","version");
            case "ownership-challenge" -> List.of("challengeId","message","expiresAt","evidenceHash","fromAddress",
                    "txid","logIndex","grossAmount");
            default -> throw new BizException(422,"CREGIS_COMMAND_OPERATION_INVALID");
        };
        var projection=new LinkedHashMap<String,Object>();
        for(String field:fields) if(result.containsKey(field)) projection.put(field,result.get(field));
        return ApiResult.ok(values("status",recovered.status().name(),"result",projection));
    }

    @GetMapping("/review-cases")
    @PreAuthorize("hasAuthority('finance_d1_read')")
    public ApiResult<List<Map<String, Object>>> reviewCases() {
        return ApiResult.ok(reviews.cases());
    }

    @GetMapping("/review-cases/before/{beforeId}")
    @PreAuthorize("hasAuthority('finance_d1_read')")
    public ApiResult<List<Map<String, Object>>> reviewCasesBefore(@PathVariable long beforeId) {
        return ApiResult.ok(reviews.casesBefore(beforeId));
    }

    @GetMapping("/review-evidence/{cid}")
    @PreAuthorize("hasAuthority('finance_d1_read')")
    public ApiResult<Map<String, Object>> reviewEvidence(@PathVariable long cid) {
        return ApiResult.ok(reviews.preview(cid));
    }

    @GetMapping("/switch-cases")
    @PreAuthorize("hasAuthority('finance_d1_read')")
    public ApiResult<List<Map<String, Object>>> switchCases() {
        return ApiResult.ok(switches.cases());
    }

    @PostMapping("/switch-cases")
    @PreAuthorize("hasAuthority('finance_d1_channel_manage')")
    public ApiResult<Map<String, Object>> proposeSwitch(@RequestBody SwitchProposal request,
            @RequestHeader(value="Idempotency-Key",required=false) String key,Authentication auth) {
        long id = admin(auth);
        try {
            if(request.network()!=null) {
                if(request.depositEnabled()==null || request.payoutEnabled()!=null || request.expectedNetworkVersion()==null
                        || request.assignEnabled()!=null || request.creditEnabled()!=null)
                    throw new BizException(422,"CREGIS_SWITCH_REQUEST_INVALID");
                ffdd.opsconsole.finance.cregis.CregisNetwork network;
                try {network=ffdd.opsconsole.finance.cregis.CregisNetwork.fromNetwork(request.network());}
                catch(IllegalArgumentException invalid){throw new BizException(422,"CREGIS_NETWORK_UNSUPPORTED");}
                return ApiResult.ok(commands.executeRetained("CREGIS_SWITCH:PROPOSE:"+id,key,hash(request),Map.class,
                        ()->switches.proposeNetwork(id,request.expectedVersion(),network,
                        request.expectedNetworkVersion(),request.depositEnabled(),request.reason())));
            }
            if(request.payoutEnabled()==null || request.assignEnabled()!=null || request.creditEnabled()!=null || request.depositEnabled()!=null || request.expectedNetworkVersion()!=null)
                throw new BizException(422,"CREGIS_SWITCH_NETWORK_REQUIRED");
            return ApiResult.ok(commands.executeRetained("CREGIS_SWITCH:PROPOSE:"+id,key,hash(request),Map.class,
                    ()->switches.proposePayout(id,request.expectedVersion(),request.payoutEnabled(),request.reason())));
        } catch (BizException rejected) {
            switches.auditRejected(id, "PROPOSE", rejected.getMessage());
            throw rejected;
        }
    }

    @PostMapping("/switch-cases/{caseId}/decision")
    @PreAuthorize("hasAuthority('finance_d1_channel_manage')")
    public ApiResult<Map<String, Object>> decideSwitch(@PathVariable long caseId,
            @RequestBody SwitchDecision request,@RequestHeader(value="Idempotency-Key",required=false) String key, Authentication auth) {
        long id = admin(auth);
        try {
            return ApiResult.ok(commands.executeRetained("CREGIS_SWITCH:DECIDE:"+id,key,hash(values("caseId",caseId,"body",request)),Map.class,
                    ()->switches.check(id,caseId,request.decision(),request.reason())));
        } catch (BizException rejected) {
            switches.auditRejected(id, "DECIDE", rejected.getMessage());
            throw rejected;
        }
    }

    @PostMapping("/switches/emergency-off")
    @PreAuthorize("hasAuthority('finance_d1_channel_manage')")
    public ApiResult<Map<String, Object>> emergencyOff(@RequestBody EmergencyOff request,
            @RequestHeader(value="Idempotency-Key",required=false) String key,Authentication auth) {
        long id = admin(auth);
        try {
            return ApiResult.ok(commands.executeRetained("CREGIS_SWITCH:EMERGENCY:"+id,key,hash(request),Map.class,
                    ()->switches.emergencyOff(id,request.expectedVersion(),request.reason())));
        } catch (BizException rejected) {
            switches.auditRejected(id, "EMERGENCY_OFF", rejected.getMessage());
            throw rejected;
        }
    }

    @PostMapping("/review-cases")
    @PreAuthorize("hasAuthority('finance_d1_channel_manage')")
    public ApiResult<Map<String, Object>> propose(@RequestBody ReviewProposal request,
            @RequestHeader(value="Idempotency-Key",required=false) String key, Authentication auth) {
        long id = admin(auth);
        try {
            return retainedIndependent("CREGIS_REVIEW:PROPOSE:"+id,key,hash(request),
                    ()->reviews.propose(id, request.cid(), request.reason(), request.evidenceHash()));
        } catch (RuntimeException rejected) {
            reviews.auditRejected(id, "PROPOSE", rejected instanceof BizException
                    ? rejected.getMessage() : "CREGIS_REVIEW_FAILED");
            throw rejected;
        }
    }

    @PostMapping("/review-cases/{caseId}/decision")
    @PreAuthorize("hasAuthority('finance_d1_channel_manage')")
    public ApiResult<Map<String, Object>> decide(@PathVariable long caseId,
            @RequestBody ReviewDecision request,@RequestHeader(value="Idempotency-Key",required=false) String key, Authentication auth) {
        long id = admin(auth);
        try {
            return retainedIndependent("CREGIS_REVIEW:DECIDE:"+id,key,hash(values("caseId",caseId,"body",request)),
                    ()->reviews.check(id, caseId, request.expectedVersion(),request.decision(), request.reason()));
        } catch (RuntimeException rejected) {
            reviews.auditRejected(id, "DECIDE", rejected instanceof BizException
                    ? rejected.getMessage() : "CREGIS_REVIEW_FAILED");
            throw rejected;
        }
    }

    /** These services preserve their original local transactions, including durable risk gates on failure. */
    private ApiResult<Map<String,Object>> retainedIndependent(String scope,String key,String requestHash,
            Supplier<Map<String,Object>> action) {
        return originalResult(commands.executeRetainedIndependent(scope,key,requestHash,ApiResult.class,()->{
            try { return ApiResult.ok(action.get()); }
            catch(BizException rejected) { return ApiResult.fail(rejected.getCode(),rejected.getMessage()); }
            catch(RuntimeException failure) {
                log.error("Cregis command failed in scope {}",scope,failure);
                return ApiResult.fail(500,"CREGIS_COMMAND_FAILED");
            }
        }));
    }

    private static <T> ApiResult<T> originalResult(ApiResult<T> result) {
        if(result.getCode()!=0) throw new BizException(result.getCode(),result.getMessage());
        return result;
    }

    private static long admin(Authentication auth) {
        if (auth == null || !auth.isAuthenticated() || auth.getPrincipal() == null
                || !(auth.getDetails() instanceof Map<?, ?> details)
                || !"ADMIN".equals(String.valueOf(details.get("subjectType"))))
            throw new BizException(401, "ADMIN_AUTH_REQUIRED");
        try {
            long id = Long.parseLong(String.valueOf(auth.getPrincipal()));
            if (id > 0) return id;
        } catch (NumberFormatException invalid) { /* reject below */ }
        throw new BizException(401, "ADMIN_AUTH_REQUIRED");
    }

    public record ReviewProposal(long cid, String reason, String evidenceHash) { }
    public record ReviewDecision(long expectedVersion, String decision, String reason) { }
    public record SwitchProposal(long expectedVersion, Boolean assignEnabled,
                                 Boolean creditEnabled, Boolean payoutEnabled, String reason,
                                 String network,Boolean depositEnabled,Long expectedNetworkVersion) { }
    public record SwitchDecision(String decision, String reason) { }
    public record EmergencyOff(long expectedVersion, String reason) { }
}
