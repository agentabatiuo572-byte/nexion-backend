package ffdd.compute.dto;

import java.time.LocalDateTime;
import java.util.List;
import lombok.Data;

@Data
public class NodeMapResponse {
    private long total;
    private long online;
    private long busy;
    private long degraded;
    private long offline;
    private String cacheStatus;
    private LocalDateTime generatedAt;
    private List<DeviceStatusResponse> points = List.of();
}
