package ffdd.auth.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import ffdd.auth.domain.Admin;
import ffdd.auth.dto.AdminCreateRequest;
import ffdd.auth.dto.AdminQueryRequest;
import ffdd.auth.dto.AdminUpdateRequest;
import ffdd.auth.dto.AssignAdminRolesRequest;
import ffdd.auth.service.AdminService;
import ffdd.common.api.ApiResult;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/auth/admins")
@RequiredArgsConstructor
public class AdminController {
    private final AdminService adminService;

    @PostMapping
    @PreAuthorize("hasAuthority('PERM_ADMIN_WRITE')")
    public ApiResult<Admin> create(@Valid @RequestBody AdminCreateRequest request) {
        return ApiResult.ok(adminService.create(request));
    }

    @GetMapping("/page")
    @PreAuthorize("hasAuthority('PERM_ADMIN_READ')")
    public ApiResult<Page<Admin>> page(@ModelAttribute AdminQueryRequest query,
                                       @RequestParam(defaultValue = "1") long current,
                                       @RequestParam(defaultValue = "20") long size) {
        return ApiResult.ok(adminService.page(current, size, query));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('PERM_ADMIN_READ')")
    public ApiResult<Admin> detail(@PathVariable Long id) {
        return ApiResult.ok(adminService.detail(id));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('PERM_ADMIN_WRITE')")
    public ApiResult<Admin> update(@PathVariable Long id, @Valid @RequestBody AdminUpdateRequest request) {
        return ApiResult.ok(adminService.update(id, request));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('PERM_ADMIN_WRITE')")
    public ApiResult<Void> delete(@PathVariable Long id) {
        adminService.delete(id);
        return ApiResult.ok();
    }

    @PutMapping("/{id}/roles")
    @PreAuthorize("hasAuthority('PERM_ADMIN_ROLE_ASSIGN')")
    public ApiResult<Void> assignRoles(@PathVariable Long id, @Valid @RequestBody AssignAdminRolesRequest request) {
        adminService.assignRoles(id, request.getRoleIds());
        return ApiResult.ok();
    }
}

