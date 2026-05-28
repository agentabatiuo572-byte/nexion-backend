package ffdd.system.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class SupportTicketOpsUpdateRequest {
    @Size(max = 32)
    private String status;

    @Size(max = 32)
    private String priority;

    @Size(max = 32)
    private String category;

    private Long assignedAdminId;

    @Size(max = 64)
    private String assignedAdminName;
}
