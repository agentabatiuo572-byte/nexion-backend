package ffdd.commerce.client.dto;

import java.math.BigDecimal;
import lombok.Data;

@Data
public class WalletLedgerResponse {
    private Long id;
    private Long userId;
    private String bizNo;
    private String bizType;
    private String asset;
    private String direction;
    private BigDecimal amount;
    private BigDecimal balanceAfter;
    private String status;
}
