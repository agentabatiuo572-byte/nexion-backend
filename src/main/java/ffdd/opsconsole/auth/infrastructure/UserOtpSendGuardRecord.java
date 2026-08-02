package ffdd.opsconsole.auth.infrastructure;

import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.Data;

@Data
public class UserOtpSendGuardRecord {
    private String loginKey;
    private LocalDateTime lastSentAt;
    private LocalDateTime windowStartedAt;
    private int windowSendCount;
    private LocalDate dayStartedAt;
    private int daySendCount;
}
