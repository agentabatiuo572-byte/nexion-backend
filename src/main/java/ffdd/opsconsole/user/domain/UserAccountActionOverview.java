package ffdd.opsconsole.user.domain;

import java.util.List;

public record UserAccountActionOverview(
        List<UserAccountView> accounts,
        List<UserAccountListEntryView> accountLists,
        List<UserSessionView> sessions,
        List<UserImpersonationSessionView> impersonations,
        long frozenUsers,
        long activeSessions,
        long trustListCount,
        long blockedListCount,
        long activeImpersonations,
        List<String> sources,
        List<String> redlines) {
}
