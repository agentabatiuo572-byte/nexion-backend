package ffdd.compliance.controller;

import ffdd.common.api.ApiResult;
import ffdd.compliance.domain.RiskBlacklist;
import ffdd.compliance.dto.RiskBlacklistReleaseRequest;
import ffdd.compliance.dto.RiskBlacklistUpsertRequest;
import ffdd.compliance.service.ComplianceRiskOpsService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/compliance/blacklists")
public class ComplianceBlacklistController {
    private final ComplianceRiskOpsService riskOpsService;

    public ComplianceBlacklistController(ComplianceRiskOpsService riskOpsService) {
        this.riskOpsService = riskOpsService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('PERM_COMPLIANCE_READ')")
    public ApiResult<List<RiskBlacklist>> list(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "20") int limit) {
        return ApiResult.ok(riskOpsService.listBlacklists(status, limit));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('PERM_COMPLIANCE_WRITE')")
    public ApiResult<RiskBlacklist> upsert(@Valid @RequestBody RiskBlacklistUpsertRequest request) {
        return ApiResult.ok(riskOpsService.upsertBlacklist(request));
    }

    @PostMapping("/{userId}/release")
    @PreAuthorize("hasAuthority('PERM_COMPLIANCE_WRITE')")
    public ApiResult<RiskBlacklist> release(
            @PathVariable Long userId,
            @Valid @RequestBody RiskBlacklistReleaseRequest request) {
        return ApiResult.ok(riskOpsService.releaseBlacklist(userId, request));
    }
}
