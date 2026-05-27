package ffdd.system.dto;

import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class ConfigItemResponse {
    private Long id;
    private String configKey;
    private String configValue;
    private String valueType;
    private String configGroup;
    private String visibility;
    private String remark;
    private Integer status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
