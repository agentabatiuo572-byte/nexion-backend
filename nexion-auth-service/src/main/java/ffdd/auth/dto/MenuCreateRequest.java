package ffdd.auth.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class MenuCreateRequest {
    @NotBlank
    private String menuCode;
    @NotBlank
    private String menuName;
    private String menuNameZh;
    private String menuNameEn;
    private Long parentId;
    @NotBlank
    private String routePath;
    private String icon;
    private Integer sortOrder = 0;
    private String remark;
    private Integer status = 1;
}
