package ffdd.opsconsole.device.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.Test;

class DeviceCatalogMapperE4SqlTest {
    @Test
    void orderQueriesUseCanonicalOrderInsteadOfLegacyShadowTable() throws Exception {
        Method page = DeviceCatalogMapper.class.getMethod(
                "pageOrders", String.class, String.class, long.class, long.class);
        String sql = String.join(" ", page.getAnnotation(Select.class).value());

        assertThat(sql).contains("FROM nx_order o").contains("ORDER_STATE_SQL".replace("ORDER_STATE_SQL", "CASE"));
        assertThat(sql).doesNotContain("nx_admin_device_order");
    }

    @Test
    void stateUpdateUsesExpectedStateCas() throws Exception {
        Method update = DeviceCatalogMapper.class.getMethod(
                "updateOrderState", String.class, String.class, String.class, java.time.LocalDateTime.class);
        String sql = String.join(" ", update.getAnnotation(Update.class).value());

        assertThat(sql).contains("#{expectedState}").contains("UPDATE nx_order o");
    }
}
