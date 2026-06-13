package ffdd.auth.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import ffdd.auth.domain.MakerCheckerTask;
import ffdd.auth.dto.MakerCheckerCreateRequest;
import ffdd.auth.dto.MakerCheckerReviewRequest;
import ffdd.auth.service.MakerCheckerService;
import ffdd.common.api.ApiResult;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/auth/maker-checker")
@RequiredArgsConstructor
public class MakerCheckerController {
    private final MakerCheckerService makerCheckerService;

    @GetMapping("/tasks")
    @PreAuthorize("hasAnyAuthority('PERM_ADMIN_READ','PERM_ROLE_READ')")
    public ApiResult<Page<MakerCheckerTask>> page(
            @RequestParam(defaultValue = "1") long current,
            @RequestParam(defaultValue = "20") long size,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String resourceType) {
        return ApiResult.ok(makerCheckerService.page(current, size, status, resourceType));
    }

    @PostMapping("/tasks")
    @PreAuthorize("hasAnyAuthority('PERM_ADMIN_WRITE','PERM_ROLE_PERMISSION_ASSIGN')")
    public ApiResult<MakerCheckerTask> create(@Valid @RequestBody MakerCheckerCreateRequest request) {
        return ApiResult.ok(makerCheckerService.create(request));
    }

    @PostMapping("/tasks/{id}/approve")
    @PreAuthorize("hasAnyAuthority('PERM_ADMIN_WRITE','PERM_ROLE_PERMISSION_ASSIGN')")
    public ApiResult<MakerCheckerTask> approve(@PathVariable Long id, @Valid @RequestBody MakerCheckerReviewRequest request) {
        return ApiResult.ok(makerCheckerService.approve(id, request));
    }

    @PostMapping("/tasks/{id}/reject")
    @PreAuthorize("hasAnyAuthority('PERM_ADMIN_WRITE','PERM_ROLE_PERMISSION_ASSIGN')")
    public ApiResult<MakerCheckerTask> reject(@PathVariable Long id, @Valid @RequestBody MakerCheckerReviewRequest request) {
        return ApiResult.ok(makerCheckerService.reject(id, request));
    }
}
