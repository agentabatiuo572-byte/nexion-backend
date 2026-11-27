package ffdd.opsconsole.commerce.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.Arrays;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;

class CommerceCatalogRefreshContractTest {

    @Test
    void eligibleCatalogProjectionHasAConstructorWithoutTheRunScopeColumn() {
        assertThat(Arrays.stream(CommerceAcceptanceSandboxMapper.CatalogSeed.class.getDeclaredConstructors())
                .filter(constructor -> constructor.getParameterCount() == 30))
                .as("the 30 selected catalog columns must be constructible before runId is attached")
                .hasSize(1);
    }

    @Test
    void sandboxCatalogRefreshesChangedRowsAndPrunesProductsNoLongerForSale() throws Exception {
        Method upsert = CommerceAcceptanceSandboxMapper.class.getMethod(
                "upsertCatalog", CommerceAcceptanceSandboxMapper.CatalogSeed.class);
        String upsertSql = String.join("\n", upsert.getAnnotation(Insert.class).value());
        Method prune = CommerceAcceptanceSandboxMapper.class.getMethod("pruneCatalog", String.class);
        String pruneSql = String.join("\n", prune.getAnnotation(Delete.class).value());

        assertThat(upsertSql)
                .contains("ON DUPLICATE KEY UPDATE")
                .doesNotContain("INSERT IGNORE");
        assertThat(pruneSql)
                .contains("DELETE FROM nx_commerce_sandbox_catalog")
                .contains("NOT EXISTS")
                .contains("FROM nx_product");
    }

    @Test
    void sandboxSubmitLocksAgainstCurrentCanonicalAvailability() throws Exception {
        Method lock = CommerceAcceptanceSandboxMapper.class.getMethod(
                "lockSandboxCatalogProduct", String.class, Long.class, String.class, Integer.class);
        String sql = String.join("\n", lock.getAnnotation(Select.class).value());

        assertThat(sql)
                .contains("JOIN nx_product p ON p.id=c.product_id AND p.product_no=c.product_no")
                .contains("p.is_deleted=0")
                .contains("p.store_visible=1")
                .contains("p.stock>=#{quantity}")
                .contains("p.price_usdt>0")
                .contains("p.price_usdt priceUsdt")
                .contains("p.product_no productNo")
                .contains("p.tier")
                .doesNotContain("p.product_type tier")
                .contains("FOR UPDATE");
    }

    @Test
    void sandboxCatalogReadsRunScopedSpecificationSnapshotsInsteadOfLiveE1Metadata() throws Exception {
        Method list = CommerceAcceptanceSandboxMapper.class.getMethod("listSandboxCatalog", String.class);
        String listSql = String.join("\n", list.getAnnotation(Select.class).value());
        Method lock = CommerceAcceptanceSandboxMapper.class.getMethod(
                "lockSandboxCatalogProduct", String.class, Long.class, String.class, Integer.class);
        String lockSql = String.join("\n", lock.getAnnotation(Select.class).value());

        assertThat(listSql).contains("c.datacenter", "c.uptime", "c.phone_daily_earn AS phoneDailyEarn")
                .doesNotContain("JOIN nx_admin_device_sku");
        assertThat(lockSql).contains("c.datacenter", "c.uptime", "c.phone_daily_earn AS phoneDailyEarn")
                .doesNotContain("JOIN nx_admin_device_sku");
        assertThat(listSql).contains("c.purchase_gate_json AS purchaseGateJson")
                .doesNotContain("s.purchase_gate_json AS purchaseGateJson");
        assertThat(lockSql).contains("c.purchase_gate_json AS purchaseGateJson")
                .doesNotContain("s.purchase_gate_json AS purchaseGateJson");
    }

    @Test
    void sandboxRefundCanReturnReservedStockAfterCanonicalUnlisting() throws Exception {
        Method lock = CommerceAcceptanceSandboxMapper.class.getMethod(
                "lockSandboxCatalogProductForReturn", String.class, Long.class);
        String sql = String.join("\n", lock.getAnnotation(Select.class).value());

        assertThat(sql)
                .contains("FROM nx_commerce_sandbox_catalog c")
                .doesNotContain("JOIN nx_product")
                .contains("FOR UPDATE");
    }

    @Test
    void sandboxOrderLockProjectionMatchesTheRecordConstructorOrder() throws Exception {
        Method lock = CommerceAcceptanceSandboxMapper.class.getMethod(
                "lockSandboxOrder", String.class, String.class);
        String sql = String.join("\n", lock.getAnnotation(Select.class).value())
                .replaceAll("\\s+", " ");

        assertThat(sql).contains(
                "amount_usdt amountUsdt, version,state,wallet_debited walletDebited,stock_returned stockReturned, order_type orderType,item_count itemCount");
    }
}
