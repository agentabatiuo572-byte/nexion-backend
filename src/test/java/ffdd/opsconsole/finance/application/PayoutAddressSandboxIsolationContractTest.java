package ffdd.opsconsole.finance.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class PayoutAddressSandboxIsolationContractTest {
    @Test
    void sandboxAddressAndOtpTablesAreRunAndUserScopedAndInstalledAtStartup() throws Exception {
        String mapper = Files.readString(Path.of("src/main/java/ffdd/opsconsole/finance/mapper/AppPayoutAddressMapper.java"));
        String service = Files.readString(Path.of("src/main/java/ffdd/opsconsole/finance/application/AppPayoutAddressService.java"));
        String migration = Files.readString(Path.of("scripts/migrations/20260816_payout_address_sandbox.sql"));
        String schema = Files.readString(Path.of("scripts/schema.sql"));
        String runner = Files.readString(Path.of("scripts/apply_startup_schema_migrations.ps1"));

        assertThat(migration).contains(
                "nx_user_payout_address_sandbox",
                "uk_user_payout_address_sandbox_scope (run_id,user_id,network)",
                "chk_user_payout_address_sandbox_source CHECK (source='mock' AND source_environment='SANDBOX')",
                "nx_user_payout_address_sandbox_otp",
                "uk_user_payout_address_sandbox_otp (run_id,user_id,challenge_no)");
        assertThat(schema).contains("CREATE TABLE IF NOT EXISTS nx_user_payout_address_sandbox",
                "CREATE TABLE IF NOT EXISTS nx_user_payout_address_sandbox_otp");
        assertThat(runner).contains("20260816_payout_address_sandbox.sql");
        assertThat(mapper).contains("sandboxList", "sandboxLock", "insertSandboxOtp", "consumeSandboxOtp")
                .contains("lockActiveSandboxUser")
                .contains("run_id=#{runId} AND user_id=#{userId}");
        assertThat(service).contains("sourceEnvironment()", "requireRunId()", "verifyAndConsumeSandbox")
                .contains("mapper.sandboxList", "mapper.sandboxInsert", "mapper.sandboxUpdate")
                .contains("mapper.list(userId)");
    }
}
