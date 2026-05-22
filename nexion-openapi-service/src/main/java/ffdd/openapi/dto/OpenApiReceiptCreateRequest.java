package ffdd.openapi.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import lombok.Data;

@Data
public class OpenApiReceiptCreateRequest {
    @NotNull
    private Long userDeviceId;

    @NotBlank
    private String taskType;

    @NotBlank
    private String clientName;

    private BigDecimal rewardUsdt = BigDecimal.ZERO;
    private BigDecimal rewardNex = BigDecimal.ZERO;
}
