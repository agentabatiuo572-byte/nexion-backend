package ffdd.opsconsole.finance.facade;

public interface FinanceWithdrawalKycReviewFacade {
    boolean releaseWithdrawalReview(String withdrawalNo, String ticketId, String reason, String operator);

    boolean rejectWithdrawalReview(String withdrawalNo, String ticketId, String reason, String operator);
}
