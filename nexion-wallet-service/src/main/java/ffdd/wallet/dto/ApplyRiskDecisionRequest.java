package ffdd.wallet.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class ApplyRiskDecisionRequest {
    @NotNull
    private Long decisionId;

    @NotBlank
    @Size(max = 128)
    private String decisionNo;

    @NotBlank
    @Size(max = 32)
    private String bizType;

    @NotBlank
    @Size(max = 64)
    private String bizNo;

    @NotBlank
    @Size(max = 16)
    private String decision;

    @Size(max = 255)
    private String reason;
}
