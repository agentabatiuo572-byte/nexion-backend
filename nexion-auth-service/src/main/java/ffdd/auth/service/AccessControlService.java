package ffdd.auth.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import ffdd.auth.domain.AdminPermission;
import ffdd.auth.domain.AdminRole;
import ffdd.auth.dto.PermissionCreateRequest;
import ffdd.auth.dto.PermissionQueryRequest;
import ffdd.auth.dto.PermissionUpdateRequest;
import ffdd.auth.dto.RoleCreateRequest;
import ffdd.auth.dto.RoleQueryRequest;
import ffdd.auth.dto.RoleUpdateRequest;
import java.util.List;

public interface AccessControlService {
    AdminRole createRole(RoleCreateRequest request);

    Page<AdminRole> rolePage(long current, long size, RoleQueryRequest query);

    AdminRole updateRole(Long id, RoleUpdateRequest request);

    void deleteRole(Long id);

    AdminPermission createPermission(PermissionCreateRequest request);

    Page<AdminPermission> permissionPage(long current, long size, PermissionQueryRequest query);

    AdminPermission updatePermission(Long id, PermissionUpdateRequest request);

    void deletePermission(Long id);

    void assignPermissions(Long roleId, List<Long> permissionIds);
}

