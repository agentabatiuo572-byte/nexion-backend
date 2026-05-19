package ffdd.store.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class OrderCreateRequest {
    @NotNull
    private Long deviceId;
    private Integer quantity = 1;
    private String payToken = "USDT";
    private String network = "TRON";
}
