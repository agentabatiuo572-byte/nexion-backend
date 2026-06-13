package ffdd.openapi.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class OpenApiOpsAppCreateRequest {
    @NotNull
    private Long ownerUserId;

    @NotBlank
    private String appName;

    @Min(1)
    @Max(1000)
    private Integer qpsLimit;

    @Min(1)
    @Max(10000000)
    private Integer dailyLimit;

    @Size(max = 255)
    private String remark;
}
