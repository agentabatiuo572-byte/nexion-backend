package ffdd.opsconsole.device.web;

import ffdd.opsconsole.device.application.OpsDeviceService;
import ffdd.opsconsole.shared.api.ApiResult;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * App/H5 read-only projection of the operator-owned E2 task and phone-yield
 * configuration. Mutations remain under the authenticated admin controllers.
 */
@RestController
@RequiredArgsConstructor
public class AppE2ConfigController {
    private final OpsDeviceService deviceService;

    @GetMapping("/api/config/task-pricing")
    public ApiResult<Map<String, Object>> taskPricing() {
        return deviceService.e2TaskPricing();
    }

    @GetMapping("/api/config/phone-tiers")
    public ApiResult<Map<String, Object>> phoneTiers() {
        return deviceService.e2PhoneTiers();
    }

    @GetMapping("/api/tasks/route")
    public ApiResult<Map<String, Object>> routeTask(@RequestParam Integer deviceVramGb) {
        return deviceService.routeE2Task(deviceVramGb);
    }
}
