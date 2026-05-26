package ffdd.compliance.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class ProofAssetUploadRequest {
    @NotNull
    private Long userId;

    @Size(max = 96)
    private String proofNo;

    @NotBlank
    @Size(max = 64)
    private String proofType;

    @Size(max = 32)
    private String status;

    @Size(max = 64)
    private String relatedBizType;

    @Size(max = 96)
    private String relatedBizNo;

    @Size(max = 64)
    private String submittedBy;

    @Size(max = 2048)
    private String metadataJson;
}
