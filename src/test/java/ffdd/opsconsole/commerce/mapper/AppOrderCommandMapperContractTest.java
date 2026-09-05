package ffdd.opsconsole.commerce.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
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

        String release = ((Update) AppOrderCommandMapper.class
                .getMethod("releaseLifetimeQuota", String.class, Integer.class, Long.class)
                .getAnnotation(Update.class)).value()[0];
        assertThat(release).contains("'$.quotaSold'")
                .contains("-#{quantity}")
                .contains("quotaSold')) AS UNSIGNED)>=#{quantity}")
                .contains("purchase_gate_generation=#{expectedGateGeneration}");
    }

    @Test
    void orderLockReadsTheHeaderItemCountUsedBySnapshotValidation() throws Exception {
        String order = ((Select) AppOrderCommandMapper.class.getMethod("lockOrder", String.class)
                .getAnnotation(Select.class)).value()[0];
        assertThat(order).contains("item_count itemCount");
    }

    @Test
    void paymentBoundaryRejectsIncompletePhysicalProductSpecsForSingleAndBundleOrders() throws Exception {
        String productLock = String.join(" ", AppOrderCommandMapper.class
                .getMethod("lockOrderProductsForPayment", String.class)
                .getAnnotation(Select.class).value());
        String skuLock = String.join(" ", AppOrderCommandMapper.class
                .getMethod("lockOrderSkusForPayment", String.class)
                .getAnnotation(Select.class).value());
        String guard = String.join(" ", AppOrderCommandMapper.class
                .getMethod("hasNonPayableOrderProduct", String.class)
                .getAnnotation(Select.class).value());

        assertThat(productLock).contains("JOIN nx_product", "ORDER BY p.id", "FOR UPDATE");
        assertThat(skuLock).contains("JOIN nx_admin_device_sku", "ORDER BY s.id", "FOR UPDATE");
        assertThat(guard)
                .contains("p.gpu_model", "p.vram_total_gb", "power_text", "datacenter")
                .contains("'DEVICE','SERVER'", "'FINITE','UNLIMITED'");

        String emergency = String.join(" ", AppOrderCommandMapper.class
                .getMethod("emergencyValue", String.class)
                .getAnnotation(Select.class).value());
        assertThat(emergency).contains("nx_emergency_control_setting", "FOR UPDATE");
    }

    @Test
    void walletPaymentLocksProductsBeforeWalletToMatchTradeinLockOrder() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/ffdd/opsconsole/commerce/application/AppOrderCommandService.java"));
        String payment = source.substring(source.indexOf("private ApiResult<Map<String, Object>> payFromWallet"),
                source.indexOf("private void publishDevelopmentCheckoutCompleted"));

        int productLock = payment.indexOf("mapper.lockOrderProductsForPayment(orderNo)");
        int skuLock = payment.indexOf("mapper.lockOrderSkusForPayment(orderNo)");
        int walletLock = payment.lastIndexOf("mapper.lockDevelopmentWallet(userId)");
        assertThat(productLock).isGreaterThanOrEqualTo(0);
        assertThat(skuLock).isGreaterThan(productLock);
        assertThat(walletLock).isGreaterThan(skuLock);
    }

    @Test
    void cancellationGuardCoversTheIntentBeforeAndAfterProviderOrderInsertion() throws Exception {
        String guard = String.join(" ", AppOrderCommandMapper.class
                .getMethod("countNonCancellableHdPaySessions", String.class)
                .getAnnotation(Select.class).value());

        assertThat(guard)
                .contains("LEFT JOIN nx_hdpay_payin_order")
                .contains("i.status='AWAITING_PAYMENT'")
                .contains("i.expires_at > NOW()")
                .contains("h.submission_status IN ('PENDING','CREATED','SUBMIT_UNKNOWN')");
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

    @Test
    void expiryScanIsProductionPendingOnlyAndTheTerminalWriteIsCompareAndSet() throws Exception {
        String scan = String.join(" ", AppOrderCommandMapper.class
                .getMethod("expiredPendingOrders", Integer.class, Integer.class)
                .getAnnotation(Select.class).value());
        String expire = String.join(" ", AppOrderCommandMapper.class
                .getMethod("expireOrder", String.class, Long.class)
                .getAnnotation(Update.class).value());

        assertThat(scan)
                .contains("u.sandbox=0", "PENDING_PAYMENT", "UPPER(o.payment_status)='PENDING'",
                        "TIMESTAMPADD(MINUTE, -#{ttlMinutes}, NOW())")
                .contains("nx_vietqr_intent", "nx_hdpay_payin_order");
        assertThat(expire)
                .contains("user_id=#{userId}", "order_status='EXPIRED'", "payment_status='EXPIRED'",
                        "UPPER(order_status)='PENDING_PAYMENT'", "UPPER(payment_status)='PENDING'");
    }
}
