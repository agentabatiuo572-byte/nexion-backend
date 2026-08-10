package ffdd.opsconsole.janus.dto;

public record JanusTakeoverProgressRequest(
        String deviceId,
        String commandId,
        Long commandVersion,
        String phase,
        String actualTargetId,
        Integer actualTargetVersion,
        Long actualTargetCatalogVersion,
        Long deviceAppliedVersion,
        String deviceAppVersion,
        String handoffReceipt,
        String failureCode,
        String failureClass,
        String failureMessage,
        String reconciliationId) {
}
