package ffdd.auth.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import ffdd.auth.domain.Admin;
import ffdd.auth.domain.AdminMenu;
import ffdd.auth.dto.AdminChangePasswordRequest;
import ffdd.auth.dto.AdminLoginRequest;
import ffdd.auth.dto.AdminLoginResponse;
import ffdd.auth.dto.AdminProfileResponse;
import ffdd.auth.dto.AdminProfileUpdateRequest;
import ffdd.auth.mapper.AdminMapper;
import ffdd.auth.mapper.AdminMenuMapper;
import ffdd.auth.mapper.AdminPermissionMapper;
import ffdd.auth.mapper.AdminRoleMenuMapper;
import ffdd.auth.mapper.AdminRolePermissionMapper;
import ffdd.auth.mapper.AdminRoleRelationMapper;
import ffdd.auth.service.AdminAuthService;
import ffdd.common.exception.BizException;
import ffdd.common.security.JwtTokenProvider;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class AdminAuthServiceImpl implements AdminAuthService {
    private final AdminMapper adminMapper;
    private final AdminMenuMapper menuMapper;
    private final AdminPermissionMapper permissionMapper;
    private final AdminRoleRelationMapper roleRelationMapper;
    private final AdminRoleMenuMapper roleMenuMapper;
    private final AdminRolePermissionMapper rolePermissionMapper;
    private final JwtTokenProvider tokenProvider;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    @Override
    public AdminLoginResponse login(AdminLoginRequest request) {
        Admin admin = adminMapper.selectOne(new LambdaQueryWrapper<Admin>()
                .eq(Admin::getUsername, request.getUsername())
                .last("LIMIT 1"));
        if (admin == null || !passwordEncoder.matches(request.getPassword(), admin.getPasswordHash())) {
            throw new BizException("用户名或密码错误");
        }
        if (!Integer.valueOf(1).equals(admin.getStatus())) {
            throw new BizException("账号已禁用");
        }
        AdminProfileResponse profile = buildProfile(admin);
        String token = tokenProvider.createToken(admin.getId(), "ADMIN", admin.getUsername(), profile.getAuthorities());
        return new AdminLoginResponse(token, profile);
    }

    @Override
    public AdminProfileResponse current() {
        return buildProfile(currentAdmin());
    }

    @Override
    public AdminProfileResponse updateProfile(AdminProfileUpdateRequest request) {
        Admin admin = currentAdmin();
        admin.setNickname(request.getNickname());
        admin.setEmail(request.getEmail());
        admin.setPhone(request.getPhone());
        adminMapper.updateById(admin);
        return buildProfile(adminMapper.selectById(admin.getId()));
    }

    @Override
    public void changePassword(AdminChangePasswordRequest request) {
        Admin admin = currentAdmin();
        if (!passwordEncoder.matches(request.getOldPassword(), admin.getPasswordHash())) {
            throw new BizException("原密码错误");
        }
        admin.setPasswordHash(passwordEncoder.encode(request.getNewPassword()));
        adminMapper.updateById(admin);
    }

    private Admin currentAdmin() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !StringUtils.hasText(authentication.getName())) {
            throw new BizException(401, "登录已失效");
        }
        Long adminId = Long.valueOf(authentication.getName());
        Admin admin = adminMapper.selectById(adminId);
        if (admin == null || !Integer.valueOf(1).equals(admin.getStatus())) {
            throw new BizException(401, "登录已失效");
        }
        return admin;
    }

    private AdminProfileResponse buildProfile(Admin admin) {
        List<Long> roleIds = roleRelationMapper.selectActiveRoleIdsByAdminId(admin.getId());
        boolean superAdmin = Integer.valueOf(1).equals(admin.getSuperAdmin());
        List<String> authorities = Integer.valueOf(1).equals(admin.getSuperAdmin())
                ? permissionMapper.selectList(new LambdaQueryWrapper<>()).stream()
                        .filter(item -> Integer.valueOf(0).equals(item.getIsDeleted()))
                        .filter(item -> Integer.valueOf(1).equals(item.getStatus()))
                        .filter(item -> "API".equals(item.getResourceType()))
                        .map(item -> item.getPermissionCode())
                        .toList()
                : (roleIds.isEmpty() ? List.of() : rolePermissionMapper.selectActivePermissionCodesByRoleIds(roleIds));
        List<String> menuPaths = buildMenuPaths(superAdmin, roleIds);
        return new AdminProfileResponse(
                admin.getId(),
                admin.getUsername(),
                admin.getNickname(),
                admin.getEmail(),
                admin.getPhone(),
                admin.getSuperAdmin(),
                admin.getStatus(),
                roleIds,
                authorities,
                menuPaths);
    }

    private List<String> buildMenuPaths(boolean superAdmin, List<Long> roleIds) {
        List<AdminMenu> menus = menuMapper.selectList(new LambdaQueryWrapper<AdminMenu>()
                .eq(AdminMenu::getIsDeleted, 0));
        Map<Long, AdminMenu> menuById = menus.stream()
                .collect(java.util.stream.Collectors.toMap(AdminMenu::getId, Function.identity(), (left, right) -> left));
        Set<Long> allowedMenuIds = superAdmin
                ? menuById.keySet()
                : (roleIds.isEmpty() ? Set.of() : new HashSet<>(roleMenuMapper.selectActiveMenuIdsByRoleIds(roleIds)));
        return menus.stream()
                .filter(menu -> allowedMenuIds.contains(menu.getId()))
                .filter(menu -> StringUtils.hasText(menu.getRoutePath()))
                .filter(menu -> isMenuAndAncestorsEnabled(menu, menuById))
                .map(AdminMenu::getRoutePath)
                .toList();
    }

    private boolean isMenuAndAncestorsEnabled(AdminMenu menu, Map<Long, AdminMenu> menuById) {
        Set<Long> visited = new HashSet<>();
        AdminMenu current = menu;
        while (current != null) {
            if (!Integer.valueOf(0).equals(current.getIsDeleted()) || !Integer.valueOf(1).equals(current.getStatus())) {
                return false;
            }
            Long currentId = current.getId();
            if (currentId != null && !visited.add(currentId)) {
                return false;
            }
            Long parentId = current.getParentId();
            if (parentId == null || parentId <= 0) {
                return true;
            }
            current = menuById.get(parentId);
            if (current == null) {
                return false;
            }
        }
        return true;
    }
}
