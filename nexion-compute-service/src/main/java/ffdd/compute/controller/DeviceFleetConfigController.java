package ffdd.compute.controller;

import ffdd.common.api.ApiResult;
import ffdd.compute.dto.DeviceFleetConfigResponse;
import ffdd.compute.service.DeviceFleetConfigService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/config")
public class DeviceFleetConfigController {
    private final DeviceFleetConfigService deviceFleetConfigService;

    public DeviceFleetConfigController(DeviceFleetConfigService deviceFleetConfigService) {
        this.deviceFleetConfigService = deviceFleetConfigService;
    }

    @GetMapping("/device-fleet")
    public ApiResult<DeviceFleetConfigResponse> deviceFleet() {
        return ApiResult.ok(deviceFleetConfigService.currentConfig());
    }
}
