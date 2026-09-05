package ffdd.opsconsole.auth.dto;

public record AppTwoFactorChallengeRequest(Boolean enabled, String currentPassword) {
}
