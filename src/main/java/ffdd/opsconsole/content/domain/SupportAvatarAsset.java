package ffdd.opsconsole.content.domain;

import java.time.LocalDateTime;

/** Private storage location never leaves the avatar service. */
public record SupportAvatarAsset(String id,Long uploaderId,String clientUploadId,String idempotencyKey,
        String requestHash,String mime,Long byteCount,String objectKey,String state,Long attachedAdminId,LocalDateTime expiresAt) {}
