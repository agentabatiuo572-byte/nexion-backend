package ffdd.auth.dto;

import lombok.Data;

@Data
public class AdminQueryRequest {
    private String username;
    private String phone;
    private Integer status;
    private Integer superAdmin;
}

