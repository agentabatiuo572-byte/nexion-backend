package ffdd.opsconsole.device.web;

import ffdd.opsconsole.device.application.OpsDeviceService;
import ffdd.opsconsole.device.domain.PlatformComputeConfigView;
import ffdd.opsconsole.shared.api.ApiResult;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class PlatformComputeConfigController {
    private final OpsDeviceService deviceService;

    @GetMapping("/api/config/platform")
    public ApiResult<PlatformComputeConfigView> platformConfig() {
        return deviceService.platformComputeConfig();
    }
}
