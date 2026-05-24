package ffdd.compliance.controller;

import ffdd.common.api.ApiResult;
import ffdd.compliance.domain.RiskDecision;
import ffdd.compliance.dto.ManualRiskReviewRequest;
import ffdd.compliance.service.ComplianceGateService;
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
@RequestMapping("/compliance/risk-decisions")
public class ComplianceRiskDecisionController {
    private final ComplianceGateService complianceGateService;

    public ComplianceRiskDecisionController(ComplianceGateService complianceGateService) {
        this.complianceGateService = complianceGateService;
    }

    @GetMapping("/review")
    @PreAuthorize("hasAuthority('PERM_COMPLIANCE_READ')")
    public ApiResult<List<RiskDecision>> review(@RequestParam(defaultValue = "20") int limit) {
        return ApiResult.ok(complianceGateService.listReview(limit));
    }

    @PostMapping("/{decisionNo}/approve")
    @PreAuthorize("hasAuthority('PERM_COMPLIANCE_WRITE')")
    public ApiResult<RiskDecision> approve(
            @PathVariable String decisionNo,
            @Valid @RequestBody ManualRiskReviewRequest request) {
        return ApiResult.ok(complianceGateService.approveDecision(decisionNo, request));
    }

    @PostMapping("/{decisionNo}/reject")
    @PreAuthorize("hasAuthority('PERM_COMPLIANCE_WRITE')")
    public ApiResult<RiskDecision> reject(
            @PathVariable String decisionNo,
            @Valid @RequestBody ManualRiskReviewRequest request) {
        return ApiResult.ok(complianceGateService.rejectDecision(decisionNo, request));
    }
}
