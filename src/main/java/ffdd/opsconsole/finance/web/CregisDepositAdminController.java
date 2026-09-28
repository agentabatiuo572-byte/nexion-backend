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
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(OpsAdminApi.ADMIN_PREFIX + "/finance/cregis")
@RequiredArgsConstructor
public class CregisDepositAdminController {
    private final CregisDepositService deposits;
    private final CregisDepositReconciliation reconciliation;
    private final CregisDepositReviewService reviews;
    private final CregisDepositSwitchService switches;

    @GetMapping("/exceptions")
    @PreAuthorize("hasAuthority('finance_d1_read')")
    public ApiResult<Map<String, Object>> exceptions() {
        return ApiResult.ok(deposits.exceptions());
    }

    @PostMapping("/addresses/provision")
    @PreAuthorize("hasAuthority('finance_d1_channel_manage')")
    public ApiResult<Map<String, Object>> provisionAddress() {
        return ApiResult.ok(deposits.provisionPoolAddress());
    }

    @GetMapping("/reconciliation")
    @PreAuthorize("hasAuthority('finance_d1_read')")
    public ApiResult<List<Map<String, Object>>> reconciliation() {
        return ApiResult.ok(reconciliation.runs());
    }

    @PostMapping("/reconciliation/run")
    @PreAuthorize("hasAuthority('finance_d1_channel_manage')")
    public ApiResult<Map<String, Object>> reconcileNow() {
        return ApiResult.ok(reconciliation.runOnce());
    }

    @GetMapping("/review-cases")
    @PreAuthorize("hasAuthority('finance_d1_read')")
    public ApiResult<List<Map<String, Object>>> reviewCases() {
        return ApiResult.ok(reviews.cases());
    }

    @GetMapping("/switch-cases")
    @PreAuthorize("hasAuthority('finance_d1_read')")
    public ApiResult<List<Map<String, Object>>> switchCases() {
        return ApiResult.ok(switches.cases());
    }

    @PostMapping("/switch-cases")
    @PreAuthorize("hasAuthority('finance_d1_channel_manage')")
    public ApiResult<Map<String, Object>> proposeSwitch(@RequestBody SwitchProposal request,
            Authentication auth) {
        long id = admin(auth);
        try {
            return ApiResult.ok(switches.propose(id, request.expectedVersion(),
                    request.assignEnabled(), request.creditEnabled(), request.reason()));
        } catch (BizException rejected) {
            switches.auditRejected(id, "PROPOSE", rejected.getMessage());
            throw rejected;
        }
    }

    @PostMapping("/switch-cases/{caseId}/decision")
    @PreAuthorize("hasAuthority('finance_d1_channel_manage')")
    public ApiResult<Map<String, Object>> decideSwitch(@PathVariable long caseId,
            @RequestBody SwitchDecision request, Authentication auth) {
        long id = admin(auth);
        try {
            return ApiResult.ok(switches.check(id, caseId, request.decision(), request.reason()));
        } catch (BizException rejected) {
            switches.auditRejected(id, "DECIDE", rejected.getMessage());
            throw rejected;
        }
    }

    @PostMapping("/switches/emergency-off")
    @PreAuthorize("hasAuthority('finance_d1_channel_manage')")
    public ApiResult<Map<String, Object>> emergencyOff(@RequestBody EmergencyOff request,
            Authentication auth) {
        long id = admin(auth);
        try {
            return ApiResult.ok(switches.emergencyOff(id, request.expectedVersion(), request.reason()));
        } catch (BizException rejected) {
            switches.auditRejected(id, "EMERGENCY_OFF", rejected.getMessage());
            throw rejected;
        }
    }

    @PostMapping("/review-cases")
    @PreAuthorize("hasAuthority('finance_d1_channel_manage')")
    public ApiResult<Map<String, Object>> propose(@RequestBody ReviewProposal request, Authentication auth) {
        long id = admin(auth);
        try {
            return ApiResult.ok(reviews.propose(id, request.cid(), request.reason(), request.evidenceHash()));
        } catch (BizException rejected) {
            reviews.auditRejected(id, "PROPOSE", rejected.getMessage());
            throw rejected;
        }
    }

    @PostMapping("/review-cases/{caseId}/decision")
    @PreAuthorize("hasAuthority('finance_d1_channel_manage')")
    public ApiResult<Map<String, Object>> decide(@PathVariable long caseId,
            @RequestBody ReviewDecision request, Authentication auth) {
        long id = admin(auth);
        try {
            return ApiResult.ok(reviews.check(id, caseId, request.expectedVersion(),
                    request.decision(), request.reason()));
        } catch (BizException rejected) {
            reviews.auditRejected(id, "DECIDE", rejected.getMessage());
            throw rejected;
        }
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
    public record SwitchProposal(long expectedVersion, boolean assignEnabled,
                                 boolean creditEnabled, String reason) { }
    public record SwitchDecision(String decision, String reason) { }
    public record EmergencyOff(long expectedVersion, String reason) { }
}
