package ffdd.opsconsole.janus.dto;

public record JanusStatusChangeRequest(
        String targetStatus,
        String reasonCategory,
        String reasonText,
        String effectiveTiming,
        Long expireAt,
        String remoteUrlKey,
        String confirmationMode,
        Long expectedDeviceVersion) {
}
