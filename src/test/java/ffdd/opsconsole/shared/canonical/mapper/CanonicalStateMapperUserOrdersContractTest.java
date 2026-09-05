package ffdd.opsconsole.shared.canonical.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;

class CanonicalStateMapperUserOrdersContractTest {

    @Test
    void userOrderSelectColumnsFollowTheRecordConstructorOrder() throws Exception {
        String sql = String.join("\n", CanonicalStateMapper.class
                .getMethod("userOrdersPage", Long.class, String.class, Integer.class)
                .getAnnotation(Select.class)
                .value());

        List<String> aliases = List.of(
                "AS orderNo", "AS productId", "AS productNo", "AS productName",
                "o.quantity", "AS unitPriceUsdt", "AS discountUsdt", "AS amountUsdt",
                "AS paymentMethod", "AS paymentStatus", "AS orderStatus", "AS activationStatus",
                "AS orderType", "AS placedAt", "AS paidAt", "AS activatedAt",
                "AS dataCenter", "AS tradeinNo", "AS sourceDeviceId", "AS targetDeviceId",
                "AS targetDeviceInstanceNo", "AS itemCount", "AS subtotalUsdt",
                "AS refundedAt", "AS refundAmountUsdt", "AS refundChannel", "AS refundBillNo");

        int previous = -1;
        for (String alias : aliases) {
            int current = sql.indexOf(alias, previous + 1);
            assertThat(current)
                    .as("column %s must exist after the preceding UserOrder component", alias)
                    .isGreaterThan(previous);
            previous = current;
        }
    }

    @Test
    void userOrdersSelectsOnlyTrustedRefundBillsAndOneTradeinRow() throws Exception {
        String sql = String.join("\n", CanonicalStateMapper.class
                .getMethod("userOrdersPage", Long.class, String.class, Integer.class)
                .getAnnotation(Select.class)
                .value());

        assertThat(sql).contains("wb.type = 'ORDER_REFUND'")
                .contains("wb.token = 'USDT'")
                .contains("wb.direction = 'IN'")
                .contains("wb.amount > 0")
                .contains("LEFT JOIN nx_tradein_application ta")
                .contains("SELECT latest_tradein.id")
                .contains("ORDER BY latest_tradein.updated_at DESC, latest_tradein.id DESC")
                .doesNotContain("ON ta.user_id = o.user_id AND ta.target_order_no = o.order_no");
    }

    @Test
    void capacityKeepOrderReadbackLinksTheDeliveredInventoryDevice() throws Exception {
        String sql = String.join("\n", CanonicalStateMapper.class
                .getMethod("userOrdersPage", Long.class, String.class, Integer.class)
                .getAnnotation(Select.class)
                .value()).replaceAll("\\s+", " ");

        assertThat(sql).contains(
                "CASE WHEN o.order_type IN ('TRADE_IN','CAPACITY_KEEP') THEN COALESCE(ta.target_device_id, ud.id) ELSE NULL END AS targetDeviceId");
    }
}
