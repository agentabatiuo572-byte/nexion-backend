package ffdd.auth.dto;

import java.util.List;
import lombok.Data;

@Data
public class AssignRolePermissionsRequest {
    private List<Long> permissionIds;
}
