package ffdd.opsconsole.device.web;

import ffdd.opsconsole.common.api.OpsAdminApi;
import ffdd.opsconsole.device.application.LegacyUnknownZeroCreditService;
import ffdd.opsconsole.device.application.LegacyUnknownZeroCreditCases;
import ffdd.opsconsole.platform.application.OpsAuditCenterService;
import ffdd.opsconsole.platform.domain.AuditLockTarget;
import ffdd.opsconsole.platform.dto.AuditOperationProposalRequest;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.exception.BizException;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping(OpsAdminApi.ADMIN_PREFIX+"/devices/legacy-unknown-resolutions")
@RequiredArgsConstructor
public class LegacyUnknownZeroCreditController {
    private final LegacyUnknownZeroCreditService service;
    private final OpsAuditCenterService audit;
    public record Proposal(String reason,String proofSha) { }
    @GetMapping("/{deviceId}")
    @PreAuthorize("hasAuthority('device_e5_write')")
    public Object candidate(@PathVariable long deviceId) { return ApiResult.ok(service.candidate(deviceId)); }
    @PostMapping("/{deviceId}/proposals")
    @PreAuthorize("hasAuthority('device_e5_write') and hasAuthority('platform_a2_proposal_create')")
    public Object propose(@PathVariable long deviceId,@RequestHeader(value="Idempotency-Key",required=false)String key,
            @RequestBody Proposal request) {
        if (request==null || request.reason()==null || request.reason().trim().length()<8 || request.reason().trim().length()>200
                || request.proofSha()==null || !request.proofSha().matches("[a-f0-9]{64}"))
            throw new BizException(422,"LEGACY_UNKNOWN_PROPOSAL_INVALID");
        var command=service.proposal(deviceId,request.proofSha());
        return audit.createProposal(key,new AuditOperationProposalRequest("结束未确认任务（不计收益）",String.valueOf(deviceId),
                null,null,null,null,"acct",false,false,null,request.reason(),"E5",command,
                new AuditLockTarget("E","legacy_unknown",LegacyUnknownZeroCreditCases.require(deviceId).taskNo()),null));
    }
}
