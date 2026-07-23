package ffdd.opsconsole.user.domain;

import java.util.List;

/** Server-authoritative C2 detail for one exact user, independent of overview truncation. */
public record UserAccountActionContext(
        UserAccountView account,
        UserAccountListEntryView accountList,
        List<UserSessionView> sessions,
        List<UserImpersonationSessionView> impersonations,
        UserAccountControlFactView controlFact,
        long totalSessions,
        long activeSessions,
        long totalImpersonations,
        boolean sessionsTruncated,
        boolean impersonationsTruncated) {
}
