package ffdd.system.controller;

import ffdd.common.api.ApiResult;
import ffdd.system.dto.EmergencySopStatusRequest;
import ffdd.system.dto.EmergencySopStepResponse;
import ffdd.system.dto.EmergencyTamperGateResponse;
import ffdd.system.dto.EmergencyTamperReviewRequest;
import ffdd.system.service.EmergencyOpsService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/system/emergency")
public class EmergencyOpsController {
    private final EmergencyOpsService emergencyOpsService;

    public EmergencyOpsController(EmergencyOpsService emergencyOpsService) {
        this.emergencyOpsService = emergencyOpsService;
    }

    @GetMapping("/tamper-gates")
    @PreAuthorize("hasAuthority('PERM_SYSTEM_READ')")
    public ApiResult<List<EmergencyTamperGateResponse>> listTamperGates() {
        return ApiResult.ok(emergencyOpsService.listTamperGates());
    }

    @PostMapping("/tamper-gates/{gateKey}/review")
    @PreAuthorize("hasAuthority('PERM_SYSTEM_WRITE')")
    public ApiResult<EmergencyTamperGateResponse> reviewTamperGate(
            @PathVariable String gateKey,
            @Valid @RequestBody EmergencyTamperReviewRequest request) {
        return ApiResult.ok(emergencyOpsService.reviewTamperGate(gateKey, request));
    }

    @GetMapping("/sop-steps")
    @PreAuthorize("hasAuthority('PERM_SYSTEM_READ')")
    public ApiResult<List<EmergencySopStepResponse>> listSopSteps() {
        return ApiResult.ok(emergencyOpsService.listSopSteps());
    }

    @PostMapping("/sop-steps/{sopId}/status")
    @PreAuthorize("hasAuthority('PERM_SYSTEM_WRITE')")
    public ApiResult<EmergencySopStepResponse> updateSopStepStatus(
            @PathVariable String sopId,
            @Valid @RequestBody EmergencySopStatusRequest request) {
        return ApiResult.ok(emergencyOpsService.updateSopStepStatus(sopId, request));
    }
}
