package ffdd.wallet.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class RiskDecisionApplyResult {
    private String bizType;
    private String bizNo;
    private String status;
}
