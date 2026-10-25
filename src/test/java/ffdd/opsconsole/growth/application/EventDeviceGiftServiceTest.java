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
    @Test void ratedHardwarePowerCanBeValidatedAndAwardedWithExactWatts(){
        product.put("id",2L);product.put("status","ON_SALE");
        when(db.user(7L,true)).thenReturn(Map.of("status","ACTIVE","sandbox",0));
        when(db.deviceSlotCap()).thenReturn(3L);
        when(db.write(anyString(),any(Object[].class))).thenReturn(1);
        for(var power:java.util.List.of("575W（单卡额定）","1800W（显卡额定合计）")){
            when(db.requiredRow(contains("nx_admin_device_sku"),eq("SERVER-A"))).thenReturn(Map.of("power_text",power,"datacenter","DC1"));
            service.validatePrize("SERVER-A",1);
            var receipt=service.award(7L,"source-"+power,"SERVER-A",1);
            assertThat(receipt).containsEntry("rewardType","DEVICE").containsEntry("quantity",1);
        }
        var args=org.mockito.ArgumentCaptor.forClass(Object[].class);
        verify(db,times(2)).write(contains("INSERT INTO nx_user_device"),args.capture());
        assertThat((java.math.BigDecimal)args.getAllValues().get(0)[11]).isEqualByComparingTo("575");
        assertThat((java.math.BigDecimal)args.getAllValues().get(1)[11]).isEqualByComparingTo("1800");
        verify(db,times(2)).write(startsWith("UPDATE nx_product SET stock=stock-?"),eq(1),eq(2L),eq(1));
    }
    @Test void ambiguousPowerCannotConsumeStockOrIssueDevices(){
        when(db.user(7L,true)).thenReturn(Map.of("status","ACTIVE","sandbox",0));
        when(db.deviceSlotCap()).thenReturn(3L);
        for(var power:java.util.List.of("200-250W","200W/300W","200W（单卡额定2）","0W（单卡额定）")){
            when(db.requiredRow(contains("nx_admin_device_sku"),eq("SERVER-A"))).thenReturn(Map.of("power_text",power,"datacenter","DC1"));
            assertThatThrownBy(()->service.award(7L,"real-source","SERVER-A",1)).hasMessageContaining("H4_EVENT_GIFT_PRODUCT_UNAVAILABLE");
        }
        verify(db,never()).write(anyString(),any(Object[].class));
    }
}
