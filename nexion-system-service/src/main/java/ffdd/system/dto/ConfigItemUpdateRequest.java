package ffdd.system.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class ConfigItemUpdateRequest {
    @Size(max = 65535)
    private String configValue;

    @Size(max = 32)
    private String valueType;

    @Size(max = 64)
    private String configGroup;

    @Size(max = 16)
    private String visibility;

    @Size(max = 255)
    private String remark;

    @Min(0)
    @Max(1)
    private Integer status;
}
