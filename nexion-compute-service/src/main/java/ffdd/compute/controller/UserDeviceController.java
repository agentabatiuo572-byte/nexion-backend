package ffdd.compute.controller;

import ffdd.common.api.ApiResult;
import ffdd.common.api.PageResult;
import ffdd.common.exception.BizException;
import ffdd.compute.domain.UserDevice;
import ffdd.compute.dto.DeviceActivateRequest;
import ffdd.compute.dto.DeviceStatusResponse;
import ffdd.compute.dto.DeviceQueryRequest;
import ffdd.compute.dto.UserDeviceResponse;
import ffdd.compute.service.ComputeService;
import ffdd.compute.service.DeviceLifecycleService;
import ffdd.compute.service.DeviceStatusService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.util.StringUtils;
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
    private final DeviceLifecycleService lifecycleService;
    private final DeviceStatusService deviceStatusService;

    public UserDeviceController(
            ComputeService computeService,
            DeviceLifecycleService lifecycleService,
            DeviceStatusService deviceStatusService) {
        this.computeService = computeService;
        this.lifecycleService = lifecycleService;
        this.deviceStatusService = deviceStatusService;
    }

    @GetMapping
    public ApiResult<PageResult<UserDeviceResponse>> page(DeviceQueryRequest request) {
        Long roleUserId = currentRoleUserId();
        if (roleUserId != null) {
            request.setUserId(roleUserId);
        }
        PageResult<UserDevice> page = computeService.pageDevices(request);
        return ApiResult.ok(new PageResult<>(
                page.getTotal(),
                page.getPageNum(),
                page.getPageSize(),
                page.getRecords().stream()
                        .map(this::toResponse)
                        .toList()));
    }

    @GetMapping("/{id}")
    public ApiResult<UserDeviceResponse> detail(@PathVariable Long id) {
        UserDevice device = computeService.getDevice(id);
        Long roleUserId = currentRoleUserId();
        if (roleUserId != null && !roleUserId.equals(device.getUserId())) {
            throw new BizException("Device does not belong to authenticated user");
        }
        return ApiResult.ok(toResponse(device));
    }

    @PostMapping("/activate")
    public ApiResult<List<UserDevice>> activate(@Valid @RequestBody DeviceActivateRequest request) {
        return ApiResult.ok(computeService.activateDevices(request));
    }

    @PostMapping("/{id}/activate")
    @PreAuthorize("hasAuthority('PERM_COMPUTE_WRITE') or hasAuthority('ROLE_USER')")
    public ApiResult<UserDeviceResponse> activateOne(@PathVariable Long id) {
        UserDevice device = computeService.activateDevice(id, currentRoleUserId());
        return ApiResult.ok(toResponse(device));
    }

    @PostMapping("/{id}/deactivate")
    @PreAuthorize("hasAuthority('PERM_COMPUTE_WRITE') or hasAuthority('ROLE_USER')")
    public ApiResult<UserDeviceResponse> deactivate(@PathVariable Long id) {
        UserDevice device = computeService.deactivateDevice(id, currentRoleUserId());
        return ApiResult.ok(toResponse(device));
    }

    @PostMapping("/{id}/deactivation-schedule")
    @PreAuthorize("hasAuthority('PERM_COMPUTE_WRITE') or hasAuthority('ROLE_USER')")
    public ApiResult<UserDeviceResponse> scheduleDeactivation(@PathVariable Long id) {
        UserDevice device = computeService.scheduleDeactivation(id, currentRoleUserId());
        return ApiResult.ok(toResponse(device));
    }

    private UserDeviceResponse toResponse(UserDevice device) {
        DeviceStatusResponse runtime = deviceStatusService.getStatus(device.getId());
        return UserDeviceResponse.from(
                device,
                lifecycleService.evaluate(device),
                runtime,
                computeService.currentTaskForDevice(device.getId()));
    }

    private Long currentRoleUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || authentication.getAuthorities().stream().noneMatch(a -> "ROLE_USER".equals(a.getAuthority()))) {
            return null;
        }
        String subject = String.valueOf(authentication.getPrincipal());
        if (!StringUtils.hasText(subject)) {
            return null;
        }
        try {
            return Long.valueOf(subject);
        } catch (NumberFormatException ignored) {
            throw new BizException("Authenticated user id is invalid");
        }
    }
}
