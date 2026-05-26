package ffdd.commerce.dto;

import lombok.Data;

@Data
public class PaymentRecordQueryRequest {
    private Long userId;
    private String orderNo;
    private String paymentNo;
    private String provider;
    private String paymentStatus;
    private Long pageNum = 1L;
    private Long pageSize = 10L;
}
