package ffdd.commerce.dto;

import lombok.Data;

@Data
public class ProductWaitlistQueryRequest {
    private Long productId;
    private Long userId;
    private String status;
    private Long pageNum = 1L;
    private Long pageSize = 20L;
}
