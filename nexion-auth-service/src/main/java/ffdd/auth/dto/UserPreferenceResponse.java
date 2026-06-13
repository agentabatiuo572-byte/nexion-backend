package ffdd.auth.dto;

import java.time.LocalDateTime;
import lombok.Data;

@Data
public class UserPreferenceResponse {
    private Long userId;
    private Boolean soundEnabled;
    private Boolean hapticsEnabled;
    private Boolean notifyCommission;
    private Boolean notifyTeam;
    private Boolean notifyStaking;
    private Boolean notifyMarket;
    private Boolean notifyGenesis;
    private Boolean notifySystem;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
