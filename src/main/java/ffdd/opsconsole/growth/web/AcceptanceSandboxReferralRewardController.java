package ffdd.opsconsole.growth.web;

import ffdd.opsconsole.common.api.OpsAdminApi;
import ffdd.opsconsole.growth.application.OpsReferralRewardService;
import ffdd.opsconsole.growth.application.H8AcceptanceSandboxRunScope;
import ffdd.opsconsole.growth.dto.AcceptanceSandboxReferralSettlementRequest;
import ffdd.opsconsole.shared.api.ApiResult;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Registered only by the local acceptance runtime; it does not exist in production. */
@RestController
@Profile("test")
@RequestMapping(OpsAdminApi.ADMIN_PREFIX + "/growth/referral-rewards/acceptance")
@RequiredArgsConstructor
public class AcceptanceSandboxReferralRewardController {
    private final OpsReferralRewardService service;
    private final H8AcceptanceSandboxRunScope runScope;

    @GetMapping("/overview")
    @PreAuthorize("hasAuthority('growth_h8_read')")
    public ApiResult<Map<String, Object>> overview(@RequestParam String runId) {
        return ApiResult.ok(service.acceptanceSandboxOverview(runScope.requireCurrentRunId(runId)));
    }

    @PostMapping("/sandbox-settlements")
    @PreAuthorize("hasAuthority('growth_h8_settle')")
    public ApiResult<Map<String, Object>> settle(
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String key,
            @RequestBody AcceptanceSandboxReferralSettlementRequest request) {
        String runId = runScope.requireCurrentRunId(request == null ? null : request.runId());
        if (request == null) {
            return ApiResult.ok(service.runAcceptanceSandboxSettlement(key, null));
        }
        return ApiResult.ok(service.runAcceptanceSandboxSettlement(key,
                new AcceptanceSandboxReferralSettlementRequest(runId, request.invitedUserId(),
                        request.reason(), request.operator())));
    }
}
