package ffdd.opsconsole.janus.dto;

public record JanusCommandAckRequest(
        String deviceId,
        Long revision,
        String commandId,
        String leaseToken,
        Long fencingToken,
        Boolean success,
        String appliedStatus,
        String message,
        String handoffReceipt,
        String actualAppliedCommandId,
        Long actualAppliedCommandVersion,
        Long actualFencingToken,
        Long deviceAppliedVersion,
        String deviceAppVersion,
        String actualTargetId,
        Integer actualTargetVersion,
        Long actualTargetCatalogVersion,
        String proofMode,
        String executorId,
        String proofNonce,
        Long proofTimestamp,
        String proofSignature) {
}
