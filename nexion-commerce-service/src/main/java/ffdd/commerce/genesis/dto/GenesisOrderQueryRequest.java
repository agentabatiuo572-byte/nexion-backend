package ffdd.commerce.genesis.dto;

import lombok.Data;

@Data
public class GenesisOrderQueryRequest {
    private Long userId;
    private String status;
    private Long pageNum = 1L;
    private Long pageSize = 10L;
}
