package ffdd.auth.dto;

import lombok.Data;

@Data
public class AdminProfileUpdateRequest {
    private String nickname;
    private String email;
    private String phone;
}
