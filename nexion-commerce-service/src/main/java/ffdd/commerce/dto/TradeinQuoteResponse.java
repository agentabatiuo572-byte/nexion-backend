package ffdd.commerce.dto;

import java.math.BigDecimal;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class TradeinQuoteResponse {
    private boolean eligible;
    private String reason;
    private Long userId;
    private Long sourceDeviceId;
    private String sourceInstanceNo;
    private Long sourceProductId;
    private String sourceProductName;
    private String sourceProductTier;
    private Long targetProductId;
    private String targetProductName;
    private String targetProductTier;
    private Integer monthsOwned;
    private BigDecimal currentEfficiency;
    private BigDecimal sourcePriceUsdt;
    private BigDecimal targetPriceUsdt;
    private BigDecimal salvageValueUsdt;
    private BigDecimal tradeinDiscountUsdt;
    private BigDecimal netUpgradeCostUsdt;
}
