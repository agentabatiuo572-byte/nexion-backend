package ffdd.compliance.dto;

import lombok.Data;

@Data
public class RiskDecisionSummaryResponse {
    private int days;
    private long totalDecisions;
    private long approvedDecisions;
    private long reviewDecisions;
    private long rejectedDecisions;
    private long activeBlacklists;
}
