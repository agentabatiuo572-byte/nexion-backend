package ffdd.opsconsole.market.infrastructure;

import com.baomidou.mybatisplus.annotation.TableName;
import ffdd.opsconsole.shared.domain.BaseEntity;
import java.math.BigDecimal;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("nx_genesis_series")
public class GenesisSeriesEntity extends BaseEntity {
    private String seriesCode;
    private String name;
    private Integer totalSupply;
    private Integer soldSupply;
    private BigDecimal priceUsdt;
    private String status;
    private Integer royaltyBps;
}
