package ffdd.auth.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import ffdd.auth.domain.Admin;
import ffdd.auth.domain.AdminRoleRelation;
import ffdd.auth.dto.AdminCreateRequest;
import ffdd.auth.dto.AdminQueryRequest;
import ffdd.auth.dto.AdminUpdateRequest;
import ffdd.auth.mapper.AdminMapper;
import ffdd.auth.mapper.AdminRoleRelationMapper;
import ffdd.auth.service.AdminService;
import ffdd.common.exception.BizException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class AdminServiceImpl implements AdminService {
    private static final long ROOT_ADMIN_ID = 1L;

    private final AdminMapper adminMapper;
    private final AdminRoleRelationMapper adminRoleRelationMapper;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    @Override
    public Admin create(AdminCreateRequest request) {
        Admin admin = new Admin();
        BeanUtils.copyProperties(request, admin);
        admin.setPasswordHash(passwordEncoder.encode(request.getPassword()));
        admin.setIsDeleted(0);
        adminMapper.insert(admin);
        return admin;
    }

    @Override
    public Page<Admin> page(long current, long size, AdminQueryRequest query) {
        return adminMapper.selectPage(Page.of(current, size), buildQuery(query));
    }

    @Override
    public Admin detail(Long id) {
        Admin admin = adminMapper.selectById(id);
        if (admin == null) {
            throw new BizException("Admin does not exist");
        }
        return admin;
    }

    @Override
    public Admin update(Long id, AdminUpdateRequest request) {
        Admin admin = detail(id);
        if (ROOT_ADMIN_ID == id && (Integer.valueOf(0).equals(request.getSuperAdmin()) || Integer.valueOf(0).equals(request.getStatus()))) {
            throw new BizException("Root super admin cannot be downgraded or disabled");
        }
        BeanUtils.copyProperties(request, admin);
        adminMapper.updateById(admin);
        return detail(id);
    }

    @Override
    public void delete(Long id) {
        if (ROOT_ADMIN_ID == id) {
            throw new BizException("Root super admin cannot be deleted");
        }
        if (adminMapper.deleteById(id) == 0) {
            throw new BizException("Admin does not exist");
        }
    }

    @Override
    public List<Long> roleIds(Long adminId) {
        detail(adminId);
        return adminRoleRelationMapper.selectActiveRoleIdsByAdminId(adminId);
    }

    @Override
    public void assignRoles(Long adminId, List<Long> roleIds) {
        if (ROOT_ADMIN_ID == adminId) {
            throw new BizException("Root super admin roles cannot be changed");
        }
        detail(adminId);
        Set<Long> targetRoleIds = roleIds == null ? Set.of() : roleIds.stream()
                .filter(roleId -> roleId != null && roleId > 0)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        List<AdminRoleRelation> existingRelations = adminRoleRelationMapper.selectAllByAdminId(adminId);
        Map<Long, AdminRoleRelation> existingByRoleId = existingRelations.stream()
                .collect(Collectors.toMap(AdminRoleRelation::getRoleId, Function.identity(), (left, right) -> left));

        for (AdminRoleRelation relation : existingRelations) {
            int nextDeleted = targetRoleIds.contains(relation.getRoleId()) ? 0 : 1;
            if (!Integer.valueOf(nextDeleted).equals(relation.getIsDeleted())) {
                adminRoleRelationMapper.updateDeletedById(relation.getId(), nextDeleted);
            }
        }

        for (Long roleId : targetRoleIds) {
            AdminRoleRelation existing = existingByRoleId.get(roleId);
            if (existing == null || !Integer.valueOf(0).equals(existing.getIsDeleted())) {
                adminRoleRelationMapper.upsertActive(adminId, roleId);
            }
        }
    }

    private LambdaQueryWrapper<Admin> buildQuery(AdminQueryRequest query) {
        LambdaQueryWrapper<Admin> wrapper = new LambdaQueryWrapper<>();
        if (query == null) {
            return wrapper.orderByDesc(Admin::getId);
        }
        return wrapper
                .like(StringUtils.hasText(query.getUsername()), Admin::getUsername, query.getUsername())
                .eq(StringUtils.hasText(query.getPhone()), Admin::getPhone, query.getPhone())
                .eq(query.getStatus() != null, Admin::getStatus, query.getStatus())
                .eq(query.getSuperAdmin() != null, Admin::getSuperAdmin, query.getSuperAdmin())
                .orderByDesc(Admin::getId);
    }
}
