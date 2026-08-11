package ffdd.opsconsole.device.dto;

public record AppTaskCompleteRequest(
        String resultHash,
        String proofMode,
        String executorId,
        String proofNonce,
        Long proofTimestamp,
        String proofSignature) {
}
