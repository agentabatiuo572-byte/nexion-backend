package ffdd.compute.dto;

import lombok.Data;

@Data
public class ReceiptQueryRequest {
    private Long userId;
    private Long userDeviceId;
    private String taskType;
    private Long pageNum = 1L;
    private Long pageSize = 10L;
}
