package ffdd.opsconsole.auth.dto;

import java.util.List;

public record AdminLoginResponse(String accessToken, String tokenType, AdminSession session, MfaChallenge mfa) {
    public AdminLoginResponse(String accessToken, String tokenType, AdminSession session) {
        this(accessToken, tokenType, session, null);
    }

    public record MfaChallenge(
            String challengeId,
            String mode,
            long expiresInSeconds,
            String provisioningUri,
            String manualKey) {
    }

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
