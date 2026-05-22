package ffdd.wallet.dto;

import lombok.Data;

@Data
public class LedgerQueryRequest {
    private Long userId;
    private String bizNo;
    private String asset;
    private String direction;
    private String status;
    private Long pageNum = 1L;
    private Long pageSize = 10L;
}
