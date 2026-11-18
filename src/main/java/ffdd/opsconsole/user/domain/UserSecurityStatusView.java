package ffdd.opsconsole.user.domain;

public record UserSecurityStatusView(
        Long userId,
        boolean twoFactorEnabled,
        int loginFailCount,
        boolean locked,
        boolean passwordResetRequired,
        int lockThreshold,
        int lockDurationMinutes,
        java.time.LocalDateTime lockedUntil) {
    @org.apache.ibatis.annotations.AutomapConstructor
    public UserSecurityStatusView { }

    public UserSecurityStatusView(Long userId, boolean twoFactorEnabled, int loginFailCount, boolean locked,
            boolean passwordResetRequired, int lockThreshold, int lockDurationMinutes) {
        this(userId, twoFactorEnabled, loginFailCount, locked, passwordResetRequired, lockThreshold, lockDurationMinutes, null);
    }
}
