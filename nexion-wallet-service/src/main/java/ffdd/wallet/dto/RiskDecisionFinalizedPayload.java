package ffdd.wallet.dto;

import java.time.LocalDateTime;
import lombok.Data;

@Data
public class RiskDecisionFinalizedPayload {
    private Long decisionId;
    private String decisionNo;
    private Long userId;
    private String bizType;
    private String bizNo;
    private String decision;
    private String reason;
    private String reviewedBy;
    private LocalDateTime reviewedAt;
}
