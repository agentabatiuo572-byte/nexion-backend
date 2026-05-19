package ffdd.auth.dto;

import lombok.Data;

@Data
public class RoleUpdateRequest {
    private String roleName;
    private String remark;
    private Integer status;
}

