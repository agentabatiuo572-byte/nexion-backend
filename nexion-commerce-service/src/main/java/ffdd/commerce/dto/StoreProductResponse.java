package ffdd.commerce.dto;

import java.math.BigDecimal;
import java.util.List;
import lombok.Data;

@Data
public class StoreProductResponse {
    private String id;
    private Long productId;
    private String productNo;
    private String name;
    private String tier;
    private String badge;
    private String tagline;
    private String photo;
    private List<String> detailMediaUrls;
    private Boolean featured;
    private String tierCode;
    private BigDecimal dailyEarn;
    private BigDecimal dailyEarnNEX;
    private Integer annualROI;
    private BigDecimal price;
    private Integer sold;
    private Integer stock;
    private BigDecimal rating;
    private Integer reviews;
    private String gpu;
    private String vram;
    private StoreAiPerformance ai;
    private Object detailMetrics;
    private Object hardwareSpecs;
    private Object reviewSummary;
    private Object reviewItems;
    private Object trust;
    private Object faqItems;
    private Object phoneCompare;
    private BigDecimal shareYieldMin;
    private BigDecimal shareYieldMax;
    private Integer generation;
    private String status;
    private String supersededBy;
    private BigDecimal tradeinCredit;
    private String unlocksAtPhase;
}
