package ffdd.system.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import lombok.Data;

@Data
public class SupportTicketCreateRequest {
    @NotBlank
    @Size(max = 32)
    private String category;

    @Size(max = 32)
    private String priority;

    @NotBlank
    @Size(max = 160)
    private String title;

    @NotBlank
    @Size(max = 4000)
    private String content;

    @Valid
    private List<SupportTicketAttachmentRequest> attachments;
}
