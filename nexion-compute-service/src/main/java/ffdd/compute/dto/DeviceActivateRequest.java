package ffdd.compute.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import lombok.Data;

@Data
public class DeviceActivateRequest {
    @NotNull
    private Long userId;

    @NotBlank
    private String sourceOrderNo;

    private Long productId;

    @NotBlank
    private String productName;

    @NotBlank
    private String deviceType;

    private BigDecimal hashrate = BigDecimal.ZERO;
    private BigDecimal dailyUsdt = BigDecimal.ZERO;
    private BigDecimal dailyNex = BigDecimal.ZERO;

    @Min(1)
    private Integer quantity = 1;
}
