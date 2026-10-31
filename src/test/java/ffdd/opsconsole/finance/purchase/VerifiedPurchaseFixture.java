package ffdd.opsconsole.finance.purchase;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.function.Supplier;

/** Domain tests enter after the independently verified provider/intent gate. */
public final class VerifiedPurchaseFixture {
    private VerifiedPurchaseFixture(){}
    public static <T> T settle(long owner,String method,String intentNo,BigDecimal subtotal,Instant pricingInstant,Supplier<T> work){
        return VerifiedPurchasePayment.settle(owner,method,intentNo,subtotal,pricingInstant,work);
    }
}
