package ffdd.opsconsole.platform.dto;

public record PlatformConfigUpdateRequest(
        String kind,
        String flagKey,
        String gateKey,
        String value,
        String reason,
        String operator) {
}
