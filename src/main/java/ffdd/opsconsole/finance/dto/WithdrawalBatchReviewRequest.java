package ffdd.opsconsole.finance.dto;

import java.util.List;

public record WithdrawalBatchReviewRequest(
        String action,
        List<String> withdrawalIds,
        String operator,
        String reason,
        String reasonCode,
        Integer holdDays,
        String period,
        String owner,
        String reviewAt) {

    public WithdrawalReviewRequest toSingleRequest() {
        return new WithdrawalReviewRequest(
                action, operator, reason, reasonCode, holdDays, period, owner, reviewAt, false, false);
    }
}
