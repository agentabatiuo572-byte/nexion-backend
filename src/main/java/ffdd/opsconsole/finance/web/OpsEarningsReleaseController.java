package ffdd.opsconsole.finance.web;

import ffdd.opsconsole.common.api.OpsAdminApi;
import ffdd.opsconsole.finance.application.EarningsReleaseService;
import ffdd.opsconsole.risk.dto.EarningsManualReleaseRequest;
import ffdd.opsconsole.shared.api.ApiResult;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/risk/multi-account/releases")
@RequiredArgsConstructor
public class OpsEarningsReleaseController {
    private final EarningsReleaseService service;

    @GetMapping
    @PreAuthorize("hasAuthority('risk_k1_read')")
    public ApiResult<Map<String, Object>> entries(
            @RequestParam(value = "userId", required = false) Long userId,
            @RequestParam(value = "clusterId", required = false) String clusterId,
            @RequestParam(value = "limit", required = false) Integer limit) {
        return service.protectedEntries(userId, clusterId, limit);
    }

    @PostMapping("/{entryNo}/manual")
    @PreAuthorize("hasAuthority('risk_k1_cluster_release')")
    public ApiResult<Map<String, Object>> release(
            @PathVariable String entryNo,
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody EarningsManualReleaseRequest request) {
        return service.manualRelease(entryNo, idempotencyKey, request);
    }
}
