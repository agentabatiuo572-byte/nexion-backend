package ffdd.auth.dto;

import lombok.Data;

@Data
public class MenuUpdateRequest {
    private String menuName;
    private String menuNameZh;
    private String menuNameEn;
    private Long parentId;
    private String routePath;
    private String icon;
    private Integer sortOrder;
    private String remark;
    private Integer status;
}
