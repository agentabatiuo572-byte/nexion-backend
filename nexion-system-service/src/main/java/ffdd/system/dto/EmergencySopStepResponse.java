package ffdd.system.dto;

import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class EmergencySopStepResponse {
    private Long id;
    private String sopId;
    private Integer stepOrder;
    private String stepTitle;
    private String status;
    private String statusReason;
    private String operator;
    private LocalDateTime operatedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
