package ffdd.auth.dto;

import java.util.List;
import lombok.Data;

@Data
public class AssignRoleMenusRequest {
    private List<Long> menuIds;
}
