package ffdd.auth.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class RoleCreateRequest {
    @NotBlank
    private String roleCode;
    @NotBlank
    private String roleName;
    private String remark;
    private Integer status = 1;
}

