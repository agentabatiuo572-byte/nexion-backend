package ffdd.opsconsole.team.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.Test;

class TeamFulfillmentCatalogSourceContractTest {

    @Test
    void skuRewardReservationUsesTheCanonicalProductInventory() throws Exception {
        Method method = TeamFulfillmentQueueMapper.class.getMethod("reserveSkuStock", String.class);
        String sql = String.join("\n", method.getAnnotation(Update.class).value());

        assertThat(sql)
                .contains("UPDATE nx_product")
                .contains("stock = CASE WHEN inventory_mode='FINITE' THEN stock - 1 ELSE stock END")
                .contains("(inventory_mode='UNLIMITED' OR stock > 0)")
                .contains("sold_count = sold_count + 1")
                .doesNotContain("nx_admin_device_sku");
    }
}
