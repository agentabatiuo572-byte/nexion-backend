package ffdd.system.controller;

import ffdd.common.api.ApiResult;
import ffdd.system.dto.ContentPageCreateRequest;
import ffdd.system.dto.ContentPageResponse;
import ffdd.system.dto.ContentPageUpdateRequest;
import ffdd.system.service.SystemContentService;
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
@RequestMapping("/system/content/pages")
public class SystemContentController {
    private final SystemContentService systemContentService;

    public SystemContentController(SystemContentService systemContentService) {
        this.systemContentService = systemContentService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('PERM_SYSTEM_READ')")
    public ApiResult<List<ContentPageResponse>> list(
            @RequestParam(required = false) String query,
            @RequestParam(required = false) Integer status,
            @RequestParam(defaultValue = "20") int limit) {
        return ApiResult.ok(systemContentService.list(query, status, limit));
    }

    @GetMapping("/{pageCode}")
    @PreAuthorize("hasAuthority('PERM_SYSTEM_READ')")
    public ApiResult<ContentPageResponse> getActive(@PathVariable String pageCode) {
        return ApiResult.ok(systemContentService.getActiveByCode(pageCode));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('PERM_SYSTEM_WRITE')")
    public ApiResult<ContentPageResponse> create(@Valid @RequestBody ContentPageCreateRequest request) {
        return ApiResult.ok(systemContentService.create(request));
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasAuthority('PERM_SYSTEM_WRITE')")
    public ApiResult<ContentPageResponse> update(
            @PathVariable Long id,
            @Valid @RequestBody ContentPageUpdateRequest request) {
        return ApiResult.ok(systemContentService.update(id, request));
    }
}
