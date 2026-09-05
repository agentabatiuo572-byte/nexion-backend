package ffdd.opsconsole.auth.dto;

public record AppTwoFactorUpdateRequest(
        Boolean enabled,
        String currentPassword,
        String challengeNo,
        String code) {
    public AppTwoFactorUpdateRequest(Boolean enabled, String currentPassword) {
        this(enabled, currentPassword, null, null);
    }
}
