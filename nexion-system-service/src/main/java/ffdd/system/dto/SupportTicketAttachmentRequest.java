package ffdd.system.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class SupportTicketAttachmentRequest {
    @NotBlank
    @Size(max = 512)
    private String objectKey;

    @Size(max = 255)
    private String fileName;

    @Size(max = 96)
    private String contentType;

    private Long fileSize;
}
