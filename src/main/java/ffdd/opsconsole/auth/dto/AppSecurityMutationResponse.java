package ffdd.opsconsole.auth.dto;

import java.time.LocalDateTime;

public record AppSecurityMutationResponse(
        Boolean twoFactorEnabled,
        LocalDateTime passwordChangedAt,
        Integer revokedSessionCount) {

    public static AppSecurityMutationResponse sessions(int count) {
        return new AppSecurityMutationResponse(null, null, count);
    }

    public static AppSecurityMutationResponse password(LocalDateTime changedAt, int revokedCount) {
        return new AppSecurityMutationResponse(null, changedAt, revokedCount);
    }

    public static AppSecurityMutationResponse twoFactor(boolean enabled) {
        return new AppSecurityMutationResponse(enabled, null, null);
    }
}
