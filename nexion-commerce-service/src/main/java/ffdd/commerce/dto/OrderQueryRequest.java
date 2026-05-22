package ffdd.commerce.dto;

import lombok.Data;

@Data
public class OrderQueryRequest {
    private Long userId;
    private String orderNo;
    private String paymentStatus;
    private String orderStatus;
    private Long pageNum = 1L;
    private Long pageSize = 10L;
}
