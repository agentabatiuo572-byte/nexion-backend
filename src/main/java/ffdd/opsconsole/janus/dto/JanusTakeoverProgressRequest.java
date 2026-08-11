package ffdd.opsconsole.janus.dto;

public record JanusTakeoverProgressRequest(
        String deviceId,
        String commandId,
        Long commandVersion,
        String leaseToken,
        Long fencingToken,
        String phase,
        String actualTargetId,
        Integer actualTargetVersion,
        Long actualTargetCatalogVersion,
        Long deviceAppliedVersion,
        String deviceAppVersion,
        String handoffReceipt,
        String actualAppliedCommandId,
        Long actualAppliedCommandVersion,
        Long actualFencingToken,
        String failureCode,
        String failureClass,
        String failureMessage,
        String reconciliationId,
        String proofMode,
        String executorId,
        String proofNonce,
        Long proofTimestamp,
        String proofSignature) {
}
