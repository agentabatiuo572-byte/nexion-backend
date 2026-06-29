package ffdd.opsconsole.market.facade;

public interface MarketExchangeKycReviewFacade {
    boolean releaseExchangeReview(String exchangeNo, String reason, String operator);

    boolean rejectExchangeReview(String exchangeNo, String reason, String operator);
}
