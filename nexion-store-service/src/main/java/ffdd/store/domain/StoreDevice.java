package ffdd.store.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import ffdd.common.domain.BaseEntity;
import java.math.BigDecimal;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("nx_device")
public class StoreDevice extends BaseEntity {
    private String deviceNo;
    private String name;
    private String type;
    private String tier;
    private String status;
    private BigDecimal priceUsdt;
    private BigDecimal hashrate;
    private BigDecimal estimatedDailyUsdt;
    private BigDecimal dailyUsdt;
    private BigDecimal dailyNex;
    private Integer stock;
    private String coverUrl;
}
