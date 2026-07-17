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
            String username,
            String email,
            String role,
            boolean tfa,
            String status,
            String lastLogin,
            int sessions,
            String tfaResetAt,
            String credentialDeliveryStatus,
            List<SessionRecord> sessionDetails,
            List<RoleHistoryRecord> roleHistory) {
        public OperatorRecord(
                String id,
                String name,
                String username,
                String email,
                String role,
                boolean tfa,
                String status,
                String lastLogin,
                int sessions,
                String tfaResetAt,
                String credentialDeliveryStatus) {
            this(id, name, username, email, role, tfa, status, lastLogin, sessions,
                    tfaResetAt, credentialDeliveryStatus, List.of(), List.of());
        }
    }

    public record SessionRecord(
            String sessionId,
            String ipAddress,
            String device,
            String issuedAt,
            String lastSeenAt) {
    }

    public record RoleHistoryRecord(
            String fromRole,
            String toRole,
            String changedAt,
            String operator,
            String source) {
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
