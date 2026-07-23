package ffdd.opsconsole.shared.security;

public interface ImpersonationSessionVerifier {
    boolean isActive(Long userId, String sessionNo);
}
