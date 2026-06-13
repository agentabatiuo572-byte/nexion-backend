package ffdd.system.dto;

import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class EmergencyTamperGateResponse {
    private Long id;
    private String gateKey;
    private String gateName;
    private Integer eventCount24h;
    private String verdict;
    private String reviewReason;
    private String reviewedBy;
    private LocalDateTime reviewedAt;
    private Integer status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
