package ffdd.opsconsole.growth.application;

import ffdd.opsconsole.promotion.mapper.PromotionMapper;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class EventDeviceGiftServiceTest {
    private final PromotionMapper db = mock(PromotionMapper.class);
    private final EventDeviceGiftService service = new EventDeviceGiftService(db);
    private Map<String,Object> product;

    @BeforeEach void setUp() {
        product = new HashMap<>(Map.of("product_type", "SERVER", "status", "ACTIVE", "vram_total_gb", 16,
                "inventory_mode", "FINITE", "stock", 3));
        when(db.product("SERVER-A", true)).thenReturn(product);
        when(db.requiredRow(contains("nx_admin_device_sku"), eq("SERVER-A")))
                .thenReturn(Map.of("power_text", "200 W", "datacenter", "DC1"));
    }

    @Test void validatesCombinedPrizeStockWithoutConsumingIt() {
        assertThatCode(() -> service.validatePrize("SERVER-A", 3)).doesNotThrowAnyException();
        assertThatThrownBy(() -> service.validatePrize("SERVER-A", 4)).hasMessageContaining("H4_EVENT_GIFT_STOCK_UNAVAILABLE");
        verify(db, atLeastOnce()).product("SERVER-A", true);
        verify(db, atLeastOnce()).requiredRow(contains("FOR UPDATE"), eq("SERVER-A"));
        verify(db, never()).write(anyString(), any(Object[].class));
    }

    @Test void rejectsUnavailableUnsupportedAndIncompleteDeviceRights() {
        product.put("status", "OFF_SHELF");
        assertThatThrownBy(() -> service.validatePrize("SERVER-A", 1)).hasMessageContaining("H4_EVENT_GIFT_PRODUCT_UNAVAILABLE");
        product.put("status", "ACTIVE"); product.put("product_type", "CLOUD_SHARE");
        assertThatThrownBy(() -> service.validatePrize("SERVER-A", 1)).hasMessageContaining("H4_EVENT_GIFT_PRODUCT_UNAVAILABLE");
        product.put("product_type", "DEVICE");
        when(db.requiredRow(contains("nx_admin_device_sku"), eq("SERVER-A"))).thenReturn(Map.of("power_text", "unknown", "datacenter", "DC1"));
        assertThatThrownBy(() -> service.validatePrize("SERVER-A", 1)).hasMessageContaining("H4_EVENT_GIFT_PRODUCT_UNAVAILABLE");
    }

    @Test void rejectsInventoryThatTheDeliveryExecutorCannotConsume() {
        product.put("inventory_mode", "UNLIMITED");
        assertThatThrownBy(() -> service.validatePrize("SERVER-A", 1)).hasMessageContaining("H4_EVENT_GIFT_STOCK_UNAVAILABLE");
        product.put("inventory_mode", "FINITE"); product.put("stock", 0);
        assertThatThrownBy(() -> service.validatePrize("SERVER-A", 1)).hasMessageContaining("H4_EVENT_GIFT_STOCK_UNAVAILABLE");
    }

    @Test void boundsTheCombinedPrizeQuantityBeforeLockingProducts() {
        for(int quantity : new int[]{0, -1, 101})
            assertThatThrownBy(() -> service.validatePrize("SERVER-A", quantity)).hasMessageContaining("H4_EVENT_GIFT_QUANTITY_INVALID");
        verify(db, never()).product(anyString(), anyBoolean());
    }
}
