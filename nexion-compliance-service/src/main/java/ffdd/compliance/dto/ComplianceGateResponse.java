package ffdd.compliance.dto;

import lombok.Data;

@Data
public class ComplianceGateResponse {
    private Long decisionId;
    private String decision;
    private String reason;
}
