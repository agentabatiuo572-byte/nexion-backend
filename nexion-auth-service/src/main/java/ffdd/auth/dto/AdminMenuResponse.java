package ffdd.auth.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AdminMenuResponse {
    private Long id;
    private String menuCode;
    private String menuName;
    private String menuNameZh;
    private String menuNameEn;
    private Long parentId;
    private String routePath;
    private String icon;
    private Integer sortOrder;
    private Integer status;
}
