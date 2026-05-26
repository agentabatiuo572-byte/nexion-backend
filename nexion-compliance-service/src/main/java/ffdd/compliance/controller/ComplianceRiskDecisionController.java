package ffdd.compliance.controller;

import ffdd.common.api.ApiResult;
import ffdd.common.audit.AuditLogService;
import ffdd.common.audit.AuditLogWriteRequest;
import ffdd.compliance.domain.RiskDecision;
import ffdd.compliance.dto.ManualRiskReviewRequest;
import ffdd.compliance.dto.RiskDecisionSummaryResponse;
import ffdd.compliance.service.ComplianceGateService;
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
@RequestMapping("/compliance/risk-decisions")
public class ComplianceRiskDecisionController {
    private final ComplianceGateService complianceGateService;
    private final ComplianceRiskOpsService riskOpsService;
    private final AuditLogService auditLogService;

    public ComplianceRiskDecisionController(
            ComplianceGateService complianceGateService,
            ComplianceRiskOpsService riskOpsService,
            AuditLogService auditLogService) {
        this.complianceGateService = complianceGateService;
        this.riskOpsService = riskOpsService;
        this.auditLogService = auditLogService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('PERM_COMPLIANCE_READ')")
    public ApiResult<List<RiskDecision>> list(
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) String bizType,
            @RequestParam(required = false) String decision,
            @RequestParam(required = false) String reason,
            @RequestParam(defaultValue = "20") int limit) {
        return ApiResult.ok(riskOpsService.listRiskDecisions(userId, bizType, decision, reason, limit));
    }

    @GetMapping("/summary")
    @PreAuthorize("hasAuthority('PERM_COMPLIANCE_READ')")
    public ApiResult<RiskDecisionSummaryResponse> summary(@RequestParam(defaultValue = "7") int days) {
        return ApiResult.ok(riskOpsService.summarize(days));
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
        RiskDecision decision = complianceGateService.approveDecision(decisionNo, request);
        auditDecision("RISK_DECISION_APPROVE", decision);
        return ApiResult.ok(decision);
    }

    @PostMapping("/{decisionNo}/reject")
    @PreAuthorize("hasAuthority('PERM_COMPLIANCE_WRITE')")
    public ApiResult<RiskDecision> reject(
            @PathVariable String decisionNo,
            @Valid @RequestBody ManualRiskReviewRequest request) {
        RiskDecision decision = complianceGateService.rejectDecision(decisionNo, request);
        auditDecision("RISK_DECISION_REJECT", decision);
        return ApiResult.ok(decision);
    }

    private void auditDecision(String action, RiskDecision decision) {
        auditLogService.record(AuditLogWriteRequest.builder()
                .action(action)
                .resourceType("RISK_DECISION")
                .resourceId(decision.getDecisionNo())
                .bizNo(decision.getBizNo())
                .userId(decision.getUserId())
                .riskLevel("HIGH")
                .detail(detail(
                        "decision", decision.getDecision(),
                        "reason", decision.getReason(),
                        "bizType", decision.getBizType(),
                        "riskScore", decision.getRiskScore(),
                        "ruleCodes", decision.getRuleCodes(),
                        "reviewedBy", decision.getReviewedBy(),
                        "reviewedAt", decision.getReviewedAt()))
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
