package ffdd.compute.controller;

import ffdd.common.api.ApiResult;
import ffdd.compute.domain.DeviceLifecycleRule;
import ffdd.compute.dto.DeviceLifecycleResponse;
import ffdd.compute.dto.DeviceLifecycleRuleCreateRequest;
import ffdd.compute.dto.DeviceLifecycleRuleUpdateRequest;
import ffdd.compute.service.DeviceLifecycleService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class DeviceLifecycleController {
    private final DeviceLifecycleService lifecycleService;

    public DeviceLifecycleController(DeviceLifecycleService lifecycleService) {
        this.lifecycleService = lifecycleService;
    }

    @GetMapping("/compute/devices/{id}/lifecycle")
    public ApiResult<DeviceLifecycleResponse> lifecycle(@PathVariable Long id) {
        return ApiResult.ok(lifecycleService.lifecycle(id));
    }

    @GetMapping("/config/device-lifecycle")
    public ApiResult<Map<String, Object>> publicConfig() {
        return ApiResult.ok(Map.of("rules", lifecycleService.listRules(1)));
    }

    @GetMapping("/compute/device-lifecycle/rules")
    @PreAuthorize("hasAuthority('PERM_COMPUTE_READ')")
    public ApiResult<List<DeviceLifecycleRule>> rules(@RequestParam(required = false) Integer status) {
        return ApiResult.ok(lifecycleService.listRules(status));
    }

    @PostMapping("/compute/device-lifecycle/rules")
    @PreAuthorize("hasAuthority('PERM_COMPUTE_WRITE')")
    public ApiResult<DeviceLifecycleRule> createRule(@Valid @RequestBody DeviceLifecycleRuleCreateRequest request) {
        return ApiResult.ok(lifecycleService.createRule(request));
    }

    @PatchMapping("/compute/device-lifecycle/rules/{id}")
    @PreAuthorize("hasAuthority('PERM_COMPUTE_WRITE')")
    public ApiResult<DeviceLifecycleRule> updateRule(
            @PathVariable Long id,
            @Valid @RequestBody DeviceLifecycleRuleUpdateRequest request) {
        return ApiResult.ok(lifecycleService.updateRule(id, request));
    }
}
