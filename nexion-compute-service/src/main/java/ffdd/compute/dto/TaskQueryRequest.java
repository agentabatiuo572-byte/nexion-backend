package ffdd.compute.dto;

import lombok.Data;

@Data
public class TaskQueryRequest {
    private Long pageNum = 1L;
    private Long pageSize = 10L;
    private Long userId;
    private Long userDeviceId;
    private String taskType;
    private String status;
}
