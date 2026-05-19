package ffdd.team.dto;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class RankUpgradeResult {
    private Long userId;
    private String eventType;
    private String oldUserLevel;
    private String newUserLevel;
    private String oldVRank;
    private String newVRank;
    private Boolean userLevelUpgraded;
    private Boolean vRankUpgraded;
    private List<String> matchedReasons;
    private List<String> missing;
}

