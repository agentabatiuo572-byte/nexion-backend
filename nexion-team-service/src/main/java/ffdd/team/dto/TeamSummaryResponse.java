package ffdd.team.dto;

import java.math.BigDecimal;
import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class TeamSummaryResponse {
    private String vRank;
    private BigDecimal teamVolume;
    private Integer directCount;
    private Integer totalMembers;
    private BigDecimal commissionUsdt;
}

