package ffdd.system.controller;

import ffdd.common.api.ApiResult;
import ffdd.system.dto.ConfigBatchQueryRequest;
import ffdd.system.dto.ConfigItemCreateRequest;
import ffdd.system.dto.ConfigItemResponse;
import ffdd.system.dto.ConfigItemUpdateRequest;
import ffdd.system.service.SystemConfigService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/system/configs")
public class SystemConfigController {
    private final SystemConfigService systemConfigService;

    public SystemConfigController(SystemConfigService systemConfigService) {
        this.systemConfigService = systemConfigService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('PERM_SYSTEM_READ')")
    public ApiResult<List<ConfigItemResponse>> list(
            @RequestParam(required = false) String query,
            @RequestParam(required = false) Integer status,
            @RequestParam(defaultValue = "20") int limit) {
        return ApiResult.ok(systemConfigService.list(query, status, limit));
    }

    @GetMapping("/{configKey}")
    @PreAuthorize("hasAuthority('PERM_SYSTEM_READ')")
    public ApiResult<ConfigItemResponse> getActive(@PathVariable String configKey) {
        return ApiResult.ok(systemConfigService.getActiveByKey(configKey));
    }

    @PostMapping("/batch-query")
    @PreAuthorize("hasAuthority('PERM_SYSTEM_READ')")
    public ApiResult<List<ConfigItemResponse>> batchQuery(@Valid @RequestBody ConfigBatchQueryRequest request) {
        return ApiResult.ok(systemConfigService.batchGetActive(request.getConfigKeys()));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('PERM_SYSTEM_WRITE')")
    public ApiResult<ConfigItemResponse> create(@Valid @RequestBody ConfigItemCreateRequest request) {
        return ApiResult.ok(systemConfigService.create(request));
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasAuthority('PERM_SYSTEM_WRITE')")
    public ApiResult<ConfigItemResponse> update(
            @PathVariable Long id,
            @Valid @RequestBody ConfigItemUpdateRequest request) {
        return ApiResult.ok(systemConfigService.update(id, request));
    }
}
