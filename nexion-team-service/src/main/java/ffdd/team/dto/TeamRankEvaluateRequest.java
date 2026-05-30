package ffdd.team.dto;

import java.math.BigDecimal;
import java.util.Map;
import lombok.Data;

@Data
public class TeamRankEvaluateRequest {
    private Long userId;
    private String eventType;
    private Boolean registered;
    private Boolean phoneComputeConnected;
    private Boolean firstEarningReceived;
    private Boolean viewedStore;
    private BigDecimal lifetimeEarnedUsdt = BigDecimal.ZERO;
    private BigDecimal selfBuyUsd = BigDecimal.ZERO;
    private Integer purchasedDeviceCount = 0;
    private Integer directRefs = 0;
    private BigDecimal teamVolumeUsd = BigDecimal.ZERO;
    private Map<String, Integer> downlineRankCounts = Map.of();
}
