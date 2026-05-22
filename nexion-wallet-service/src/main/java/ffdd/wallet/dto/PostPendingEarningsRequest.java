package ffdd.wallet.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;

@Data
public class PostPendingEarningsRequest {
    @Min(1)
    @Max(500)
    private Integer limit = 100;
}
