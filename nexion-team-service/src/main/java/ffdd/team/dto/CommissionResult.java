package ffdd.team.dto;

import ffdd.team.domain.CommissionEvent;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class CommissionResult {
    private String commissionType;
    private Integer generatedCount;
    private List<CommissionEvent> events;
}

