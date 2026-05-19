package ffdd.auth.dto;

import lombok.Data;

@Data
public class RoleQueryRequest {
    private String roleCode;
    private String roleName;
    private Integer status;
}

