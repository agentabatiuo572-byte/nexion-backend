package ffdd.wallet.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class PostEarningRequest {
    @NotBlank
    private String eventNo;
}
