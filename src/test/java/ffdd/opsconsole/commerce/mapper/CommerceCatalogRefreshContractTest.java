package ffdd.opsconsole.commerce.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;

class CommerceCatalogRefreshContractTest {

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
    void sandboxRefundCanReturnReservedStockAfterCanonicalUnlisting() throws Exception {
        Method lock = CommerceAcceptanceSandboxMapper.class.getMethod(
                "lockSandboxCatalogProductForReturn", String.class, Long.class);
        String sql = String.join("\n", lock.getAnnotation(Select.class).value());

        assertThat(sql)
                .contains("FROM nx_commerce_sandbox_catalog c")
                .doesNotContain("JOIN nx_product")
                .contains("FOR UPDATE");
    }
}
