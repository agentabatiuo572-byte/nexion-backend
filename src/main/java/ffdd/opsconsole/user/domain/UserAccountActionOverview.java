package ffdd.opsconsole.user.domain;

import java.util.List;

public record UserAccountActionOverview(
        List<UserAccountView> accounts,
        List<UserAccountListEntryView> accountLists,
        List<UserSessionView> sessions,
        List<UserImpersonationSessionView> impersonations,
        List<UserAccountControlFactView> controlFacts,
        long frozenUsers,
        long activeSessions,
        long trustListCount,
        long blockedListCount,
        long activeImpersonations,
        long totalAccounts,
        long totalAccountLists,
        long totalSessions,
        long totalImpersonations,
        List<String> sources,
        List<String> redlines) {
}
