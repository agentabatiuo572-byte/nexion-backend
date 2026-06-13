package ffdd.auth.dto;

import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class UserImpersonationSessionResponse {
    private Long userId;
    private String sessionNo;
    private String status;
    private String operator;
    private String reason;
    private Integer ttlMinutes;
    private LocalDateTime startedAt;
    private LocalDateTime expiresAt;
    private LocalDateTime endedAt;
}
