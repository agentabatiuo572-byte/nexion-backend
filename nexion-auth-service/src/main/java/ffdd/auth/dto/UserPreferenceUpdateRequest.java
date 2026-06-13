package ffdd.auth.dto;

import lombok.Data;

@Data
public class UserPreferenceUpdateRequest {
    private Boolean soundEnabled;
    private Boolean hapticsEnabled;
    private Boolean notifyCommission;
    private Boolean notifyTeam;
    private Boolean notifyStaking;
    private Boolean notifyMarket;
    private Boolean notifyGenesis;
    private Boolean notifySystem;
}
