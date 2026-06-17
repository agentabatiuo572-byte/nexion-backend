package ffdd.opsconsole.platform.dto;

import java.time.LocalDateTime;

public record PlatformConfigResponse(
        Long id,
        String configKey,
        String configValue,
        String valueType,
        String configGroup,
        String visibility,
        String remark,
        Integer status,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
}
