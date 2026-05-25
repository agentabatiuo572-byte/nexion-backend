package ffdd.earnings.dto;

import jakarta.validation.constraints.NotNull;
import java.time.LocalDateTime;
import lombok.Data;

@Data
public class MissedIncomeQueryRequest {
    @NotNull
    private Long userId;

    @NotNull
    private LocalDateTime joinedAt;

    private LocalDateTime calculatedAt;
}
