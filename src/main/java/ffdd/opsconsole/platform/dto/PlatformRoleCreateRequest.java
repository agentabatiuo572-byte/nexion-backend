package ffdd.opsconsole.platform.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** A6 新建角色。roleCode 创建后不可改；与内置码同名会被拒。 */
public record PlatformRoleCreateRequest(
        @NotBlank @Pattern(regexp = "[A-Z][A-Z0-9_]{2,63}") String roleCode,
        @NotBlank @Size(max = 64) String roleName,
        @Size(max = 255) String remark,
        @Min(0) @Max(1) Integer status,
        @NotBlank @Size(min = 8, max = 200) String reason,
        @Size(max = 128) String operator) {
}
