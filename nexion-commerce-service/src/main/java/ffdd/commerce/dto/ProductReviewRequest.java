package ffdd.commerce.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.List;
import lombok.Data;

@Data
public class ProductReviewRequest {
    @NotNull
    private Long productId;
    private Long userId;
    private Long orderId;
    private String orderNo;
    @NotNull
    @DecimalMin("1.0")
    @DecimalMax("5.0")
    private BigDecimal rating;
    private String title;
    private String content;
    private List<String> mediaObjectKeys;
    private String avatarObjectKey;
    private String avatarColor;
    private String status;
    private Integer sortOrder;
}
