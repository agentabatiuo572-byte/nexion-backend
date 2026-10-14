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
    void bothFulfillmentStatementsAcceptSourceWattsAndRejectMalformedOrUnrepresentablePower() throws Exception {
        var samples = java.util.Map.ofEntries(
                java.util.Map.entry("1200", "1200"),
                java.util.Map.entry("1200.25", "1200.25"),
                java.util.Map.entry("1200W", "1200"),
                java.util.Map.entry("1200w", "1200"),
                java.util.Map.entry("1,200W TDP", "1200"),
                java.util.Map.entry(" 1,200W TDP ", "1200"),
                java.util.Map.entry("1200W\u00a0TDP", "1200"),
                java.util.Map.entry("1200W\u2003TDP", "1200"),
                java.util.Map.entry("1,200.125 W TDP", "1200.125"),
                java.util.Map.entry("0.000001W", "0.000001"),
                java.util.Map.entry("999,999,999,999.999999 W TDP", "999999999999.999999"));
        var malformed = java.util.List.of("", "unknown", "0", "0 W TDP", "-1200W", "1e3W",
                "1000000000000W", "1,000,000,000,000W", "1200.1234567W",
                "1,20W TDP", "12,34,567W", "1,200WW TDP", "1200WTDP",
                "TDP1200W", "1200W TDP extra", "1200W; DROP TABLE nx_user_device",
                "1200W\u0000", "1200W\u0001", "1200W\u001f", "\t1200W", "1200W\t", "\n1200W",
                "1200W\n", "1200W TDP\n", "1200W TDP\t", "\u00a01200W", "1200W\u00a0");
        var sqlGrammar = java.util.regex.Pattern.compile(
                "UPPER\\(TRIM\\(s.power_text\\)\\) REGEXP '([^']+)'");
        for (Method method : java.util.List.of(
                AppOrderCommandMapper.class.getMethod("insertDevelopmentDevice",
                        String.class, Long.class, String.class, Integer.class),
                AppOrderCommandMapper.class.getMethod("insertWalletDevice",
                        String.class, Long.class, Long.class, String.class, Integer.class))) {
            String sql = String.join(" ", method.getAnnotation(Insert.class).value());
            var literal = sqlGrammar.matcher(sql);
            assertThat(literal.find()).as(method.getName()).isTrue();
            var grammar = java.util.regex.Pattern.compile(literal.group(1).replace("\\\\", "\\").replace("[[:space:]]", "\\s"),
                    java.util.regex.Pattern.UNICODE_CHARACTER_CLASS);
            assertThat(sql).contains(
                    "CAST(REPLACE(REGEXP_SUBSTR(s.power_text,'[0-9][0-9,.]*'),',','') AS DECIMAL(18,6)) > 0",
                    "o.user_id=#{userId}", "o.payment_status='PAID'", "o.order_status='COMPLETED'",
                    "o.activation_status='ACTIVATED'", "UPPER(p.product_type) IN ('SHARE','CLOUD_SHARE')");
            for (var sample : samples.entrySet()) {
                var parsed = grammar.matcher(sample.getKey().replaceAll("^ +| +$", "").toUpperCase(java.util.Locale.ROOT));
                assertThat(parsed.matches()).as(sample.getKey()).isTrue();
                var watts = new java.math.BigDecimal(parsed.group(1).replace(",", "")
                        + java.util.Objects.toString(parsed.group(3), ""));
                assertThat(watts).isPositive().isEqualByComparingTo(sample.getValue());
            }
            for (String value : malformed) {
                var parsed = grammar.matcher(value.replaceAll("^ +| +$", "").toUpperCase(java.util.Locale.ROOT));
                boolean positive = parsed.matches() && new java.math.BigDecimal(parsed.group(1).replace(",", "")
                        + java.util.Objects.toString(parsed.group(3), "")).signum() > 0;
                assertThat(positive).as(value).isFalse();
            }
        }
    }

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
                        "TIMESTAMPADD(MINUTE, -#{ttlMinutes}, NOW(6))",
                        "OR EXISTS (SELECT 1 FROM nx_promotion_order_receipt pr",
                        "pr.order_no=o.order_no AND pr.pay_by<=NOW(6)")
                .contains("nx_vietqr_intent", "nx_hdpay_payin_order");
        assertThat(expire)
                .contains("user_id=#{userId}", "order_status='EXPIRED'", "payment_status='EXPIRED'",
                        "UPPER(order_status)='PENDING_PAYMENT'", "UPPER(payment_status)='PENDING'");
    }
}
