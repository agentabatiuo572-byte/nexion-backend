package ffdd.compliance.dto;

import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;
import lombok.Data;

@Data
public class KycProfileReviewRequest {
    @Size(max = 64)
    private String reviewer;

    @Size(max = 255)
    private String reason;

    private LocalDateTime expiresAt;
}
