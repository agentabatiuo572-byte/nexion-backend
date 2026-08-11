package ffdd.opsconsole.janus.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class JanusExecutorClaimStartupMigrationContractTest {
    @Test
    void startupAppliesExecutorClaimLeaseAndEnvironmentIsolationMigration() throws Exception {
        String startup = read("scripts/apply_startup_schema_migrations.ps1");
        String migration = read("scripts/migrations/20260811_janus_executor_claim_nonce.sql");

        assertThat(startup).contains("20260811_janus_executor_claim_nonce.sql");
        assertThat(migration).contains(
                "CREATE TABLE IF NOT EXISTS nx_janus_executor_claim_nonce",
                "PRIMARY KEY (executor_id, claim_nonce)",
                "CREATE TABLE IF NOT EXISTS nx_janus_command_lease",
                "PRIMARY KEY (device_id, command_id, command_version)",
                "UNIQUE KEY uk_janus_command_lease_token (lease_token)",
                "MODIFY proof_nonce VARCHAR(128) NOT NULL",
                "column_name='source_environment'",
                "ADD COLUMN source_environment",
                "ADD UNIQUE KEY uk_earnings_release_attestation(user_id,device_id,source_environment)");
        String baseline=read("scripts/schema.sql");
        assertThat(baseline).contains("CREATE TABLE IF NOT EXISTS nx_janus_applied_proof",
                "proof_nonce VARCHAR(128) NOT NULL","CREATE TABLE IF NOT EXISTS nx_janus_executor_claim_nonce",
                "CREATE TABLE IF NOT EXISTS nx_janus_command_lease");
    }

    private String read(String path) throws Exception {
        return Files.readString(Path.of(path));
    }
}
