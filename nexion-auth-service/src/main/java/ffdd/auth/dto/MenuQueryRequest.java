package ffdd.auth.dto;

import lombok.Data;

@Data
public class MenuQueryRequest {
    private String menuCode;
    private String menuName;
    private String routePath;
    private Long parentId;
    private Integer status;
}
