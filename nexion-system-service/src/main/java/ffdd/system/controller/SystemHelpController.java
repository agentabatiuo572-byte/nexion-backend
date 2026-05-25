package ffdd.system.controller;

import ffdd.common.api.ApiResult;
import ffdd.system.dto.HelpArticleCreateRequest;
import ffdd.system.dto.HelpArticleResponse;
import ffdd.system.dto.HelpArticleUpdateRequest;
import ffdd.system.service.SystemHelpService;
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
@RequestMapping("/system/help/articles")
public class SystemHelpController {
    private final SystemHelpService systemHelpService;

    public SystemHelpController(SystemHelpService systemHelpService) {
        this.systemHelpService = systemHelpService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('PERM_SYSTEM_READ')")
    public ApiResult<List<HelpArticleResponse>> list(
            @RequestParam(required = false) String query,
            @RequestParam(required = false) Integer status,
            @RequestParam(defaultValue = "20") int limit) {
        return ApiResult.ok(systemHelpService.list(query, status, limit));
    }

    @GetMapping("/{articleCode}")
    @PreAuthorize("hasAuthority('PERM_SYSTEM_READ')")
    public ApiResult<HelpArticleResponse> getActive(@PathVariable String articleCode) {
        return ApiResult.ok(systemHelpService.getActiveByCode(articleCode));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('PERM_SYSTEM_WRITE')")
    public ApiResult<HelpArticleResponse> create(@Valid @RequestBody HelpArticleCreateRequest request) {
        return ApiResult.ok(systemHelpService.create(request));
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasAuthority('PERM_SYSTEM_WRITE')")
    public ApiResult<HelpArticleResponse> update(
            @PathVariable Long id,
            @Valid @RequestBody HelpArticleUpdateRequest request) {
        return ApiResult.ok(systemHelpService.update(id, request));
    }
}
