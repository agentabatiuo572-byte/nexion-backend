package ffdd.opsconsole.platform.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;

/** A6 改角色权限/菜单绑定（核心 HIGH）。permissionCodes + menuIds 为全量白名单（同步差量）。 */
public record PlatformRoleGrantsUpdateRequest(
        @Size(max = 2000) List<@NotBlank @Size(max = 128) String> permissionCodes,
        @Size(max = 2000) List<@Positive Long> menuIds,
        @NotBlank @Size(min = 8, max = 200) String reason,
        @Size(max = 128) String operator) {
}
