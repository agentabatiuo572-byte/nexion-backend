package ffdd.wallet.dto;

import lombok.Data;

@Data
public class StakingProductQueryRequest {
    private Long pageNum;
    private Long pageSize;
    private String status;
    private String asset;
}
