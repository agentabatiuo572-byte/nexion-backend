package ffdd.compute.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import lombok.Data;

@Data
public class TaskCompleteRequest {
    @DecimalMin("0.0")
    private BigDecimal rewardUsdt = BigDecimal.ZERO;

    @DecimalMin("0.0")
    private BigDecimal rewardNex = BigDecimal.ZERO;

    @Size(max = 128)
    private String proofHash;

    @Size(max = 128)
    private String clientName;
}
