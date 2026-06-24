package ffdd.opsconsole.user.domain;

public record UserSecurityUserRow(
        Long userId,
        String userNo,
        String nickname,
        boolean twoFactorEnabled,
        int loginFailCount,
        boolean locked,
        boolean passwordResetRequired,
        String lockKind,
        String lockLabel,
        String lockReason,
        String lockLeft) {
}
