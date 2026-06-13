package ffdd.wallet.dto;

import lombok.Data;

@Data
public class StakingPositionQueryRequest {
    private Long pageNum;
    private Long pageSize;
    private Long userId;
    private String status;
}
