package ffdd.opsconsole.device.web;

import ffdd.opsconsole.common.api.OpsAdminApi;
import ffdd.opsconsole.device.application.OpsDeviceService;
import ffdd.opsconsole.device.dto.E2PhoneTierConfigUpdateRequest;
import ffdd.opsconsole.device.dto.E2TaskPricingUpdateRequest;
import ffdd.opsconsole.shared.api.ApiResult;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Exact E2 PRD contract under /api/admin/config. */
@RestController
@RequestMapping(OpsAdminApi.ADMIN_PREFIX + "/config")
@RequiredArgsConstructor
public class OpsDeviceConfigController {
    private final OpsDeviceService deviceService;

    @GetMapping("/task-pricing")
    @PreAuthorize("hasAuthority('device_e2_read')")
    public ApiResult<Map<String, Object>> taskPricing() {
        return deviceService.e2TaskPricing();
    }

    @PutMapping("/task-pricing")
    @PreAuthorize("hasAuthority('device_e2_write')")
    public ApiResult<Map<String, Object>> updateTaskPricing(
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody E2TaskPricingUpdateRequest request) {
        return deviceService.updateE2TaskPricing(idempotencyKey, request);
    }

    @GetMapping("/phone-tiers")
    @PreAuthorize("hasAuthority('device_e2_read')")
    public ApiResult<Map<String, Object>> phoneTiers() {
        return deviceService.e2PhoneTiers();
    }

    @PutMapping("/phone-tiers")
    @PreAuthorize("hasAnyAuthority('device_e2_phone_tier_usdt','device_e2_phone_tier_nex')")
    public ApiResult<Map<String, Object>> updatePhoneTier(
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody E2PhoneTierConfigUpdateRequest request) {
        return deviceService.updateE2PhoneTier(idempotencyKey, request);
    }
}
