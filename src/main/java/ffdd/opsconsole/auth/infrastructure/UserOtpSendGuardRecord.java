package ffdd.opsconsole.auth.infrastructure;

import java.time.LocalDateTime;
import lombok.Data;

@Data
public class UserOtpSendGuardRecord {
    private String loginKey;
    private LocalDateTime lastSentAt;
    private LocalDateTime windowStartedAt;
    private int windowSendCount;
    /** Start of the fixed rolling 24-hour rate window, never a calendar date. */
    private LocalDateTime dayStartedAt;
    private int daySendCount;
    /** Conservative carry-over for rows migrated from the former DATE-only counter. */
    private LocalDateTime legacyWindowUntil;
}
