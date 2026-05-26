package ffdd.commerce.client.dto;

import java.math.BigDecimal;
import lombok.Data;

@Data
public class PostWalletDebitRequest {
    private Long userId;
    private String bizNo;
    private String bizType;
    private String asset;
    private BigDecimal amount;
    private String remark;
}
