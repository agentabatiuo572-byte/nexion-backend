package ffdd.opsconsole.platform.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** A6 编辑角色元数据（不含 roleCode，避免破坏 UK+外键）。字段可空=不改。 */
public record PlatformRoleUpdateRequest(
        @Size(min = 1, max = 64) String roleName,
        @Size(max = 255) String remark,
        @Min(0) @Max(1) Integer status,
        @NotBlank @Size(min = 8, max = 200) String reason,
        @Size(max = 128) String operator) {
}
