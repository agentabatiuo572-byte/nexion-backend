package ffdd.commerce.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class ProductFaqRequest {
    @NotNull
    private Long productId;
    @NotBlank
    private String question;
    @NotBlank
    private String answer;
    private String status;
    private Integer sortOrder;
}
