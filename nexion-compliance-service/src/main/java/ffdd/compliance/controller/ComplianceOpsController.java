package ffdd.compliance.controller;

import ffdd.common.api.ApiResult;
import ffdd.compliance.service.ComplianceRiskOpsService;
import java.util.List;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/compliance")
public class ComplianceOpsController {
    private final ComplianceRiskOpsService riskOpsService;

    public ComplianceOpsController(ComplianceRiskOpsService riskOpsService) {
        this.riskOpsService = riskOpsService;
    }

    @GetMapping("/ops/overview")
    public ApiResult<Map<String, Object>> overview() {
        return ApiResult.ok(Map.of(
                "service", "nexion-compliance-service",
                "database", "nexion_compliance",
                "responsibilities", List.of("KYC", "risk decisions", "blacklist", "manual review",
                        "risk scoring", "withdrawal checks", "exchange checks", "proof assets"),
                "gate", "/compliance/gates/check",
                "kycProfiles", "/compliance/kyc-profiles",
                "proofAssets", "/compliance/proof-assets",
                "riskDecisionSummary", "/compliance/risk-decisions/summary",
                "blacklists", "/compliance/blacklists"));
    }

    @GetMapping("/ops/stats")
    @PreAuthorize("hasAuthority('PERM_COMPLIANCE_READ')")
    public ApiResult<Map<String, Object>> stats(@RequestParam(defaultValue = "7") int days) {
        return ApiResult.ok(riskOpsService.opsStats(days));
    }
}
