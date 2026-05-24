package ffdd.compute.controller;

import ffdd.common.api.ApiResult;
import ffdd.compute.dto.DeviceStatusResponse;
import ffdd.compute.dto.DeviceStatusUpdateRequest;
import ffdd.compute.dto.NodeMapResponse;
import ffdd.compute.service.DeviceStatusService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/compute/devices")
public class DeviceStatusController {
    private final DeviceStatusService deviceStatusService;

    public DeviceStatusController(DeviceStatusService deviceStatusService) {
        this.deviceStatusService = deviceStatusService;
    }

    @PatchMapping("/{id}/status")
    @PreAuthorize("hasAuthority('PERM_COMPUTE_WRITE')")
    public ApiResult<DeviceStatusResponse> reportStatus(
            @PathVariable Long id,
            @Valid @RequestBody DeviceStatusUpdateRequest request) {
        return ApiResult.ok(deviceStatusService.reportStatus(id, request));
    }

    @GetMapping("/{id}/status")
    public ApiResult<DeviceStatusResponse> status(@PathVariable Long id) {
        return ApiResult.ok(deviceStatusService.getStatus(id));
    }

    @GetMapping("/node-map")
    public ApiResult<NodeMapResponse> nodeMap(@RequestParam(defaultValue = "100") int limit) {
        return ApiResult.ok(deviceStatusService.nodeMap(limit));
    }
}
