package ffdd.commerce.client.dto;

import java.math.BigDecimal;
import lombok.Data;

@Data
public class ComputeDeviceActivateRequest {
    private Long userId;
    private String sourceOrderNo;
    private Long productId;
    private String productTier;
    private String productName;
    private String deviceType;
    private BigDecimal hashrate;
    private BigDecimal dailyUsdt;
    private BigDecimal dailyNex;
    private Integer quantity;
}
