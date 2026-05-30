package ffdd.auth.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class UserUpdateRequest {
    @Size(max = 64)
    private String nickname;

    @Size(max = 512)
    private String avatarUrl;

    @Size(max = 16)
    private String language;

    @Size(max = 32)
    private String region;
}
