package ffdd.compliance.dto;

import java.time.LocalDateTime;
import lombok.Data;

@Data
public class EvidenceUploadPolicyResponse {
    private String objectKey;
    private String uploadUrl;
    private String method;
    private String contentType;
    private long sizeBytes;
    private int expiresInSeconds;
    private LocalDateTime expiresAt;
}
