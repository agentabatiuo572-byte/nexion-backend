package ffdd.compliance.controller;

import ffdd.common.api.ApiResult;
import ffdd.compliance.dto.ComplianceGateRequest;
import ffdd.compliance.dto.ComplianceGateResponse;
import ffdd.compliance.service.ComplianceGateService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/compliance/gates")
public class ComplianceGateController {
    private final ComplianceGateService complianceGateService;

    public ComplianceGateController(ComplianceGateService complianceGateService) {
        this.complianceGateService = complianceGateService;
    }

    @PostMapping("/check")
    @PreAuthorize("hasAuthority('PERM_COMPLIANCE_WRITE')")
    public ApiResult<ComplianceGateResponse> check(@Valid @RequestBody ComplianceGateRequest request) {
        return ApiResult.ok(complianceGateService.check(request));
    }
}
