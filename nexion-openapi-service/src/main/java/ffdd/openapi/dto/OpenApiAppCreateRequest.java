package ffdd.openapi.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;

@Data
public class OpenApiAppCreateRequest {
    @NotBlank
    private String appName;

    @Min(1)
    @Max(1000)
    private Integer qpsLimit;

    @Min(1)
    @Max(10000000)
    private Integer dailyLimit;

    private String remark;
}
