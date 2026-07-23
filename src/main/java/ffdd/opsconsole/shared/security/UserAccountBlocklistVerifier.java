package ffdd.opsconsole.shared.security;

public interface UserAccountBlocklistVerifier {
    boolean isBlocked(Long userId);

    default boolean isAllowlisted(Long userId) {
        return false;
    }
}
