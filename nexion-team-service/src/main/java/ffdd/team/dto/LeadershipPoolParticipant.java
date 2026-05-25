package ffdd.team.dto;

import java.math.BigDecimal;
import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class LeadershipPoolParticipant {
    private Long userId;
    private String rankCode;
    private int votes;
    private BigDecimal estimatedShareUsdt;
}
