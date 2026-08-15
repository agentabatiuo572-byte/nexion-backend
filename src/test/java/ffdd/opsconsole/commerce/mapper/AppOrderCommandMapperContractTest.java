package ffdd.opsconsole.commerce.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.Test;

class AppOrderCommandMapperContractTest {
    @Test
    void cancellationSqlIsUserScopedPendingOnlyAndStockReturnIsOverflowSafe() throws Exception {
        String user = ((Select) AppOrderCommandMapper.class.getMethod("lockUser", Long.class)
                .getAnnotation(Select.class)).value()[0];
        String cancel = ((Update) AppOrderCommandMapper.class.getMethod("cancelOrder", String.class, Long.class)
                .getAnnotation(Update.class)).value()[0];
        String stock = ((Update) AppOrderCommandMapper.class.getMethod("returnStock", Long.class, Integer.class)
                .getAnnotation(Update.class)).value()[0];
        assertThat(user).contains("status='ACTIVE'").contains("is_deleted=0");
        assertThat(cancel).contains("user_id=#{userId}").contains("PENDING_PAYMENT")
                .contains("UPPER(payment_status)='PENDING'");
        assertThat(stock).contains("2147483647-#{quantity}").contains("sold_count >= #{quantity}");
    }

    @Test
    void orderLockReadsTheHeaderItemCountUsedBySnapshotValidation() throws Exception {
        String order = ((Select) AppOrderCommandMapper.class.getMethod("lockOrder", String.class)
                .getAnnotation(Select.class)).value()[0];
        assertThat(order).contains("item_count itemCount");
    }
}
