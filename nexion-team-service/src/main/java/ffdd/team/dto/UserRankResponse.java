package ffdd.team.dto;

import ffdd.team.domain.UserLevelConfig;
import ffdd.team.domain.VRankConfig;
import java.math.BigDecimal;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class UserRankResponse {
    private String currentLevel;
    private String currentVRank;
    private String nextVRank;
    private BigDecimal progressPct;
    private List<String> missing;
    private List<UserLevelConfig> userLevels;
    private List<VRankConfig> vRanks;
}

