package ffdd.compute.dto;

import lombok.Data;

@Data
public class DeviceQueryRequest {
    private Long userId;
    private String status;
    private String sourceOrderNo;
    private Long pageNum = 1L;
    private Long pageSize = 10L;
}
