package ffdd.commerce.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import ffdd.common.domain.BaseEntity;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("nx_tradein_application")
public class TradeinApplication extends BaseEntity {
    private String tradeinNo;
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
    private String status;
    private String reviewNote;
    private String reviewer;
    private LocalDateTime submittedAt;
    private LocalDateTime reviewedAt;
}
