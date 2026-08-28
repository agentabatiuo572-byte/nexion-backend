package ffdd.opsconsole.device.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.Test;

class DeviceCatalogMapperSkuSqlContractTest {

    @Test
    void skuCountOnlyReferencesDeclaredFilterParameters() throws Exception {
        Method method = DeviceCatalogMapper.class.getMethod("countSkus", String.class, String.class);
        String sql = String.join("\n", method.getAnnotation(Select.class).value());

        assertThat(sql).contains("#{status}", "#{keyword}");
        assertThat(sql).doesNotContain("#{taskClass}");
    }

    @Test
    void e1CatalogReadsTheSameNxProductTruthAsAppQuoteAndSubmit() throws Exception {
        String countSql = selectSql("countSkus", String.class, String.class);
        String pageSql = selectSql("pageSkus", String.class, String.class, long.class, long.class);
        String findSql = selectSql("findSku", String.class);

        assertThat(countSql).contains("FROM nx_product p");
        assertThat(pageSql).contains("FROM nx_product p");
        assertThat(findSql).contains("FROM nx_product p");
    }

    @Test
    void e1CatalogCoreMutationsWriteNxProductInsteadOfTheLegacyAdminMirror() throws Exception {
        String insertSql = String.join("\n", DeviceCatalogMapper.class.getMethod(
                "insertSku", DeviceCatalogMapper.SkuWrite.class).getAnnotation(Insert.class).value());
        String updateSql = updateSql("updateSku", DeviceCatalogMapper.SkuWrite.class, java.time.LocalDateTime.class);
        String statusSql = updateSql("updateSkuStatus", String.class, String.class, java.time.LocalDateTime.class, java.time.LocalDateTime.class);
        String deleteSql = updateSql("softDeleteSku", String.class, java.time.LocalDateTime.class, java.time.LocalDateTime.class);

        assertThat(insertSql).contains("INSERT INTO nx_product");
        assertThat(updateSql).contains("UPDATE nx_product");
        assertThat(statusSql).contains("UPDATE nx_product");
        assertThat(deleteSql).contains("UPDATE nx_product");
    }

    @Test
    void e1CatalogPersistsExplicitInventoryModeInTheCanonicalProduct() throws Exception {
        String insertSql = String.join("\n", DeviceCatalogMapper.class.getMethod(
                "insertSku", DeviceCatalogMapper.SkuWrite.class).getAnnotation(Insert.class).value());
        String updateSql = updateSql("updateSku", DeviceCatalogMapper.SkuWrite.class, java.time.LocalDateTime.class);
        String pageSql = selectSql("pageSkus", String.class, String.class, long.class, long.class);

        assertThat(insertSql).contains("inventory_mode", "#{sku.inventoryMode}");
        assertThat(updateSql).contains("inventory_mode=#{sku.inventoryMode}");
        assertThat(pageSql).contains("p.inventory_mode AS inventoryMode");
    }

    @Test
    void e1CatalogOrdinaryEditPreservesTheCanonicalProductType() throws Exception {
        String updateSql = updateSql("updateSku", DeviceCatalogMapper.SkuWrite.class, java.time.LocalDateTime.class);

        assertThat(updateSql).doesNotContain("product_type=");
    }

    @Test
    void e1CatalogUpdateUsesTheProductRevisionAsAnOptimisticLock() throws Exception {
        String sql = updateSql("updateSku", DeviceCatalogMapper.SkuWrite.class, java.time.LocalDateTime.class);
        String statusSql = updateSql("updateSkuStatus", String.class, String.class, java.time.LocalDateTime.class, java.time.LocalDateTime.class);
        String deleteSql = updateSql("softDeleteSku", String.class, java.time.LocalDateTime.class, java.time.LocalDateTime.class);

        assertThat(sql).contains("updated_at=#{expectedUpdatedAt}");
        assertThat(statusSql).contains("updated_at=#{expectedUpdatedAt}");
        assertThat(deleteSql).contains("updated_at=#{expectedUpdatedAt}");
    }

    @Test
    void legacyMigrationKeepsActiveProductsVisibleAndBoundsTextNumbers() throws Exception {
        String sql = String.join("\n", DeviceCatalogMapper.class.getMethod(
                "backfillProductsFromLegacySkus").getAnnotation(Insert.class).value());

        assertThat(sql).contains("LOWER(s.status) IN ('on','active')");
        assertThat(sql).contains("CHAR_LENGTH(SUBSTRING_INDEX(TRIM(s.hash_rate),'.',1)) <= 11");
        assertThat(sql).contains("CHAR_LENGTH(TRIM(s.stock_text)) < 10");
        assertThat(sql).contains("TRIM(s.stock_text) <= '2147483647'");
    }

