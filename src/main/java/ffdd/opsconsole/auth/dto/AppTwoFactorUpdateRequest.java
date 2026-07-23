package ffdd.opsconsole.auth.dto;

public record AppTwoFactorUpdateRequest(Boolean enabled, String currentPassword) {
}
