package ffdd.opsconsole.device.web;

import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.api.PageResult;
import ffdd.opsconsole.common.api.OpsAdminApi;
import ffdd.opsconsole.device.application.OpsDeviceService;
import ffdd.opsconsole.device.domain.DeviceOpsView;
import ffdd.opsconsole.device.dto.DatacenterOpsRequest;
import ffdd.opsconsole.device.dto.DeviceOpsQueryRequest;
import ffdd.opsconsole.device.dto.DeviceRestoreRequest;
import ffdd.opsconsole.device.dto.E3ConfigUpdateRequest;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(OpsAdminApi.ADMIN_PREFIX + "/devices")
public class OpsDeviceController {
    private final OpsDeviceService deviceService;

    public OpsDeviceController(OpsDeviceService deviceService) {
        this.deviceService = deviceService;
    }

    @GetMapping("/overview")
    public ApiResult<Map<String, Object>> overview() {
        return deviceService.overview();
    }

    @GetMapping
    public ApiResult<PageResult<DeviceOpsView>> devices(DeviceOpsQueryRequest request) {
        return deviceService.devices(request);
    }

    @GetMapping("/e3/overview")
    public ApiResult<Map<String, Object>> e3Overview() {
        return deviceService.e3Overview();
    }

    @PatchMapping("/e3/config")
    public ApiResult<Map<String, Object>> updateE3Config(
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody E3ConfigUpdateRequest request) {
        return deviceService.updateE3Config(idempotencyKey, request);
    }

    @PostMapping("/{deviceId}/restore")
    public ApiResult<DeviceOpsView> restoreDevice(
            @PathVariable Long deviceId,
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody DeviceRestoreRequest request) {
        return deviceService.restoreDevice(deviceId, idempotencyKey, request);
    }

    @PostMapping("/datacenters/{dcLocation}/pause")
    public ApiResult<Map<String, Object>> pauseDatacenter(
            @PathVariable String dcLocation,
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody DatacenterOpsRequest request) {
        return deviceService.pauseDatacenter(dcLocation, idempotencyKey, request);
    }

    @PostMapping("/datacenters/{dcLocation}/resume")
    public ApiResult<Map<String, Object>> resumeDatacenter(
            @PathVariable String dcLocation,
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody DatacenterOpsRequest request) {
        return deviceService.resumeDatacenter(dcLocation, idempotencyKey, request);
    }
}
