package ffdd.opsconsole.growth.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class AppTrialLifecycleMapperContractTest {

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
                .contains("SELECT GREATEST(daily_limit-claimed_count,0)")
                .contains("WHERE quota_date=#{quotaDate} AND claimed_count<daily_limit")
                .contains("SET claimed_count=claimed_count+1")
                .contains("client_request_no");
    }
}
