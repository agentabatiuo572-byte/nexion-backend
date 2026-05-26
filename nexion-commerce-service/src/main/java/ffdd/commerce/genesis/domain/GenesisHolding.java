package ffdd.commerce.genesis.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import ffdd.common.domain.BaseEntity;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("nx_genesis_holding")
public class GenesisHolding extends BaseEntity {
    private String holdingNo;
    private Long userId;
    private String orderNo;
    private String seriesCode;
    private BigDecimal acquiredPriceUsdt;
    private String status;
    private LocalDateTime acquiredAt;
}
