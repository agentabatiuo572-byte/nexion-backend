package ffdd.team.dto;

import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import lombok.Data;

@Data
public class RankTriggerRequest {
    @NotNull
    private Long userId;
    private String eventType;
    private Boolean registered = false;
    private Boolean phoneComputeConnected = false;
    private Boolean firstEarningReceived = false;
    private Boolean viewedStore = false;
    private BigDecimal lifetimeEarnedUsdt = BigDecimal.ZERO;
    private BigDecimal selfBuyUsd = BigDecimal.ZERO;
    private Integer purchasedDeviceCount = 0;
    private Integer directRefs = 0;
    private BigDecimal teamVolumeUsd = BigDecimal.ZERO;
    private Map<String, Integer> downlineRankCounts = new HashMap<>();
}