    @Test
    void canonicalUnlockPhaseCanBeRecoveredFromExistingE1Metadata() throws Exception {
        String sql = updateSql("backfillProductUnlockPhasesFromSkuMetadata");

        assertThat(sql).contains("JOIN nx_admin_device_sku s ON s.sku_id=p.product_no");
        assertThat(sql).contains("p.unlock_phase IS NULL OR p.unlock_phase=''");
        assertThat(sql).contains("s.unlock_phase_id IS NOT NULL");
    }

    @Test
    void orderCancellationRestockCannotOverflowOrCreateNegativeSales() throws Exception {
        String sql = updateSql("restockOrderProduct", String.class, java.time.LocalDateTime.class);
        String itemSql = updateSql("restockOrderItemProducts", String.class, java.time.LocalDateTime.class);
        String planSql = selectSql("orderRestockPlan", String.class);

        assertThat(sql).contains("p.sold_count>=o.quantity");
        assertThat(sql).contains("p.stock<=2147483647-o.quantity");
        assertThat(sql).contains("UPPER(o.order_type)='SINGLE'");
        assertThat(sql).doesNotContain("p.is_deleted=0");
        assertThat(planSql)
                .contains("o.quantity orderQuantity")
                .contains("o.item_count itemCount")
                .contains("COUNT(oi.id) itemRows")
                .contains("SUM(CASE WHEN oi.product_id IS NOT NULL AND oi.quantity>0")
                .contains("COUNT(DISTINCT CASE WHEN oi.product_id IS NOT NULL AND oi.quantity>0");
        assertThat(itemSql)
                .contains("JOIN nx_order_item oi")
                .contains("GROUP BY oi.product_id")
                .contains("SUM(oi.quantity) quantity")
                .contains("HAVING oi.product_id IS NOT NULL AND MIN(oi.quantity)>0")
                .contains("p.sold_count>=items.quantity")
                .contains("p.stock<=2147483647-items.quantity")
                .doesNotContain("p.is_deleted=0");
        assertThat(sql).contains("p.inventory_mode='FINITE'");
        assertThat(itemSql).contains("p.inventory_mode='FINITE'");
    }

    @Test
    void canonicalProductSchemaDefinesFiniteAndUnlimitedInventoryWithoutMagicStock() throws Exception {
        String schema = Files.readString(Path.of("scripts", "schema.sql"));
        int productStart = schema.indexOf("CREATE TABLE IF NOT EXISTS nx_product (");
        int productEnd = schema.indexOf(") ENGINE=InnoDB", productStart);

        assertThat(schema.substring(productStart, productEnd))
                .contains("inventory_mode VARCHAR(16) NOT NULL DEFAULT 'FINITE'")
                .contains("CHECK (inventory_mode IN ('FINITE','UNLIMITED'))");
    }

    @Test
    void productRevisionUsesMicrosecondPrecisionAndAlwaysMovesForward() throws Exception {
        String alter = updateSql("widenProductUpdatedAtPrecision");
        String update = updateSql("updateSku", DeviceCatalogMapper.SkuWrite.class, java.time.LocalDateTime.class);
        String status = updateSql("updateSkuStatus", String.class, String.class,
                java.time.LocalDateTime.class, java.time.LocalDateTime.class);
        String delete = updateSql("softDeleteSku", String.class, java.time.LocalDateTime.class,
                java.time.LocalDateTime.class);

        assertThat(alter).contains("DATETIME(6)").contains("CURRENT_TIMESTAMP(6)");
        assertThat(update).contains("updated_at + INTERVAL 1 MICROSECOND")
                .contains("updated_at=#{expectedUpdatedAt}");
        assertThat(status).contains("updated_at + INTERVAL 1 MICROSECOND")
                .contains("updated_at=#{expectedUpdatedAt}");
        assertThat(delete).contains("updated_at + INTERVAL 1 MICROSECOND")
                .contains("updated_at=#{expectedUpdatedAt}");
    }

    @Test
    void coldInstallSchemaCreatesProductRevisionWithMicrosecondPrecision() throws Exception {
        String schema = Files.readString(Path.of("scripts", "schema.sql"));
        int productStart = schema.indexOf("CREATE TABLE IF NOT EXISTS nx_product (");
        int productEnd = schema.indexOf(") ENGINE=InnoDB", productStart);

        assertThat(productStart).isGreaterThanOrEqualTo(0);
        assertThat(productEnd).isGreaterThan(productStart);
        assertThat(schema.substring(productStart, productEnd))
                .contains("updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)"
                        + " ON UPDATE CURRENT_TIMESTAMP(6)");
    }

    private static String selectSql(String name, Class<?>... parameterTypes) throws Exception {
        Method method = DeviceCatalogMapper.class.getMethod(name, parameterTypes);
        return String.join("\n", method.getAnnotation(Select.class).value());
    }

    private static String updateSql(String name, Class<?>... parameterTypes) throws Exception {
        Method method = DeviceCatalogMapper.class.getMethod(name, parameterTypes);
        return String.join("\n", method.getAnnotation(Update.class).value());
    }
}
