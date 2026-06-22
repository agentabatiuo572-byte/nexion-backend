package ffdd.opsconsole.user.domain;

public record UserSecurityStatusView(
        Long userId,
        boolean twoFactorEnabled,
        int loginFailCount,
        boolean locked,
        boolean passwordResetRequired,
        int lockThreshold,
        int lockDurationMinutes) {
}
