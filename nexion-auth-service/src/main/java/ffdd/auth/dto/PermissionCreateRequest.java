package ffdd.auth.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class PermissionCreateRequest {
    @NotBlank
    private String permissionCode;
    @NotBlank
    private String permissionName;
    @NotBlank
    private String resourceType;
    private String resourcePath;
    private String remark;
}

