package ffdd.opsconsole.user.domain;

/** C1 detail inviter projection. The numeric inviter id and customer id stay server-side. */
public record UserInviterDetailView(UserInviterView.State state, String userNo, String nickname,
                                    UserInviterView.Reason reason, boolean navigable) {
    public static UserInviterDetailView from(UserInviterView inviter, boolean navigable) {
        return new UserInviterDetailView(inviter.state(), inviter.userNo(), inviter.nickname(), inviter.reason(), navigable);
    }
}
