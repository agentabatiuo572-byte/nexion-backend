package ffdd.opsconsole.team.dto;

/** Replayable export artifact persisted by the admin idempotency boundary. */
public record F5CommissionExportPayload(
        String exportId,
        String filename,
        long rowCount,
        int byteSize,
        String sha256,
        byte[] content) {
}
