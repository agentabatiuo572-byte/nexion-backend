package ffdd.opsconsole.device.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AnnotationUtils;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Update;

class DeviceCatalogMapperProductSpecContractTest {
    @Test
    void skuProjectionAndWritesCarryServerOwnedProductSpecs() throws Exception {
        assertThat(DeviceCatalogMapper.SKU_COLUMNS)
                .contains("s.uptime AS uptime", "s.warranty AS warranty",
                        "s.phone_daily_earn AS phoneDailyEarn", "s.phone_daily_earn_nex AS phoneDailyEarnNex");
        Method insert = DeviceCatalogMapper.class.getMethod("upsertSkuMetadata", DeviceCatalogMapper.SkuWrite.class);
        assertThat(AnnotationUtils.findAnnotation(insert, Insert.class).value()[0])
                .contains("uptime", "warranty", "phone_daily_earn", "phone_daily_earn_nex");
        assertThat(AnnotationUtils.findAnnotation(insert, Insert.class).value()[0])
                .contains("uptime=VALUES(uptime)", "phone_daily_earn=VALUES(phone_daily_earn)");

        Method canonicalUpdate = DeviceCatalogMapper.class.getMethod(
                "updateSku", DeviceCatalogMapper.SkuWrite.class, java.time.LocalDateTime.class);
        assertThat(AnnotationUtils.findAnnotation(canonicalUpdate, Update.class).value()[0])
                .contains("updated_at=#{expectedUpdatedAt}");
    }

    @Test
    void freshBaselineAndUpgradeMigrationOwnTheSameSpecificationColumns() throws Exception {
        String schema = Files.readString(Path.of("scripts/schema.sql"));
        String migration = Files.readString(Path.of("scripts/migrations/20260817_p2_product_specifications.sql"));
        assertThat(schema).contains("CREATE TABLE IF NOT EXISTS nx_admin_device_sku",
                "uptime VARCHAR(64)", "phone_daily_earn DECIMAL(18,6)",
                "CREATE TABLE IF NOT EXISTS nx_commerce_sandbox_catalog");
        assertThat(migration).contains("CREATE TABLE IF NOT EXISTS nx_admin_device_sku",
                "ALTER TABLE nx_commerce_sandbox_catalog ADD COLUMN uptime",
                "ALTER TABLE nx_commerce_sandbox_catalog ADD COLUMN phone_daily_earn");
    }
}
