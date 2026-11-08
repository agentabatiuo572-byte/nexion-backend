package ffdd.opsconsole.user.domain;

import java.time.LocalDateTime;

/** C2 identity and account-control facts only; no C1 risk, balance or contact fields. */
public record UserAccountActionListView(
        Long id,
        String userNo,
        String nickname,
        String status,
        LocalDateTime registeredAt,
        String freezeSource,
        String freezeSourceRef,
        String freezeReason,
        String freezeOperator,
        LocalDateTime frozenAt,
        long d2FrozenWithdrawalCount) {
}
