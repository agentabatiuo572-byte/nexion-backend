package ffdd.team.dto;

import java.math.BigDecimal;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class LeadershipPoolSnapshot {
    private Long userId;
    private String rankCode;
    private boolean unlocked;
    private BigDecimal poolRate;
    private BigDecimal platformVolumeUsdt;
    private BigDecimal poolUsdt;
    private int totalVotes;
    private int userVotes;
    private BigDecimal estimatedShareUsdt;
    private List<LeadershipPoolParticipant> participants;
    private List<LeadershipPoolRankWeight> rankWeights;
    private List<LeadershipPoolHistoryItem> history;
}
