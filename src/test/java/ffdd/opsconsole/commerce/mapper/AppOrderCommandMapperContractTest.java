package ffdd.opsconsole.commerce.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import org.apache.ibatis.annotations.Insert;
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
        assertThat(stock).contains("inventory_mode='FINITE'");
    }

    @Test
    void orderLockReadsTheHeaderItemCountUsedBySnapshotValidation() throws Exception {
        String order = ((Select) AppOrderCommandMapper.class.getMethod("lockOrder", String.class)
                .getAnnotation(Select.class)).value()[0];
        assertThat(order).contains("item_count itemCount");
    }

    @Test
    void developmentFulfillmentUsesCanonicalDeviceProjectionForSandboxAccount() throws Exception {
        String insert = String.join(" ", AppOrderCommandMapper.class
                .getMethod("insertDevelopmentDevice", String.class, Long.class, String.class, Integer.class)
                .getAnnotation(Insert.class).value());

        assertThat(insert).contains("JOIN nx_user u ON u.id=o.user_id AND u.sandbox=0")
                .contains("'PRODUCTION',''");
    }

    @Test
    void developmentPaymentLocksAndConditionallyDebitsTheCanonicalWalletWithOneVisibleLedger() throws Exception {
        String lock = String.join(" ", AppOrderCommandMapper.class
                .getMethod("lockDevelopmentWallet", Long.class)
                .getAnnotation(Select.class).value());
        String debit = String.join(" ", AppOrderCommandMapper.class
                .getMethod("debitDevelopmentWallet", Long.class, java.math.BigDecimal.class, Long.class)
                .getAnnotation(Update.class).value());
        String ledger = String.join(" ", AppOrderCommandMapper.class
                .getMethod("insertDevelopmentPurchaseLedger", String.class, Long.class,
                        java.math.BigDecimal.class, java.math.BigDecimal.class)
                .getAnnotation(Insert.class).value());

        assertThat(lock).contains("FROM nx_user_wallet", "u.sandbox=0", "FOR UPDATE");
        assertThat(debit).contains("w.usdt_available=w.usdt_available-#{amount}",
                "w.version=#{expectedVersion}", "w.usdt_available>=#{amount}", "u.sandbox=0");
        assertThat(ledger).contains("INSERT INTO nx_wallet_ledger", "'ORDER_PURCHASE'", "'OUT'",
                "'SUCCESS'", "#{balanceAfter}");
    }
}
