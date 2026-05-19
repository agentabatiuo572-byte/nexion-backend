package ffdd.auth.dto;

import lombok.Data;

@Data
public class PermissionUpdateRequest {
    private String permissionName;
    private String resourceType;
    private String resourcePath;
    private String remark;
}

