package ffdd.opsconsole.platform.domain;

import java.time.LocalDateTime;

public record PlatformConfigItem(
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
    public PlatformConfigItem withValue(String value, String group, String remark, int activeStatus) {
        return new PlatformConfigItem(
                id,
                configKey,
                value,
                "STRING",
                group,
                visibility == null ? "ADMIN" : visibility,
                remark,
                activeStatus,
                createdAt,
                LocalDateTime.now());
    }
}
