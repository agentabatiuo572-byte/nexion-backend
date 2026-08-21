package ffdd.opsconsole.finance.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class PaymentMethodRunScopeMigrationContractTest {
    @Test
    void startupInstallsRunScopedCardAndRevokeKeys() throws Exception {
        String migration = Files.readString(Path.of("scripts/migrations/20260816_payment_method_run_scope.sql"));
        String startup = Files.readString(Path.of("scripts/apply_startup_schema_migrations.ps1"));
        String schema = Files.readString(Path.of("scripts/schema.sql"));

        assertThat(startup).contains("20260816_payment_method_run_scope.sql");
        assertThat(migration).contains("nx_wallet_bank_card", "ADD COLUMN run_id", "uk_wallet_card_scope_token")
                .contains("nx_payment_method_revoke_command", "uk_payment_method_revoke_scope_method");
        assertThat(schema).contains("run_id VARCHAR(64) NOT NULL DEFAULT ''")
                .contains("uk_wallet_card_scope_token (source_environment, run_id, card_token)");
    }
}
