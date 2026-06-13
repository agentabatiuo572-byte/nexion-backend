package ffdd.commerce.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class ProductFeaturedRequest {
    @Size(max = 16)
    private String currentPhase;
}
