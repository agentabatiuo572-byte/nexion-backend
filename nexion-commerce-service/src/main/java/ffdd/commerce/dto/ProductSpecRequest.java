package ffdd.commerce.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class ProductSpecRequest {
    @NotNull
    private Long productId;
    private String specGroup;
    @NotBlank
    private String specKey;
    @NotBlank
    private String specValue;
    private String unit;
    private String status;
    private Integer sortOrder;
}
