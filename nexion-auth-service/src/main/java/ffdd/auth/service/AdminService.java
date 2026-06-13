package ffdd.auth.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import ffdd.auth.domain.Admin;
import ffdd.auth.dto.AdminCreateRequest;
import ffdd.auth.dto.AdminQueryRequest;
import ffdd.auth.dto.AdminTwoFactorResetRequest;
import ffdd.auth.dto.AdminTwoFactorResetResponse;
import ffdd.auth.dto.AdminUpdateRequest;
import java.util.List;

public interface AdminService {
    Admin create(AdminCreateRequest request);

    Page<Admin> page(long current, long size, AdminQueryRequest query);

    Admin detail(Long id);

    Admin update(Long id, AdminUpdateRequest request);

    void delete(Long id);

    List<Long> roleIds(Long adminId);

    void assignRoles(Long adminId, List<Long> roleIds);

    AdminTwoFactorResetResponse resetTwoFactor(Long adminId, AdminTwoFactorResetRequest request);
}
