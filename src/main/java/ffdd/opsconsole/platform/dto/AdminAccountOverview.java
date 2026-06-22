package ffdd.opsconsole.platform.dto;

import java.util.List;

public record AdminAccountOverview(
        AdminAccountStats stats,
        List<RoleDefinition> roles,
        List<OperatorRecord> operators,
        List<RbacAction> rbacMatrix,
        List<SecurityBaseline> securityBaselines) {
    public record AdminAccountStats(
            int totalAccounts,
            int activeAccounts,
            int disabledAccounts,
            int activeSessions,
            int effectiveSupers,
            int pendingAcctTickets) {
    }

    public record RoleDefinition(
            String key,
            String name,
            String av,
            String color,
            String desc,
            String scope) {
    }

    public record OperatorRecord(
            String id,
            String name,
            String email,
            String role,
            String tier,
            boolean tfa,
            String status,
            String lastLogin,
            int sessions,
            String tfaResetAt,
            String credentialDeliveryStatus) {
    }

    public record RbacAction(
            String id,
            String action,
            String domainGroup,
            List<String> grants) {
    }

    public record SecurityBaseline(
            String key,
            String name,
            String sub,
            String value,
            boolean locked) {
    }
}
