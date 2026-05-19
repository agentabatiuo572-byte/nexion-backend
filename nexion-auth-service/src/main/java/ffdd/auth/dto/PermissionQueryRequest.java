package ffdd.auth.dto;

import lombok.Data;

@Data
public class PermissionQueryRequest {
    private String permissionCode;
    private String permissionName;
    private String resourceType;
    private String resourcePath;
}

