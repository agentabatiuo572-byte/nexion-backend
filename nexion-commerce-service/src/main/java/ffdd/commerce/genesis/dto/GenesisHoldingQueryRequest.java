package ffdd.commerce.genesis.dto;

import lombok.Data;

@Data
public class GenesisHoldingQueryRequest {
    private Long userId;
    private String seriesCode;
    private Long pageNum = 1L;
    private Long pageSize = 10L;
}
