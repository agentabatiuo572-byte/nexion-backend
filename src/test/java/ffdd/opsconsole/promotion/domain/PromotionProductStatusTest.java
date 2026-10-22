package ffdd.opsconsole.promotion.domain;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class PromotionProductStatusTest {
    @Test
    void acceptsFormalAndLegacySaleStatesButRejectsUnavailableProducts() {
        assertTrue(PromotionValues.productOnSale("ON_SALE"));
        assertTrue(PromotionValues.productOnSale("ACTIVE"));
        assertTrue(PromotionValues.productOnSale("on_sale"));
        for (String state : new String[] {"DRAFT", "OFF_SALE", "DISABLED", "DELETED", ""}) {
            assertFalse(PromotionValues.productOnSale(state));
        }
        assertFalse(PromotionValues.productOnSale(null));
    }
}
