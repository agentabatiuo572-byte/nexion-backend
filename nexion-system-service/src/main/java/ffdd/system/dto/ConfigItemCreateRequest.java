package ffdd.system.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class ConfigItemCreateRequest {
    @NotBlank
    @Size(max = 128)
    private String configKey;

    @NotNull
    @Size(max = 1024)
    private String configValue;

    @Size(max = 32)
    private String valueType;

    @Size(max = 255)
    private String remark;

    @Min(0)
    @Max(1)
    private Integer status;
}
