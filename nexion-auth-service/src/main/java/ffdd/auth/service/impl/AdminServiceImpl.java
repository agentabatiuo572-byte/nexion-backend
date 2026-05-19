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
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class AdminServiceImpl implements AdminService {
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
        BeanUtils.copyProperties(request, admin);
        adminMapper.updateById(admin);
        return detail(id);
    }

    @Override
    public void delete(Long id) {
        if (adminMapper.deleteById(id) == 0) {
            throw new BizException("Admin does not exist");
        }
    }

    @Override
    public void assignRoles(Long adminId, List<Long> roleIds) {
        detail(adminId);
        adminRoleRelationMapper.delete(new LambdaQueryWrapper<AdminRoleRelation>()
                .eq(AdminRoleRelation::getAdminId, adminId));
        for (Long roleId : roleIds) {
            AdminRoleRelation relation = new AdminRoleRelation();
            relation.setAdminId(adminId);
            relation.setRoleId(roleId);
            relation.setIsDeleted(0);
            adminRoleRelationMapper.insert(relation);
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

