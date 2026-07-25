package ffdd.opsconsole.janus.dto;

public record JanusStatusChangeRequest(
        String targetStatus,
        String reasonCategory,
        String reasonText,
        String effectiveTiming,
        Long expireAt,
        String remoteUrlKey,
        Integer remoteTargetVersion,
        Long remoteTargetCatalogVersion,
        String confirmationMode,
        Long expectedDeviceVersion) {
    public JanusStatusChangeRequest(
            String targetStatus,
            String reasonCategory,
            String reasonText,
            String effectiveTiming,
            Long expireAt,
            String remoteUrlKey,
            String confirmationMode,
            Long expectedDeviceVersion) {
        this(targetStatus, reasonCategory, reasonText, effectiveTiming, expireAt, remoteUrlKey,
                null, null, confirmationMode, expectedDeviceVersion);
    }
}
