package ffdd.opsconsole.user.domain;

import java.time.LocalDateTime;

/** Readback of the authoritative C2 freeze fact and its D2 impact. */
public record UserAccountControlFactView(
        Long userId,
        String freezeSource,
        String freezeSourceRef,
        String freezeReason,
        String freezeOperator,
        LocalDateTime frozenAt,
        long d2FrozenWithdrawalCount) {
}
