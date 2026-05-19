package ffdd.team.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class CultivationCommissionRequest {
    @NotNull
    private Long userId;
    @NotNull
    private Long promotedUserId;
    private String promotedUserName;
    @NotBlank
    private String promotedRank;
}

