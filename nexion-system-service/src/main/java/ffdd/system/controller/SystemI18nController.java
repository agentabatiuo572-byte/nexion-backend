package ffdd.system.controller;

import ffdd.common.api.ApiResult;
import ffdd.system.dto.I18nBatchQueryRequest;
import ffdd.system.dto.I18nMessageCreateRequest;
import ffdd.system.dto.I18nMessageResponse;
import ffdd.system.dto.I18nMessageUpdateRequest;
import ffdd.system.service.SystemI18nService;
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
@RequestMapping("/system/i18n/messages")
public class SystemI18nController {
    private final SystemI18nService systemI18nService;

    public SystemI18nController(SystemI18nService systemI18nService) {
        this.systemI18nService = systemI18nService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('PERM_SYSTEM_READ')")
    public ApiResult<List<I18nMessageResponse>> list(
            @RequestParam(required = false) String locale,
            @RequestParam(required = false) String query,
            @RequestParam(required = false) Integer status,
            @RequestParam(defaultValue = "20") int limit) {
        return ApiResult.ok(systemI18nService.list(locale, query, status, limit));
    }

    @GetMapping("/{messageKey}")
    @PreAuthorize("hasAuthority('PERM_SYSTEM_READ')")
    public ApiResult<I18nMessageResponse> getActive(
            @PathVariable String messageKey,
            @RequestParam String locale) {
        return ApiResult.ok(systemI18nService.getActive(messageKey, locale));
    }

    @PostMapping("/batch-query")
    @PreAuthorize("hasAuthority('PERM_SYSTEM_READ')")
    public ApiResult<List<I18nMessageResponse>> batchQuery(@Valid @RequestBody I18nBatchQueryRequest request) {
        return ApiResult.ok(systemI18nService.batchGetActive(request));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('PERM_SYSTEM_WRITE')")
    public ApiResult<I18nMessageResponse> create(@Valid @RequestBody I18nMessageCreateRequest request) {
        return ApiResult.ok(systemI18nService.create(request));
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasAuthority('PERM_SYSTEM_WRITE')")
    public ApiResult<I18nMessageResponse> update(
            @PathVariable Long id,
            @Valid @RequestBody I18nMessageUpdateRequest request) {
        return ApiResult.ok(systemI18nService.update(id, request));
    }
}
