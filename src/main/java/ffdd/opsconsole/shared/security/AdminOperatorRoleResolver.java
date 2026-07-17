package ffdd.opsconsole.shared.security;

import ffdd.opsconsole.auth.infrastructure.AdminEntity;
import ffdd.opsconsole.auth.mapper.AdminMapper;
import ffdd.opsconsole.auth.mapper.AdminRoleRelationMapper;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** Resolves the audit role label from the authenticated administrator record. */
@Component
@RequiredArgsConstructor
public class AdminOperatorRoleResolver {
    private final AdminMapper adminMapper;
    private final AdminRoleRelationMapper roleRelationMapper;

    public String resolve() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        Long adminId = authentication == null ? null : positiveId(authentication.getPrincipal());
        if (adminId == null) {
            return "认证运营员";
        }
        AdminEntity admin = adminMapper.selectById(adminId);
        if (admin == null || Integer.valueOf(1).equals(admin.getIsDeleted())
                || !Integer.valueOf(1).equals(admin.getStatus())) {
            return "认证运营员";
        }
        if (Integer.valueOf(1).equals(admin.getSuperAdmin())) {
            return "超管";
        }
        String roleCode = roleRelationMapper.activeRoleCode(adminId);
        if (!StringUtils.hasText(roleCode)) {
            return "审计";
        }
        return switch (roleCode.trim().toUpperCase(Locale.ROOT)) {
            case "SUPER_ADMIN" -> "超管";
            case "CONFIG_ADMIN" -> "配置管理员";
            case "FINANCE" -> "财务";
            case "RISK" -> "风控";
            case "CONTENT" -> "内容";
            case "GROWTH" -> "增长";
            case "SUPPORT" -> "客服";
            case "AUDITOR" -> "审计";
            default -> "运营角色:" + roleCode.trim().toUpperCase(Locale.ROOT);
        };
    }

    private Long positiveId(Object principal) {
        if (principal == null) {
            return null;
        }
        try {
            long value = Long.parseLong(String.valueOf(principal));
            return value > 0 ? value : null;
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
