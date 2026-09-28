package ffdd.opsconsole.finance.infrastructure;

import com.baomidou.mybatisplus.annotation.TableName;
import ffdd.opsconsole.shared.domain.BaseEntity;
import java.math.BigDecimal;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("nx_cregis_deposit_event")
public class CregisDepositEventEntity extends BaseEntity {
    private Long userId;
    private Long projectId;
    private Long cid;
    private String txid;
    private Integer logIndex;
    private String address;
    private BigDecimal grossAmount;
    private BigDecimal feeAmount;
    private BigDecimal netAmount;
    private Long blockNumber;
    private String blockHash;
    private Integer confirmations;
    private String status;
    private Long ledgerId;
}
