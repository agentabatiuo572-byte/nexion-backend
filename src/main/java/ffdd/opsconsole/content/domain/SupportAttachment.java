package ffdd.opsconsole.content.domain;

import java.time.LocalDateTime;

/** Internal persistence row. Never serialize this record to a client. */
public record SupportAttachment(String id, Long customerId, String uploaderType, Long uploaderId,
        Long assignmentId, String clientUploadId, String requestHash, String mime, long bytes,
        int width, int height, String objectKey, String state, LocalDateTime expiresAt, Long messageId) {
    public View view() { return new View(id, customerId, mime, bytes, width, height, state, expiresAt, messageId); }
    public record View(String id, Long customerId, String mime, long bytes, int width, int height,
            String state, LocalDateTime expiresAt, Long messageId) {}
}
