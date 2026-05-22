package ffdd.compute.controller;

import ffdd.common.api.ApiResult;
import ffdd.common.api.PageResult;
import ffdd.compute.domain.UserDevice;
import ffdd.compute.dto.DeviceActivateRequest;
import ffdd.compute.dto.DeviceQueryRequest;
import ffdd.compute.service.ComputeService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/compute/devices")
public class UserDeviceController {
    private final ComputeService computeService;

    public UserDeviceController(ComputeService computeService) {
        this.computeService = computeService;
    }

    @GetMapping
    public ApiResult<PageResult<UserDevice>> page(DeviceQueryRequest request) {
        return ApiResult.ok(computeService.pageDevices(request));
    }

    @GetMapping("/{id}")
    public ApiResult<UserDevice> detail(@PathVariable Long id) {
        return ApiResult.ok(computeService.getDevice(id));
    }

    @PostMapping("/activate")
    public ApiResult<List<UserDevice>> activate(@Valid @RequestBody DeviceActivateRequest request) {
        return ApiResult.ok(computeService.activateDevices(request));
    }
}
