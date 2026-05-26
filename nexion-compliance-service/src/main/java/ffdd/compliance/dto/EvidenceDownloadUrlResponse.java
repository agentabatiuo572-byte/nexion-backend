package ffdd.compliance.dto;

import java.time.LocalDateTime;
import lombok.Data;

@Data
public class EvidenceDownloadUrlResponse {
    private String objectKey;
    private String downloadUrl;
    private int expiresInSeconds;
    private LocalDateTime expiresAt;
}
