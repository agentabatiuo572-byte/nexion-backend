package ffdd.mission.dto;

import ffdd.common.api.PageResult;
import ffdd.mission.domain.PointsLedger;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PointsSummaryResponse {
    private Long userId;
    private int totalPoints;
    private PageResult<PointsLedger> recentLedgers;
}
