package ffdd.opsconsole.commerce.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;

class AppStorefrontActivityMapperContractTest {
    @Test
    void allStorefrontQueriesAreParameterizedAndUseOnlyCanonicalCommerceFacts() throws Exception {
        for (Method method : AppStorefrontActivityMapper.class.getDeclaredMethods()) {
            Select select = method.getAnnotation(Select.class);
            if (select == null) continue;
            String sql = String.join("\n", select.value()).toLowerCase();
            assertThat(sql).doesNotContain("${")
                    .doesNotContain("nx_behavior_sandbox")
                    .doesNotContain("wallet_address")
                    .doesNotContain("order_no,")
                    .doesNotContain("user_id,");
        }
    }

    @Test
    void activityQueryHasStableCursorOrderingAndEnvironmentPredicate() throws Exception {
        Method method = AppStorefrontActivityMapper.class.getMethod(
                "recentActivities", boolean.class, java.time.LocalDateTime.class, Long.class, int.class);
        String sql = String.join("\n", method.getAnnotation(Select.class).value());
        assertThat(sql)
                .contains("nx_order_item")
                .contains("nx_order")
                .contains("nx_product")
                .contains("u.sandbox = #{sandbox}")
                .contains("UPPER(o.order_type) = 'SINGLE'")
                .contains("NOT EXISTS")
                .contains("ORDER BY activity.occurred_at DESC, activity.activity_id DESC")
                .contains("LIMIT #{limit}")
                .doesNotContain("${");
    }

    @Test
    void productionSalesQueriesIncludeHistoricalSingleItemOrdersWithoutDoubleCountingItems() throws Exception {
        for (String name : new String[] {"salesTotal", "salesSince"}) {
            Method method = java.util.Arrays.stream(AppStorefrontActivityMapper.class.getDeclaredMethods())
                    .filter(candidate -> candidate.getName().equals(name))
                    .findFirst().orElseThrow();
            String sql = String.join("\n", method.getAnnotation(Select.class).value());
            assertThat(sql)
                    .contains("UNION ALL")
                    .contains("UPPER(o.order_type) = 'SINGLE'")
                    .contains("NOT EXISTS")
                    .contains("historical_item.order_no = o.order_no");
        }
    }

    @Test
    void userEnvironmentQueryRequiresAnActiveNonDeletedUser() throws Exception {
        Method method = AppStorefrontActivityMapper.class.getMethod("userEnvironment", Long.class);
        String sql = String.join("\n", method.getAnnotation(Select.class).value());
        assertThat(sql)
                .contains("status = 'ACTIVE'")
                .contains("is_deleted = 0");
    }

    @Test
    void sandboxSocialProofQueriesAreRunScopedAndCannotReadProductionOrders() throws Exception {
        Method product = AppStorefrontActivityMapper.class.getMethod("sandboxProduct", String.class, String.class);
        Method total = AppStorefrontActivityMapper.class.getMethod("sandboxSalesTotal", String.class, long.class);
        Method window = AppStorefrontActivityMapper.class.getMethod(
                "sandboxSalesSince", String.class, long.class, java.time.LocalDateTime.class);
        for (Method method : new Method[] {product, total, window}) {
            String sql = String.join("\n", method.getAnnotation(Select.class).value());
            assertThat(sql).contains("nx_commerce_sandbox")
                    .contains("run_id=#{runId}")
                    .contains("source='mock'")
                    .contains("source_environment='SANDBOX'")
                    .doesNotContain("nx_order");
        }
    }
}
