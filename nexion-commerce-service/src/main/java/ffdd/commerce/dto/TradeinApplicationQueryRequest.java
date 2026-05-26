package ffdd.commerce.dto;

import lombok.Data;

@Data
public class TradeinApplicationQueryRequest {
    private Long userId;
    private String status;
    private Long pageNum = 1L;
    private Long pageSize = 10L;
}
