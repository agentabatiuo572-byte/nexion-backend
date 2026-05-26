package ffdd.compliance.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class ProofAssetCreateRequest {
    @NotNull
    private Long userId;

    @Size(max = 96)
    private String proofNo;

    @NotBlank
    @Size(max = 64)
    private String proofType;

    @NotBlank
    @Size(max = 255)
    private String objectKey;

    @Size(max = 32)
    private String status;

    @Size(max = 255)
    private String fileName;

    @Size(max = 128)
    private String contentType;

    @Min(0)
    private Long sizeBytes;

    @Size(max = 128)
    private String checksum;

    @Size(max = 64)
    private String relatedBizType;

    @Size(max = 96)
    private String relatedBizNo;

    @Size(max = 64)
    private String submittedBy;

    @Size(max = 2048)
    private String metadataJson;
}
