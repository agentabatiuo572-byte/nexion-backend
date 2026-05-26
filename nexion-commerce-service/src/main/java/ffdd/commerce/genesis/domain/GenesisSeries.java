package ffdd.commerce.genesis.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import ffdd.common.domain.BaseEntity;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("nx_genesis_series")
public class GenesisSeries extends BaseEntity {
    private String seriesCode;
    private String name;
    private Integer totalSupply;
    private Integer soldSupply;
    private BigDecimal priceUsdt;
    private String status;
    private LocalDateTime saleStartAt;
    private LocalDateTime saleEndAt;
    private Integer royaltyBps;
    private String coverUrl;
    private String metadataJson;
}
