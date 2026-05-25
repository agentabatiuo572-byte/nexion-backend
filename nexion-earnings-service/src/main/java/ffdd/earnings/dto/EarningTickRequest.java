package ffdd.earnings.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Data;

@Data
public class EarningTickRequest {
    @Size(max = 96)
    private String tickNo;

    @NotNull
    @Positive
    private Long userId;

    @Positive
    private Long userDeviceId;

    @DecimalMin("0.000000")
    @Digits(integer = 12, fraction = 6)
    private BigDecimal rewardUsdt = BigDecimal.ZERO;

    @DecimalMin("0.000000")
    @Digits(integer = 12, fraction = 6)
    private BigDecimal rewardNex = BigDecimal.ZERO;

    private LocalDateTime tickAt;

    @Size(max = 64)
    private String source;
}
