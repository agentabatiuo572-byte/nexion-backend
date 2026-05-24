package ffdd.compliance.client.dto;

import lombok.Data;

@Data
public class WalletRiskDecisionApplyRequest {
    private Long decisionId;
    private String decisionNo;
    private String bizType;
    private String bizNo;
    private String decision;
    private String reason;
}
