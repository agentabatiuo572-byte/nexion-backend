package ffdd.opsconsole.user.domain;

public record UserSecurityStats(
        long activeSessions,
        String twoFactorRatePct,
        long lockedShort,
        long lockedLong,
        long tokenReuseToday) {
}
