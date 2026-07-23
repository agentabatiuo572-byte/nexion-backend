package ffdd.opsconsole.platform.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** A7 新建菜单节点。menuCode 创建后不可改；parentCode 空=顶级域。 */
public record PlatformMenuNodeCreateRequest(
        @NotBlank @Pattern(regexp = "[A-Z][A-Z0-9_]{0,63}") String menuCode,
        @NotBlank @Size(max = 64) String menuName,
        @Size(max = 64) String menuNameZh,
        @Pattern(regexp = "[A-Z][A-Z0-9_]{0,63}") String parentCode,
        @Size(max = 255) @Pattern(regexp = "^/.*") String routePath,
        @Size(max = 64) String icon,
        @Min(0) @Max(100000) Integer sortOrder,
        @NotBlank @Size(min = 8, max = 200) String reason,
        @Size(max = 128) String operator) {
}
