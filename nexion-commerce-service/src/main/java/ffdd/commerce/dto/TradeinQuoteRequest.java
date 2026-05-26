package ffdd.commerce.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class TradeinQuoteRequest {
    @NotNull
    private Long userId;

    @NotNull
    private Long sourceDeviceId;
}
