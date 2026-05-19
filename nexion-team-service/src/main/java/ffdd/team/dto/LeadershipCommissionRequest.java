package ffdd.team.dto;

import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import lombok.Data;

@Data
public class LeadershipCommissionRequest {
    @NotNull
    private Long userId;
    @NotNull
    private String vRank;
    @NotNull
    private BigDecimal weeklyPlatformVolumeUsd;
    @NotNull
    private Integer totalVotes;
}

