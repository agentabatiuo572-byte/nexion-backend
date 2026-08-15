package ffdd.opsconsole.auth.dto;

public record UserOAuthExchangeRequest(
        String provider,
        String mode,
        String externalSubject,
        String displayName) {
}
