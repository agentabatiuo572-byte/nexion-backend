package ffdd.commerce.client.dto;

import java.math.BigDecimal;
import lombok.Data;

@Data
public class ComputeDeviceActivateRequest {
    private Long userId;
    private String sourceOrderNo;
    private Long productId;
    private String productCode;
    private String productTier;
    private String productName;
    private String deviceType;
    private Integer generation;
    private String gpuModel;
    private Integer vramTotalGb;
    private BigDecimal basePowerW;
    private String dcLocation;
    private BigDecimal priceUsdtSnapshot;
    private String sourceChannel;
    private BigDecimal hashrate;
    private BigDecimal dailyUsdt;
    private BigDecimal dailyNex;
    private Integer quantity;
}
