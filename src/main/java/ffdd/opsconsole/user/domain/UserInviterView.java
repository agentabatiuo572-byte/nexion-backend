package ffdd.opsconsole.user.domain;

/** C1 list inviter projection: no numeric id, navigation target or support-owner fields. */
public record UserInviterView(State state, String userNo, String nickname, Reason reason) {
    public enum State { PRESENT, NONE, DELETED, UNKNOWN }
    public enum Reason { DANGLING_SPONSOR, ENVIRONMENT_CONFLICT, UNKNOWN_ENVIRONMENT, SOURCE_READ_FAILED }

    public static UserInviterView present(String userNo, String nickname) {
        return new UserInviterView(State.PRESENT, userNo, nickname, null);
    }

    public static UserInviterView none() {
        return new UserInviterView(State.NONE, null, null, null);
    }

    public static UserInviterView deleted(String userNo) {
        return new UserInviterView(State.DELETED, userNo, null, null);
    }

    public static UserInviterView unknown(Reason reason) {
        return new UserInviterView(State.UNKNOWN, null, null, reason);
    }
}
