package ffdd.opsconsole.auth.dto;

import java.util.List;

public record AdminLoginResponse(String accessToken, String tokenType, AdminSession session) {
    public record AdminSession(
            Long adminId,
            String username,
            String operator,
            String role,
            String roleCode,
            List<String> authorities,
            List<String> effectiveMenus,
            List<EffectiveMenuNode> effectiveMenuNodes,
            boolean passwordChangeRequired) {
    }

    /** A7 metadata filtered by the current admin's active A6 role-menu grants. */
    public record EffectiveMenuNode(
            String menuCode,
            String menuName,
            String routePath,
            String parentCode,
            Integer sortOrder) {
    }
}
