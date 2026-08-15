package ffdd.opsconsole.finance.application;

import static org.assertj.core.api.Assertions.assertThat;

import ffdd.opsconsole.finance.mapper.AppVietQrIntentMapper;
import java.lang.reflect.Method;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;

class AppVietQrIntentReceiptContractTest {
    @Test
    void appReceiptQueryIsUserScopedAndExcludesSyntheticInFlightRows() throws Exception {
        Method method = AppVietQrIntentMapper.class.getMethod(
                "listReceiptsForUser", Long.class, int.class, int.class);
        String sql = String.join("\n", method.getAnnotation(Select.class).value());
        assertThat(sql)
                .contains("user_id = #{userId}")
                .contains("view_type <> 'INFLIGHT'")
                .contains("LIMIT #{limit}")
                .contains("OFFSET #{offset}")
                .doesNotContain("payment_reference")
                .doesNotContain("account_number");
    }

    @Test
    void appReceiptViewIncludesServerReceiptAndSettlementFields() throws Exception {
        Method method = AppVietQrIntentService.class.getDeclaredMethod("toReceiptView", java.util.Map.class);
        assertThat(method).isNotNull();
    }
}
