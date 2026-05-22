package ffdd.auth.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import ffdd.auth.domain.AdminMenu;
import ffdd.auth.domain.AdminPermission;
import ffdd.auth.domain.AdminRole;
import ffdd.auth.dto.MenuCreateRequest;
import ffdd.auth.dto.MenuQueryRequest;
import ffdd.auth.dto.MenuUpdateRequest;
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

    AdminMenu createMenu(MenuCreateRequest request);

    List<AdminMenu> menuList(MenuQueryRequest query);

    AdminMenu updateMenu(Long id, MenuUpdateRequest request);

    void deleteMenu(Long id);

    AdminPermission createPermission(PermissionCreateRequest request);

    Page<AdminPermission> permissionPage(long current, long size, PermissionQueryRequest query);

    AdminPermission updatePermission(Long id, PermissionUpdateRequest request);

    void deletePermission(Long id);

    List<Long> permissionIds(Long roleId);

    List<Long> menuIds(Long roleId);

    void assignMenus(Long roleId, List<Long> menuIds);

    void assignApiPermissions(Long roleId, List<Long> permissionIds);
}
