package ffdd.opsconsole.auth.dto;

public record AdminMfaVerifyRequest(String challengeId, String code) {}
