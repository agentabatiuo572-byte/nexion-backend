package ffdd.system.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import lombok.Data;

@Data
public class SupportTicketReplyRequest {
    @NotBlank
    @Size(max = 4000)
    private String content;

    @Valid
    private List<SupportTicketAttachmentRequest> attachments;
}
