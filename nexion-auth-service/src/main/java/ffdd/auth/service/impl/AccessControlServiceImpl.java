package ffdd.auth.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import ffdd.auth.domain.AdminPermission;
import ffdd.auth.domain.AdminRole;
import ffdd.auth.domain.AdminRolePermission;
import ffdd.auth.dto.PermissionCreateRequest;
import ffdd.auth.dto.PermissionQueryRequest;
import ffdd.auth.dto.PermissionUpdateRequest;
import ffdd.auth.dto.RoleCreateRequest;
import ffdd.auth.dto.RoleQueryRequest;
import ffdd.auth.dto.RoleUpdateRequest;
import ffdd.auth.mapper.AdminPermissionMapper;
import ffdd.auth.mapper.AdminRoleMapper;
import ffdd.auth.mapper.AdminRolePermissionMapper;
import ffdd.auth.service.AccessControlService;
import ffdd.common.exception.BizException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class AccessControlServiceImpl implements AccessControlService {
    private final AdminRoleMapper roleMapper;
    private final AdminPermissionMapper permissionMapper;
    private final AdminRolePermissionMapper rolePermissionMapper;

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
        BeanUtils.copyProperties(request, role);
        roleMapper.updateById(role);
        return roleMapper.selectById(id);
    }

    @Override
    public void deleteRole(Long id) {
        if (roleMapper.deleteById(id) == 0) {
            throw new BizException("Role does not exist");
        }
    }

    @Override
    public AdminPermission createPermission(PermissionCreateRequest request) {
        AdminPermission permission = new AdminPermission();
        BeanUtils.copyProperties(request, permission);
        permission.setIsDeleted(0);
        permissionMapper.insert(permission);
        return permission;
    }

    @Override
    public Page<AdminPermission> permissionPage(long current, long size, PermissionQueryRequest query) {
        LambdaQueryWrapper<AdminPermission> wrapper = new LambdaQueryWrapper<>();
        if (query != null) {
            wrapper.eq(StringUtils.hasText(query.getPermissionCode()), AdminPermission::getPermissionCode, query.getPermissionCode())
                    .like(StringUtils.hasText(query.getPermissionName()), AdminPermission::getPermissionName, query.getPermissionName())
                    .eq(StringUtils.hasText(query.getResourceType()), AdminPermission::getResourceType, query.getResourceType())
                    .like(StringUtils.hasText(query.getResourcePath()), AdminPermission::getResourcePath, query.getResourcePath());
        }
        return permissionMapper.selectPage(Page.of(current, size), wrapper.orderByDesc(AdminPermission::getId));
    }

    @Override
    public AdminPermission updatePermission(Long id, PermissionUpdateRequest request) {
        AdminPermission permission = permissionMapper.selectById(id);
        if (permission == null) {
            throw new BizException("Permission does not exist");
        }
        BeanUtils.copyProperties(request, permission);
        permissionMapper.updateById(permission);
        return permissionMapper.selectById(id);
    }

    @Override
    public void deletePermission(Long id) {
        if (permissionMapper.deleteById(id) == 0) {
            throw new BizException("Permission does not exist");
        }
    }

    @Override
    public void assignPermissions(Long roleId, List<Long> permissionIds) {
        if (roleMapper.selectById(roleId) == null) {
            throw new BizException("Role does not exist");
        }
        rolePermissionMapper.delete(new LambdaQueryWrapper<AdminRolePermission>()
                .eq(AdminRolePermission::getRoleId, roleId));
        for (Long permissionId : permissionIds) {
            AdminRolePermission relation = new AdminRolePermission();
            relation.setRoleId(roleId);
            relation.setPermissionId(permissionId);
            relation.setIsDeleted(0);
            rolePermissionMapper.insert(relation);
        }
    }
}

