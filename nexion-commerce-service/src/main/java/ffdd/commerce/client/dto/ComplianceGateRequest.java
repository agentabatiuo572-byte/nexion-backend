package ffdd.commerce.client.dto;

import java.math.BigDecimal;
import lombok.Data;

@Data
public class ComplianceGateRequest {
    private Long userId;
    private String bizType;
    private String bizNo;
    private String asset;
    private BigDecimal amount;
}
