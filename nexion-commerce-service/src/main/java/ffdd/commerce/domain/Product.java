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
    private String badge;
    private String tagline;
    private String storeStatus;
    private Integer storeVisible;
    private Integer storeFeatured;
    private Integer sortOrder;
    private Integer generation;
    private String gpuModel;
    private Integer vramTotalGb;
    private String aiPerformanceJson;
    private String detailMetricsJson;
    private String hardwareSpecsJson;
    private String reviewSummaryJson;
    private String reviewsJson;
    private String trustJson;
    private String faqJson;
    private String phoneCompareJson;
    private BigDecimal shareYieldMin;
    private BigDecimal shareYieldMax;
    private String supersededByProductNo;
    private String unlockPhase;
    private Integer soldCount;
    private BigDecimal ratingValue;
    private Integer reviewCount;
}
