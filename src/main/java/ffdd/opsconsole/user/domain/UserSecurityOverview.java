package ffdd.opsconsole.user.domain;

import ffdd.opsconsole.shared.api.PageResult;
import java.util.List;

public record UserSecurityOverview(
        UserSecurityStats stats,
        List<UserCredentialParamView> credentialParams,
        UserSecurityUserRow selectedUser,
        PageResult<UserSessionView> sessions,
        List<UserSecurityUserRow> lockedUsers,
        List<String> sources,
        List<String> redlines) {
}
