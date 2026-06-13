package ffdd.team.dto;

import java.math.BigDecimal;
import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class LeadershipPoolRankWeight {
    private String rankCode;
    private String titleEn;
    private String titleCn;
    private int votesPerUser;
    private int userCount;
    private int totalVotes;
    private BigDecimal sharePct;
    private BigDecimal estimatedPerUserUsdt;
}
