package ffdd.team.dto;

import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import lombok.Data;

@Data
public class BinaryCommissionRequest {
    @NotNull
    private Long userId;
    @NotNull
    private BigDecimal leftVolumeUsd;
    @NotNull
    private BigDecimal rightVolumeUsd;
}

