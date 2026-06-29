package ffdd.opsconsole.finance.facade;

public interface FinanceWithdrawalKycReviewFacade {
    boolean releaseWithdrawalReview(String withdrawalNo, String reason, String operator);

    boolean rejectWithdrawalReview(String withdrawalNo, String reason, String operator);
}
