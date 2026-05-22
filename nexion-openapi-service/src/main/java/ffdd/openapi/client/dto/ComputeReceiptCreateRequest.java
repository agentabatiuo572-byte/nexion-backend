package ffdd.openapi.client.dto;

import java.math.BigDecimal;
import lombok.Data;

@Data
public class ComputeReceiptCreateRequest {
    private Long userDeviceId;
    private String taskType;
    private String clientName;
    private BigDecimal rewardUsdt;
    private BigDecimal rewardNex;
}
