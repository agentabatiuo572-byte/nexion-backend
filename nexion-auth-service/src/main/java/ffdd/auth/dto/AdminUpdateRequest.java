package ffdd.auth.dto;

import lombok.Data;

@Data
public class AdminUpdateRequest {
    private String nickname;
    private String email;
    private String phone;
    private Integer superAdmin;
    private Integer status;
}

