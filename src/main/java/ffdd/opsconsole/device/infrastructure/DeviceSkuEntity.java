package ffdd.opsconsole.device.infrastructure;

import com.baomidou.mybatisplus.annotation.TableName;
import ffdd.opsconsole.shared.domain.BaseEntity;
import java.math.BigDecimal;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("nx_admin_device_sku")
public class DeviceSkuEntity extends BaseEntity {
    private String skuId;
    private String name;
    private String tier;
    private String tagline;
    private String badge;
    private String gpu;
    private String vram;
    private String hashRate;
    private String powerText;
    private String datacenter;
    private BigDecimal price;
    private BigDecimal dailyEarn;
    private BigDecimal dailyEarnNex;
    private BigDecimal shareYieldMin;
    private BigDecimal shareYieldMax;
    private String baseRate;
    private Long sold;
    private String stockText;
    private BigDecimal rating;
    private Long reviews;
    private Long aiImageGenPerMin;
    private Long aiLlmTokensPerSec;
    private Long aiVideoMinPerHour;
    private Long aiFineTuneMins;
    private String aiUnlocks;
    private String featuresJson;
    private Integer generation;
    private String lifecycle;
    private String supersededBy;
    private BigDecimal tradeinDiscount;
    private String unlockPhase;
    private String imageAssetId;
    private String imageObjectKey;
    private String imagePreviewUrl;
    private String tag;
    private String status;
}
