package ffdd.auth.dto;

import jakarta.validation.constraints.NotEmpty;
import java.util.List;
import lombok.Data;

@Data
public class AssignRolePermissionsRequest {
    @NotEmpty
    private List<Long> permissionIds;
}

