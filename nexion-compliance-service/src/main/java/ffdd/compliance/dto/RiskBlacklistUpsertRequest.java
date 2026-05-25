package ffdd.compliance.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;
import lombok.Data;

@Data
public class RiskBlacklistUpsertRequest {
    @NotNull
    private Long userId;

    @NotBlank
    @Size(max = 255)
    private String reason;

    @Size(max = 64)
    private String source;

    @Size(max = 32)
    private String riskLevel;

    @NotBlank
    @Size(max = 64)
    private String operator;

    private LocalDateTime expiresAt;
}
