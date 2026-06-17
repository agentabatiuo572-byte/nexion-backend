package ffdd.opsconsole.finance.dto;

public record WithdrawalReviewRequest(
        String action,
        String operator,
        String reason) {
}
