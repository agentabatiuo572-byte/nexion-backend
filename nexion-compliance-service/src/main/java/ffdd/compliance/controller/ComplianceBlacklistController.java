package ffdd.compliance.controller;

import ffdd.common.api.ApiResult;
import ffdd.common.audit.AuditLogService;
import ffdd.common.audit.AuditLogWriteRequest;
import ffdd.compliance.domain.RiskBlacklist;
import ffdd.compliance.dto.RiskBlacklistReleaseRequest;
import ffdd.compliance.dto.RiskBlacklistUpsertRequest;
import ffdd.compliance.service.ComplianceRiskOpsService;
import jakarta.validation.Valid;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
    private final AuditLogService auditLogService;

    public ComplianceBlacklistController(ComplianceRiskOpsService riskOpsService, AuditLogService auditLogService) {
        this.riskOpsService = riskOpsService;
        this.auditLogService = auditLogService;
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
        RiskBlacklist blacklist = riskOpsService.upsertBlacklist(request);
        auditBlacklist("RISK_BLACKLIST_UPSERT", blacklist);
        return ApiResult.ok(blacklist);
    }

    @PostMapping("/{userId}/release")
    @PreAuthorize("hasAuthority('PERM_COMPLIANCE_WRITE')")
    public ApiResult<RiskBlacklist> release(
            @PathVariable Long userId,
            @Valid @RequestBody RiskBlacklistReleaseRequest request) {
        RiskBlacklist blacklist = riskOpsService.releaseBlacklist(userId, request);
        auditBlacklist("RISK_BLACKLIST_RELEASE", blacklist);
        return ApiResult.ok(blacklist);
    }

    private void auditBlacklist(String action, RiskBlacklist blacklist) {
        auditLogService.record(AuditLogWriteRequest.builder()
                .action(action)
                .resourceType("RISK_BLACKLIST")
                .resourceId(String.valueOf(blacklist.getUserId()))
                .bizNo(String.valueOf(blacklist.getUserId()))
                .userId(blacklist.getUserId())
                .riskLevel("HIGH")
                .detail(detail(
                        "status", blacklist.getStatus(),
                        "source", blacklist.getSource(),
                        "riskLevel", blacklist.getRiskLevel(),
                        "expiresAt", blacklist.getExpiresAt(),
                        "createdBy", blacklist.getCreatedBy(),
                        "releasedBy", blacklist.getReleasedBy(),
                        "releasedAt", blacklist.getReleasedAt()))
                .build());
    }

    private Map<String, Object> detail(Object... pairs) {
        Map<String, Object> detail = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            Object value = pairs[i + 1];
            if (value != null) {
                detail.put(String.valueOf(pairs[i]), value);
            }
        }
        return detail;
    }
}
