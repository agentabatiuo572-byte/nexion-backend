package ffdd.compliance.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class KycDocumentUploadRequest {
    @NotNull
    private Long userId;

    @Size(max = 96)
    private String kycNo;

    @NotBlank
    @Size(max = 64)
    private String country;

    @Size(max = 128)
    private String applicantName;

    @NotBlank
    @Size(max = 64)
    private String documentType;

    @Size(max = 16)
    private String documentLast4;
}
