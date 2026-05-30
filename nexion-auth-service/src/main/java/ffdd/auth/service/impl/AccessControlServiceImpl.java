package ffdd.auth.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import ffdd.auth.domain.AdminMenu;
import ffdd.auth.domain.AdminPermission;
import ffdd.auth.domain.AdminRole;
import ffdd.auth.domain.AdminRoleMenu;
import ffdd.auth.domain.AdminRolePermission;
import ffdd.auth.dto.MenuCreateRequest;
import ffdd.auth.dto.MenuQueryRequest;
import ffdd.auth.dto.MenuUpdateRequest;
import ffdd.auth.dto.PermissionCreateRequest;
import ffdd.auth.dto.PermissionQueryRequest;
import ffdd.auth.dto.PermissionUpdateRequest;
import ffdd.auth.dto.RoleCreateRequest;
import ffdd.auth.dto.RoleQueryRequest;
import ffdd.auth.dto.RoleUpdateRequest;
import ffdd.auth.mapper.AdminMenuMapper;
import ffdd.auth.mapper.AdminPermissionMapper;
import ffdd.auth.mapper.AdminRoleMapper;
import ffdd.auth.mapper.AdminRoleMenuMapper;
import ffdd.auth.mapper.AdminRolePermissionMapper;
import ffdd.auth.mapper.AdminRoleRelationMapper;
import ffdd.auth.service.AccessControlService;
import ffdd.common.exception.BizException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class AccessControlServiceImpl implements AccessControlService {
    private static final String SUPER_ADMIN_ROLE_CODE = "SUPER_ADMIN";

    private final AdminRoleMapper roleMapper;
    private final AdminMenuMapper menuMapper;
    private final AdminPermissionMapper permissionMapper;
    private final AdminRoleMenuMapper roleMenuMapper;
    private final AdminRolePermissionMapper rolePermissionMapper;
    private final AdminRoleRelationMapper roleRelationMapper;

    @Override
    public AdminRole createRole(RoleCreateRequest request) {
        AdminRole role = new AdminRole();
        BeanUtils.copyProperties(request, role);
        role.setIsDeleted(0);
        roleMapper.insert(role);
        return role;
    }

    @Override
    public Page<AdminRole> rolePage(long current, long size, RoleQueryRequest query) {
        LambdaQueryWrapper<AdminRole> wrapper = new LambdaQueryWrapper<>();
        if (query != null) {
            wrapper.eq(StringUtils.hasText(query.getRoleCode()), AdminRole::getRoleCode, query.getRoleCode())
                    .like(StringUtils.hasText(query.getRoleName()), AdminRole::getRoleName, query.getRoleName())
                    .eq(query.getStatus() != null, AdminRole::getStatus, query.getStatus());
        }
        return roleMapper.selectPage(Page.of(current, size), wrapper.orderByDesc(AdminRole::getId));
    }

    @Override
    public AdminRole updateRole(Long id, RoleUpdateRequest request) {
        AdminRole role = roleMapper.selectById(id);
        if (role == null) {
            throw new BizException("Role does not exist");
        }
        if (isSuperAdminRole(role)) {
            throw new BizException("Super admin role cannot be changed");
        }
        BeanUtils.copyProperties(request, role);
        roleMapper.updateById(role);
        return roleMapper.selectById(id);
    }

    @Override
    public void deleteRole(Long id) {
        AdminRole role = roleMapper.selectById(id);
        if (role == null) {
            throw new BizException("Role does not exist");
        }
        if (isSuperAdminRole(role)) {
            throw new BizException("Super admin role cannot be deleted");
        }
        rejectIfReferenced(roleRelationMapper.selectActiveAdminNamesByRoleId(id), "角色已分配给管理员：");
        rejectIfReferenced(roleMenuMapper.selectActiveMenuNamesByRoleId(id), "角色已分配菜单：");
        rejectIfReferenced(rolePermissionMapper.selectActivePermissionNamesByRoleId(id), "角色已分配API权限：");
        roleMapper.deleteById(id);
    }

    @Override
    public AdminMenu createMenu(MenuCreateRequest request) {
        AdminMenu menu = new AdminMenu();
        BeanUtils.copyProperties(request, menu);
        fillMenuNames(menu);
        menu.setIsDeleted(0);
        if (menu.getSortOrder() == null) {
            menu.setSortOrder(0);
        }
        menuMapper.insert(menu);
        return menu;
    }

    @Override
    public List<AdminMenu> menuList(MenuQueryRequest query) {
        LambdaQueryWrapper<AdminMenu> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(AdminMenu::getIsDeleted, 0);
        if (query != null) {
            wrapper.eq(StringUtils.hasText(query.getMenuCode()), AdminMenu::getMenuCode, query.getMenuCode())
                    .and(StringUtils.hasText(query.getMenuName()), name -> name
                            .like(AdminMenu::getMenuName, query.getMenuName())
                            .or()
                            .like(AdminMenu::getMenuNameZh, query.getMenuName())
                            .or()
                            .like(AdminMenu::getMenuNameEn, query.getMenuName()))
                    .like(StringUtils.hasText(query.getRoutePath()), AdminMenu::getRoutePath, query.getRoutePath())
                    .eq(query.getParentId() != null, AdminMenu::getParentId, query.getParentId())
                    .eq(query.getStatus() != null, AdminMenu::getStatus, query.getStatus());
        }
        return menuMapper.selectList(wrapper
                .orderByAsc(AdminMenu::getSortOrder)
                .orderByAsc(AdminMenu::getId));
    }

    @Override
    public AdminMenu updateMenu(Long id, MenuUpdateRequest request) {
        AdminMenu menu = menuMapper.selectById(id);
        if (menu == null) {
            throw new BizException("Menu does not exist");
        }
        BeanUtils.copyProperties(request, menu);
        fillMenuNames(menu);
        if (menu.getParentId() != null && menu.getParentId().equals(id)) {
            throw new BizException("Menu cannot use itself as parent");
        }
        menuMapper.updateById(menu);
        return menuMapper.selectById(id);
    }

    private void fillMenuNames(AdminMenu menu) {
        if (!StringUtils.hasText(menu.getMenuNameZh())) {
            menu.setMenuNameZh(menu.getMenuName());
        }
        if (!StringUtils.hasText(menu.getMenuNameEn())) {
            menu.setMenuNameEn(menu.getMenuName());
        }
        if (!StringUtils.hasText(menu.getMenuName())) {
            menu.setMenuName(StringUtils.hasText(menu.getMenuNameZh()) ? menu.getMenuNameZh() : menu.getMenuNameEn());
        }
    }

    @Override
    public void deleteMenu(Long id) {
        AdminMenu menu = menuMapper.selectById(id);
        if (menu == null) {
            throw new BizException("Menu does not exist");
        }
        rejectIfReferenced(menuMapper.selectActiveChildNamesByParentId(id), "菜单存在子菜单：");
        rejectIfReferenced(roleMenuMapper.selectActiveRoleNamesByMenuId(id), "菜单已分配给角色：");
        if (menuMapper.deleteById(id) == 0) {
            throw new BizException("Menu does not exist");
        }
    }

    @Override
    public AdminPermission createPermission(PermissionCreateRequest request) {
        if (isMenuResourceType(request.getResourceType())) {
            throw new BizException("Resource permissions only support API resources");
        }
        AdminPermission permission = new AdminPermission();
        BeanUtils.copyProperties(request, permission);
        permission.setResourceType("API");
        permission.setIsDeleted(0);
        permissionMapper.insert(permission);
        return permission;
    }

    @Override
    public Page<AdminPermission> permissionPage(long current, long size, PermissionQueryRequest query) {
        LambdaQueryWrapper<AdminPermission> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(AdminPermission::getIsDeleted, 0)
                .eq(AdminPermission::getResourceType, "API");
        if (query != null) {
            wrapper.eq(StringUtils.hasText(query.getPermissionCode()), AdminPermission::getPermissionCode, query.getPermissionCode())
                    .like(StringUtils.hasText(query.getPermissionName()), AdminPermission::getPermissionName, query.getPermissionName())
                    .like(StringUtils.hasText(query.getResourcePath()), AdminPermission::getResourcePath, query.getResourcePath())
                    .eq(query.getStatus() != null, AdminPermission::getStatus, query.getStatus());
        }
        return permissionMapper.selectPage(Page.of(current, size), wrapper.orderByDesc(AdminPermission::getId));
    }

    @Override
    public AdminPermission updatePermission(Long id, PermissionUpdateRequest request) {
        AdminPermission permission = permissionMapper.selectById(id);
        if (permission == null) {
            throw new BizException("Permission does not exist");
        }
        if (isMenuResourceType(permission.getResourceType()) || isMenuResourceType(request.getResourceType())) {
            throw new BizException("Resource permissions only support API resources");
        }
        BeanUtils.copyProperties(request, permission);
        permission.setResourceType("API");
        permissionMapper.updateById(permission);
        return permissionMapper.selectById(id);
    }

    @Override
    public void deletePermission(Long id) {
        AdminPermission permission = permissionMapper.selectById(id);
        if (permission == null || isMenuResourceType(permission.getResourceType())) {
            throw new BizException("Permission does not exist");
        }
        rejectIfReferenced(rolePermissionMapper.selectActiveRoleNamesByPermissionId(id), "API权限已分配给角色：");
        if (permissionMapper.deleteById(id) == 0) {
            throw new BizException("Permission does not exist");
        }
    }

    @Override
    public List<Long> permissionIds(Long roleId) {
        AdminRole role = roleMapper.selectById(roleId);
        if (role == null) {
            throw new BizException("Role does not exist");
        }
        if (isSuperAdminRole(role)) {
            return permissionMapper.selectList(new LambdaQueryWrapper<AdminPermission>()
                            .eq(AdminPermission::getIsDeleted, 0)
                            .eq(AdminPermission::getResourceType, "API")
                            .eq(AdminPermission::getStatus, 1))
                    .stream()
                    .map(AdminPermission::getId)
                    .toList();
        }
        return rolePermissionMapper.selectActivePermissionIdsByRoleId(roleId);
    }

    @Override
    public List<Long> menuIds(Long roleId) {
        AdminRole role = roleMapper.selectById(roleId);
        if (role == null) {
            throw new BizException("Role does not exist");
        }
        if (isSuperAdminRole(role)) {
            return menuMapper.selectList(new LambdaQueryWrapper<AdminMenu>()
                            .eq(AdminMenu::getIsDeleted, 0)
                            .eq(AdminMenu::getStatus, 1))
                    .stream()
                    .map(AdminMenu::getId)
                    .toList();
        }
        return roleMenuMapper.selectActiveMenuIdsByRoleId(roleId);
    }

    @Override
    public void assignMenus(Long roleId, List<Long> menuIds) {
        AdminRole role = roleMapper.selectById(roleId);
        if (role == null) {
            throw new BizException("Role does not exist");
        }
        if (isSuperAdminRole(role)) {
            throw new BizException("Super admin role menus cannot be changed");
        }
        Map<Long, AdminMenu> menuById = menuMapper.selectList(new LambdaQueryWrapper<AdminMenu>()
                        .eq(AdminMenu::getIsDeleted, 0)
                        .eq(AdminMenu::getStatus, 1))
                .stream()
                .collect(java.util.stream.Collectors.toMap(AdminMenu::getId, Function.identity(), (left, right) -> left));
        Set<Long> targetMenuIds = menuIds == null ? Set.of() : menuIds.stream()
                .filter(menuId -> menuId != null && menuId > 0)
                .filter(menuById::containsKey)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        List<AdminRoleMenu> existingRelations = roleMenuMapper.selectAllByRoleId(roleId);
        Map<Long, AdminRoleMenu> existingByMenuId = existingRelations.stream()
                .collect(java.util.stream.Collectors.toMap(AdminRoleMenu::getMenuId, Function.identity(), (left, right) -> left));

        for (AdminRoleMenu relation : existingRelations) {
            int nextDeleted = targetMenuIds.contains(relation.getMenuId()) ? 0 : 1;
            if (!Integer.valueOf(nextDeleted).equals(relation.getIsDeleted())) {
                roleMenuMapper.updateDeletedById(relation.getId(), nextDeleted);
            }
        }

        for (Long menuId : targetMenuIds) {
            AdminRoleMenu existing = existingByMenuId.get(menuId);
            if (existing == null || !Integer.valueOf(0).equals(existing.getIsDeleted())) {
                roleMenuMapper.upsertActive(roleId, menuId);
            }
        }
    }

    @Override
    public void assignApiPermissions(Long roleId, List<Long> permissionIds) {
        replacePermissions(roleId, permissionIds);
    }

    private void replacePermissions(Long roleId, List<Long> permissionIds) {
        AdminRole role = roleMapper.selectById(roleId);
        if (role == null) {
            throw new BizException("Role does not exist");
        }
        if (isSuperAdminRole(role)) {
            throw new BizException("Super admin role permissions cannot be changed");
        }
        Map<Long, AdminPermission> permissionById = permissionMapper.selectList(new LambdaQueryWrapper<AdminPermission>()
                        .eq(AdminPermission::getIsDeleted, 0)
                        .eq(AdminPermission::getResourceType, "API")
                        .eq(AdminPermission::getStatus, 1))
                .stream()
                .collect(java.util.stream.Collectors.toMap(AdminPermission::getId, Function.identity(), (left, right) -> left));
        Set<Long> targetPermissionIds = permissionIds == null ? Set.of() : permissionIds.stream()
                .filter(permissionId -> permissionId != null && permissionId > 0)
                .filter(permissionById::containsKey)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        List<AdminRolePermission> existingRelations = rolePermissionMapper.selectAllByRoleId(roleId);
        Map<Long, AdminRolePermission> existingByPermissionId = existingRelations.stream()
                .collect(java.util.stream.Collectors.toMap(AdminRolePermission::getPermissionId, Function.identity(), (left, right) -> left));

        for (AdminRolePermission relation : existingRelations) {
            if (!permissionById.containsKey(relation.getPermissionId())) {
                continue;
            }
            int nextDeleted = targetPermissionIds.contains(relation.getPermissionId()) ? 0 : 1;
            if (!Integer.valueOf(nextDeleted).equals(relation.getIsDeleted())) {
                rolePermissionMapper.updateDeletedById(relation.getId(), nextDeleted);
            }
        }

        for (Long permissionId : targetPermissionIds) {
            AdminRolePermission existing = existingByPermissionId.get(permissionId);
            if (existing == null || !Integer.valueOf(0).equals(existing.getIsDeleted())) {
                rolePermissionMapper.upsertActive(roleId, permissionId);
            }
        }
    }

    private boolean isSuperAdminRole(AdminRole role) {
        return role != null && SUPER_ADMIN_ROLE_CODE.equals(role.getRoleCode());
    }

    private boolean isMenuResourceType(String resourceType) {
        return "MENU".equalsIgnoreCase(resourceType);
    }

    private void rejectIfReferenced(List<String> names, String prefix) {
        if (names == null || names.isEmpty()) {
            return;
        }
        throw new BizException(prefix + formatReferenceNames(names) + "，不能删除");
    }

    private String formatReferenceNames(List<String> names) {
        int limit = Math.min(names.size(), 5);
        String joinedNames = String.join("、", names.subList(0, limit));
        if (names.size() <= limit) {
            return joinedNames;
        }
        return joinedNames + "等" + names.size() + "个";
    }
}
