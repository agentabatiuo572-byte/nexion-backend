package ffdd.openapi.dto;

import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class OpenApiAppOpsResponse {
    private Long id;
    private Long ownerUserId;
    private String appName;
    private String appKey;
    private String status;
    private Integer qpsLimit;
    private Integer dailyLimit;
    private String remark;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
