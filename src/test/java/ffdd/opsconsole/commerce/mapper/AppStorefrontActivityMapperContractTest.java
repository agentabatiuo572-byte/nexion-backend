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
                    .doesNotContain("nx_commerce_sandbox")
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
                .contains("ORDER BY COALESCE(o.paid_at, o.created_at) DESC, oi.id DESC")
                .contains("LIMIT #{limit}")
                .doesNotContain("${");
    }

    @Test
    void userEnvironmentQueryRequiresAnActiveNonDeletedUser() throws Exception {
        Method method = AppStorefrontActivityMapper.class.getMethod("userEnvironment", Long.class);
        String sql = String.join("\n", method.getAnnotation(Select.class).value());
        assertThat(sql)
                .contains("status = 'ACTIVE'")
                .contains("is_deleted = 0");
    }
}
