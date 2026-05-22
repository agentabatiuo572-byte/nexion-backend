package ffdd.bff.dto;

import java.time.LocalDateTime;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class BffSnapshot {
    private Long userId;
    private String view;
    private String cacheStatus;
    private LocalDateTime generatedAt;
    private Map<String, Object> payload;
}
