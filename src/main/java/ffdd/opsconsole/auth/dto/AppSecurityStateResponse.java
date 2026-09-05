package ffdd.opsconsole.auth.dto;

import java.time.LocalDateTime;
import java.util.List;

public record AppSecurityStateResponse(
        boolean twoFactorEnabled,
        LocalDateTime passwordChangedAt,
        List<Session> sessions,
        String nextCursor) {
    public AppSecurityStateResponse(boolean twoFactorEnabled, LocalDateTime passwordChangedAt, List<Session> sessions) {
        this(twoFactorEnabled, passwordChangedAt, sessions, null);
    }

    public record Session(
            String id,
            String deviceName,
            String ipMasked,
            LocalDateTime lastActiveAt,
            boolean current) {
    }
}
