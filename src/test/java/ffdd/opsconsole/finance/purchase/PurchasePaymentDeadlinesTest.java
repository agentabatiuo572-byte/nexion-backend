package ffdd.opsconsole.finance.purchase;

import static org.assertj.core.api.Assertions.*;
import java.time.*;
import org.junit.jupiter.api.Test;

class PurchasePaymentDeadlinesTest {
    @Test void graceNeverExtendsThePaymentWindowAndExactBoundariesAreClosed(){
        Instant deadline=Instant.parse("2026-11-01T00:30:00Z");
        for(String method:java.util.List.of("CREGIS_USDT_BSC","CREGIS_USDT_ERC20","HDPAY_BANK")){
            Instant grace=PurchasePaymentService.graceEnd(method,deadline);
            assertThat(Duration.between(deadline,grace).toSeconds()).isEqualTo(method.equals("CREGIS_USDT_BSC")?180:300);
            assertThat(PurchasePaymentService.arrivalReason(deadline,grace,deadline.minusNanos(1),grace.minusNanos(1))).isEmpty();
            assertThat(PurchasePaymentService.arrivalReason(deadline,grace,deadline,deadline.plusSeconds(1))).isEqualTo("PAYMENT_LATE_REVIEW");
            assertThat(PurchasePaymentService.arrivalReason(deadline,grace,deadline.minusSeconds(1),grace)).isEqualTo("PAYMENT_LATE_REVIEW");
            assertThat(PurchasePaymentService.arrivalReason(deadline,grace,null,deadline)).isEqualTo("PAYMENT_TIME_UNVERIFIED");
            assertThat(PurchasePaymentService.arrivalReason(deadline,grace,deadline.minusSeconds(1),deadline.minusSeconds(2))).isEqualTo("PAYMENT_LATE_REVIEW");
            assertThat(PurchasePaymentService.arrivalReason(deadline,grace,null,deadline.minusNanos(1))).isEmpty();
        }
    }
}
