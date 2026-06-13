package ffdd.compute.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class TrialClaimRequest {
    @Size(max = 96)
    private String clientRequestNo;
}
