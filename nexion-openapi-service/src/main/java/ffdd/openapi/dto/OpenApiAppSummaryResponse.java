package ffdd.openapi.dto;

import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class OpenApiAppSummaryResponse {
    private Long id;
    private String appName;
    private String appKey;
    private String status;
    private Integer qpsLimit;
    private Integer dailyLimit;
    private String remark;
    private LocalDateTime createdAt;
}
