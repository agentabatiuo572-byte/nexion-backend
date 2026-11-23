package ffdd.opsconsole.user.domain;

import com.fasterxml.jackson.annotation.JsonInclude;

/** C1 detail inviter projection. The numeric inviter id is never emitted; customerId is support-only. */
public record UserInviterDetailView(UserInviterView.State state, String userNo, String nickname,
                                    UserInviterView.Reason reason, boolean navigable,
                                    @JsonInclude(JsonInclude.Include.NON_NULL) String customerId) {
    public static UserInviterDetailView from(UserInviterView inviter, boolean navigable) {
        return from(inviter, navigable, null);
    }

    public static UserInviterDetailView from(UserInviterView inviter, boolean navigable, String customerId) {
        return new UserInviterDetailView(inviter.state(), inviter.userNo(), inviter.nickname(),
                inviter.reason(), navigable, customerId);
    }
}
