package ffdd.auth.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class UserStatusUpdateRequest {
    @NotBlank
    private String status;
}
