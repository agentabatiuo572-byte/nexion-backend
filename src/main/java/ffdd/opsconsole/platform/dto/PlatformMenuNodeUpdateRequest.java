package ffdd.opsconsole.platform.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** A7 编辑菜单节点元数据（不含 menuCode，避免破坏 UK+外键）。字段可空=不改。 */
public record PlatformMenuNodeUpdateRequest(
        @Size(min = 1, max = 64) String menuName,
        @Size(max = 64) String menuNameZh,
        @Size(max = 255) @Pattern(regexp = "^/.*") String routePath,
        @Size(max = 64) String icon,
        @Min(0) @Max(100000) Integer sortOrder,
        @Min(0) @Max(1) Integer status,
        @NotBlank @Size(min = 8, max = 200) String reason,
        @Size(max = 128) String operator) {
}
