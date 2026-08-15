package ffdd.opsconsole.shared.canonical;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class StorefrontPurchaseGatePolicyTest {
    private final StorefrontPurchaseGatePolicy policy = new StorefrontPurchaseGatePolicy();

    @Test
    void acceptsStructuredAllGateOnlyWhenServerFactsMeetEveryCondition() {
        var gate = "{\"rankMin\":2,\"activeDirectMin\":3,\"teamVolumeMin\":5000,\"mode\":\"all\",\"enforce\":true}";
        assertThat(policy.evaluate(gate, new StorefrontPurchaseGatePolicy.Facts(2, 3, new BigDecimal("5000")))).isEqualTo(
                StorefrontPurchaseGatePolicy.Decision.open());
        assertThat(policy.evaluate(gate, new StorefrontPurchaseGatePolicy.Facts(2, 2, new BigDecimal("5000"))).code())
                .isEqualTo("PURCHASE_GATE_NOT_MET");
    }

    @Test
    void malformedOrUnknownGateFailsClosedAndQuotaCannotBeBypassed() {
        assertThat(policy.evaluate("{\"mode\":\"all\",\"enforce\":true,\"unknown\":1}", null).code())
                .isEqualTo("PURCHASE_GATE_INVALID");
        assertThat(policy.evaluate("{\"mode\":\"all\",\"enforce\":true,\"quotaCap\":2,\"quotaSold\":2}",
                new StorefrontPurchaseGatePolicy.Facts(0, 0, BigDecimal.ZERO)).code())
                .isEqualTo("PURCHASE_GATE_SOLD_OUT");
    }

    @Test
    void displayOnlyGateNeverBlocksOrConsumesQuotaAfterStructuralValidation() {
        String gate = "{\"rankMin\":12,\"quotaCap\":1,\"quotaSold\":1,\"mode\":\"all\",\"enforce\":false}";
        assertThat(policy.evaluate(gate, new StorefrontPurchaseGatePolicy.Facts(0, 0, BigDecimal.ZERO)))
                .isEqualTo(StorefrontPurchaseGatePolicy.Decision.open());
        assertThat(policy.hasQuota(gate)).isFalse();
        assertThat(policy.evaluate("{\"mode\":\"all\",\"enforce\":false,\"unknown\":1}", null).code())
                .isEqualTo("PURCHASE_GATE_INVALID");
    }
}
