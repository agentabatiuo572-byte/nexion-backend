package ffdd.opsconsole.growth.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class AppTrialLifecycleMapperContractTest {

    @Test
    void purchasedTrialDeviceSnapshotsCompleteAuthoritativeSkuSpecifications() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/ffdd/opsconsole/growth/mapper/AppTrialLifecycleMapper.java"));
        String insert = source.substring(source.indexOf("INSERT INTO nx_user_device"),
                source.indexOf("int insertPurchasedDevice"));
        assertThat(insert)
                .contains("gpu_model,vram_total_gb,dc_location")
                .contains("p.gpu_model,p.vram_total_gb,s.datacenter")
                .contains("FROM nx_product p")
                .contains("JOIN nx_admin_device_sku s ON s.sku_id=p.product_no AND s.is_deleted=0")
                .contains("p.id=#{productId} AND p.product_no=#{productCode} AND p.is_deleted=0")
                .contains("TRIM(p.gpu_model)<>''")
                .contains("p.vram_total_gb>0")
                .contains("AS DECIMAL(18,6))>0")
                .contains("TRIM(s.datacenter)<>''")
                .contains("s.power_text REGEXP")
                .doesNotContain("#{deviceType},1,0");
    }

    @Test
    void lifecycleMutationsKeepRowLockVersionCasAndProductionPaymentBoundary() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/ffdd/opsconsole/growth/mapper/AppTrialLifecycleMapper.java"));

        assertThat(source)
                .contains("LIMIT 1 FOR UPDATE")
                .contains("id=#{id} AND version=#{version}")
                .contains("UPPER(status) IN ('CLAIMED','ACTIVE') AND expires_at<=#{now}")
                .contains("COALESCE(source_environment,'PRODUCTION')='PRODUCTION'")
                .contains("AND COALESCE(sandbox,0)=0 FOR UPDATE")
                .contains("AND COALESCE(sandbox,0)=0 LIMIT 1")
                .contains("phone=#{phone} AND sandbox=1")
                .contains("INSERT INTO nx_growth_trial_daily_quota")
                .contains("SELECT GREATEST(COALESCE((")
                .contains("WHERE policy_key='seatsLeftToday' AND is_deleted=0 LIMIT 1")
                .contains("),0)-claimed_count,0)")
                .contains("WHERE quota_date=#{quotaDate} AND claimed_count<daily_limit")
                .contains("SET claimed_count=claimed_count+1")
                .contains("client_request_no");
    }
}
