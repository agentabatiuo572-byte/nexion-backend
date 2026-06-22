package ffdd.opsconsole.media.dto;

import java.time.LocalDateTime;

public record UploadedAsset(
        String assetId,
        String objectKey,
        String bucket,
        String contentType,
        Long sizeBytes,
        String previewUrl,
        LocalDateTime expiresAt,
        String domain,
        String usage) {
}
