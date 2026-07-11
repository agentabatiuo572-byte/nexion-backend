package ffdd.opsconsole.platform.dto;

import java.util.List;

/** A6 角色列表。 */
public record PlatformRoleOverview(List<RoleSummary> roles, int total) {

    public record RoleSummary(
            Long id,
            String roleCode,
            String roleName,
            String remark,
            Integer status,
            boolean builtin,
            long adminCount) {
    }
}
