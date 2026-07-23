package ffdd.opsconsole.finance.dto;

public record WithdrawalReviewRequest(
        String action,
        String operator,
        String reason,
        String reasonCode,
        Integer holdDays,
        String period,
        String owner,
        String reviewAt,
        Boolean fundsVerified,
        Boolean addressVerified) {

    public WithdrawalReviewRequest(String action, String operator, String reason) {
        this(
                action,
                operator,
                reason,
                "REJECT".equalsIgnoreCase(action) ? "OTHER" : null,
                "DELAY".equalsIgnoreCase(action) ? 7 : null,
                "FREEZE".equalsIgnoreCase(action) ? "7d" : null,
                ("DELAY".equalsIgnoreCase(action) || "FREEZE".equalsIgnoreCase(action)) ? operator : null,
                ("DELAY".equalsIgnoreCase(action) || "FREEZE".equalsIgnoreCase(action))
                        ? java.time.LocalDate.now().plusDays(7).toString() : null,
                "REFUND".equalsIgnoreCase(action) ? Boolean.TRUE : null,
                Boolean.TRUE);
    }
}
