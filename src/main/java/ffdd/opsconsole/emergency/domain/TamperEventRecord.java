package ffdd.opsconsole.emergency.domain;

import java.time.LocalDateTime;

/** Canonical projection of one server-side client-tamper rejection. */
public record TamperEventRecord(
        String eventNo,
        Long userId,
        String userNo,
        String pathKey,
        String pathName,
        String attackEffect,
        String blockedAtEndpoint,
        boolean k4Accepted,
        int k4Delta,
        boolean b5Accepted,
        int eventCount,
        LocalDateTime occurredAt) {
}
