package ffdd.commerce.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.Valid;
import java.util.List;
import lombok.Data;

@Data
public class OrderCreateRequest {
    private Long userId;

    private Long productId;

    @Min(1)
    private Integer quantity = 1;

    @Valid
    private List<OrderItemCreateRequest> items;
}
