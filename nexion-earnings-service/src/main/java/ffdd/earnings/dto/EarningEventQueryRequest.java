package ffdd.earnings.dto;

import lombok.Data;

@Data
public class EarningEventQueryRequest {
    private Long userId;
    private Long userDeviceId;
    private String receiptNo;
    private String asset;
    private String status;
    private Long pageNum = 1L;
    private Long pageSize = 10L;
}
