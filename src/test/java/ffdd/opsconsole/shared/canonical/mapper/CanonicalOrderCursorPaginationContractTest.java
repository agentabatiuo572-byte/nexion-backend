package ffdd.opsconsole.shared.canonical.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;

class CanonicalOrderCursorPaginationContractTest {
    @Test
    void orderHistoryUsesAStableBoundedCursorInsteadOfAFixedFirstHundredRows() throws Exception {
        Method method = CanonicalStateMapper.class.getMethod(
                "userOrdersPage", Long.class, String.class, Integer.class);
        String sql = String.join(" ", method.getAnnotation(Select.class).value());

        assertThat(sql).contains("beforeOrderNo", "o.created_at", "o.id", "LIMIT #{limit}");
        assertThat(sql).doesNotContain("LIMIT 100");
    }
}
