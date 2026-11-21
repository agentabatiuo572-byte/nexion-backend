package ffdd.opsconsole.finance.purchase;

import ffdd.opsconsole.shared.exception.BizException;
import java.util.List;

/** Server-owned purchase terms, bound to an intent even when neither quota applies. */
public record PurchaseQuotaReservation(String intentNo, long owner, String kind, String businessId,
                                       State state, String decidedAtUtc, String monthStartUtc, List<Line> lines) {
    public enum State { HELD, CONSUMED, RELEASED }
    public enum Source { PPI_NEW, ORDER_PRESERVED, NONE }

    public record Line(long productId, String productNo, int quantity, List<Long> tierIds,
                       Source source, String skuId, Long generation) { }

    public void requireSingle(long productId, String productNo) {
        if (lines.size() != 1 || lines.get(0).quantity() != 1 || lines.get(0).productId() != productId
                || !lines.get(0).productNo().equals(productNo)) {
            throw conflict();
        }
    }

    PurchaseQuotaReservation withState(State next) {
        return new PurchaseQuotaReservation(intentNo, owner, kind, businessId, next, decidedAtUtc, monthStartUtc, lines);
    }

    static BizException conflict() {
        return new BizException(409, "PURCHASE_PAYMENT_QUOTA_RESERVATION_CONFLICT");
    }
}
