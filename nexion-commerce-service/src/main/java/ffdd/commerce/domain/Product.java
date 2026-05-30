package ffdd.commerce.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import ffdd.common.domain.BaseEntity;
import java.math.BigDecimal;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("nx_product")
public class Product extends BaseEntity {
    private String productNo;
    private String name;
    private String productType;
    private String tier;
    private String status;
    private BigDecimal priceUsdt;
    private BigDecimal hashrate;
    private BigDecimal estimatedDailyUsdt;
    private BigDecimal dailyNex;
    private Integer stock;
    private String coverUrl;
    private String detailImageUrls;
}
