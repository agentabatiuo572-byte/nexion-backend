package ffdd.wallet.dto;

import lombok.Data;

@Data
public class WalletOrderQueryRequest {
    private Long userId;
    private String status;
    private Long pageNum = 1L;
    private Long pageSize = 10L;
}
