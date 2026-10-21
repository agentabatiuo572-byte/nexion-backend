package ffdd.opsconsole.finance.web;

import ffdd.opsconsole.finance.cregis.CregisExceptionClaimService;
import ffdd.opsconsole.finance.cregis.CregisExceptionOwnershipService;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.exception.BizException;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController @RequiredArgsConstructor
@RequestMapping("/api/admin/finance/cregis")
public class CregisExceptionClaimController {
    private final CregisExceptionClaimService claims;
    private final CregisExceptionOwnershipService ownership;
    @PostMapping("/exception-ownership/challenges")
    @PreAuthorize("hasAuthority('finance_d1_read') and hasAuthority('finance_d1_channel_manage')")
    public ApiResult<CregisExceptionOwnershipService.Challenge> challenge(@RequestBody ChallengeRequest r) {
        return ApiResult.ok(ownership.prepare(new CregisExceptionOwnershipService.SourceRef(r.sourceType(),id(r.sourceId())),
                r.action(),r.targetUserId()==null?null:id(r.targetUserId())));
    }
    @GetMapping("/exception-claims")
    @PreAuthorize("hasAuthority('finance_d1_read')")
    public ApiResult<Map<String,Object>> page(@RequestParam(defaultValue="0") long beforeId,@RequestParam(defaultValue="20") int limit) {
        return ApiResult.ok(claims.page(beforeId,limit));
    }
    @PostMapping("/exception-claims/preview")
    @PreAuthorize("hasAuthority('finance_d1_read')")
    public ApiResult<Map<String,Object>> preview(@RequestBody ClaimRequest r) {
        return ApiResult.ok(claims.preview(r.challengeId(),r.signature(),id(r.targetUserId())));
    }
    @PostMapping("/exception-claims")
    @PreAuthorize("hasAuthority('finance_d1_read') and hasAuthority('finance_d1_channel_manage')")
    public ApiResult<Map<String,Object>> propose(@RequestHeader("Idempotency-Key") String key,@RequestBody ClaimRequest r) {
        return ApiResult.ok(claims.propose(key,r.challengeId(),r.signature(),id(r.targetUserId()),r.evidenceHash(),r.reason()));
    }
    @PostMapping("/exception-claims/{caseId}/decision")
    @PreAuthorize("hasAuthority('finance_d1_read') and hasAuthority('finance_d1_channel_manage')")
    public ApiResult<Map<String,Object>> decide(@RequestHeader("Idempotency-Key") String key,@PathVariable String caseId,@RequestBody Decision r) {
        return ApiResult.ok(claims.decide(key,id(caseId),r.expectedVersion(),r.decision(),r.reason()));
    }
    private static long id(String id) {
        try {if(id!=null && id.matches("[1-9][0-9]{0,18}"))return Long.parseLong(id);}catch(NumberFormatException ignored){}
        throw new BizException(422,"CREGIS_EXCEPTION_REQUEST_INVALID");
    }
    public record ChallengeRequest(String sourceType,String sourceId,String action,String targetUserId) { }
    public record ClaimRequest(String challengeId,String signature,String targetUserId,String evidenceHash,String reason) { }
    public record Decision(long expectedVersion,String decision,String reason) { }
}
