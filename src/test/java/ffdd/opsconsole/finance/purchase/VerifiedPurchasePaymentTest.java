package ffdd.opsconsole.finance.purchase;

import static org.assertj.core.api.Assertions.*;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class VerifiedPurchasePaymentTest {
    @Test void requiresExactOwnerAndFrozenTwoDecimalPriceAndAlwaysClearsScope(){
        assertThatThrownBy(()->VerifiedPurchasePayment.require(7,BigDecimal.TEN)).hasMessage("PURCHASE_PAYMENT_PRICING_CHANGED");
        VerifiedPurchasePayment.settle(7,"CREGIS_USDT_BSC","intent-test",new BigDecimal("10.01"),()->{
            VerifiedPurchasePayment.require(7,new BigDecimal("10.005"));
            assertThatThrownBy(()->VerifiedPurchasePayment.require(8,new BigDecimal("10.01"))).hasMessage("PURCHASE_PAYMENT_PRICING_CHANGED");
            assertThatThrownBy(()->VerifiedPurchasePayment.require(7,new BigDecimal("10.00"))).hasMessage("PURCHASE_PAYMENT_PRICING_CHANGED");
            assertThat(VerifiedPurchasePayment.method()).isEqualTo("CREGIS_USDT_BSC");return null;
        });
        assertThat(VerifiedPurchasePayment.active()).isFalse();
        assertThatThrownBy(()->VerifiedPurchasePayment.settle(7,"HDPAY_BANK","intent-test",BigDecimal.ONE,()->{throw new IllegalStateException("TEST_FAILURE");})).hasMessage("TEST_FAILURE");
        assertThat(VerifiedPurchasePayment.active()).isFalse();
    }
    @Test void topupPrincipalNeverRoundsFractionalOrNonpositiveAmounts(){
        for(String value:java.util.List.of("0","-1","0.1","10.000001","abc"))
            assertThatThrownBy(()->PurchasePaymentPolicyService.principal(value)).hasMessage("PURCHASE_PAYMENT_POLICY_INVALID");
        assertThat(PurchasePaymentPolicyService.principal("10.000000")).isEqualTo(new BigDecimal("10"));
    }
}
