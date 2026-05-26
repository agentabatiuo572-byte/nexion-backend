package ffdd.compliance.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class EvidenceUploadPolicyRequest {
    @NotNull
    private Long userId;

    @NotBlank
    @Size(max = 64)
    private String evidenceType;

    @NotBlank
    @Size(max = 255)
    private String fileName;

    @NotBlank
    @Size(max = 128)
    private String contentType;

    @NotNull
    @Min(1)
    private Long sizeBytes;
}
