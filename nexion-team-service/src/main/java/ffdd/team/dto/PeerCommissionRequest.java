package ffdd.team.dto;

import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import lombok.Data;

@Data
public class PeerCommissionRequest {
    @NotNull
    private Long userId;
    @NotNull
    private Long sourceUserId;
    private String sourceUserName;
    @NotNull
    private BigDecimal sameRankVolumeUsd;
}

