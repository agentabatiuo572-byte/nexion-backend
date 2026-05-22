package ffdd.openapi.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class OpenApiAppCreateRequest {
    @NotBlank
    private String appName;
    private String remark;
}
