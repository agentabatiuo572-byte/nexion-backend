package ffdd.opsconsole.finance.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class D1FinanceClosureMigrationContractTest {

    @Test
    void migrationSeedsMoneyClosureIndependentReconciliationAndExactBuiltInRbac() throws Exception {
        String migration = Files.readString(
                Path.of("scripts/migrations/20260720_d1_finance_closure.sql"),
                StandardCharsets.UTF_8).replaceAll("\\s+", " ");

        assertThat(migration)
                .contains("REGEXP_SUBSTR(REPLACE(config_value,',',''),'[0-9]+([.][0-9]+)?')")
                .contains("finance.topup.channel.card.fee_unit")
                .contains("finance.topup.card.threeDsThreshold.unit")
                .contains("nx_topup_provider_statement")
                .contains("nx_topup_fee_buffer_account")
                .contains("nx_topup_fee_buffer_ledger")
                .contains("nx_topup_chargeback_recovery")
                .contains("nx_topup_risk_lock")
                .contains("nx_topup_card_admission")
                .contains("nx_topup_card_settlement")
                .contains("nx_topup_card_failure")
                .contains("nx_topup_card_chargeback_event")
                .contains("cumulative_deposit_usdt")
                .contains("tmp_d1_verified_topup_credit")
                .contains("UNBACKED_CUMULATIVE_BALANCE")
                .contains("VERIFIED_SOURCE_WALLET_MISSING")
                .contains("LEGACY_STATUS_ONLY_CHARGEBACK")
                .contains("WRONG_DEPOSIT_D4_BINDING")
                .contains("l.biz_no=d.deposit_no AND l.biz_type IN ('CHAIN_TOPUP','DEPOSIT','TOPUP')")
                .contains("w.cumulative_deposit_usdt<>COALESCE(source.cumulative_usdt,0)")
                .contains("DUPLICATE_D4_BINDING','INVALID_D4_BINDING','UNVERIFIED_D4_BINDING")
                .contains("finance_d1_chargeback_refund")
                .contains("finance_d1_bin_manual_lock")
                .contains("r.role_code IN ('SUPER_ADMIN','FINANCE','FINANCE_LEAD','RISK','AUDITOR')")
                .doesNotContain("r.role_code <> 'SUPER_ADMIN'");
    }

    @Test
    void cleanSchemaAndClassicSeedsContainTheSameD1DurableFactsAndPermissions() throws Exception {
        String schema = Files.readString(Path.of("scripts/schema.sql"), StandardCharsets.UTF_8);
        String permissions = Files.readString(
                Path.of("scripts/rbac-classic-seed/D.sql"), StandardCharsets.UTF_8);
        String grants = Files.readString(
                Path.of("scripts/rbac-classic-seed/02-role-permission-seed.sql"), StandardCharsets.UTF_8);
        String application = Files.readString(Path.of("src/main/resources/application.yml"), StandardCharsets.UTF_8);

        assertThat(schema)
                .contains("nx_topup_provider_statement")
                .contains("nx_topup_fee_buffer_account")
                .contains("nx_topup_chargeback_recovery")
                .contains("nx_topup_risk_lock")
                .contains("nx_topup_card_admission")
                .contains("nx_topup_card_settlement")
                .contains("nx_topup_card_failure")
                .contains("nx_topup_card_chargeback_event");
        assertThat(permissions)
                .contains("finance_d1_chargeback_refund")
                .contains("finance_d1_bin_manual_lock");
        assertThat(grants)
                .contains("finance_d1_chargeback_refund")
                .contains("finance_d1_bin_manual_lock")
                .contains("r.role_code IN ('SUPER_ADMIN','FINANCE','FINANCE_LEAD','RISK','AUDITOR')")
                .doesNotContain("r.role_code <> 'SUPER_ADMIN' AND p.permission_code LIKE 'finance_d1_%'");
        assertThat(application).contains("connection-init-sql: \"SET time_zone = '+08:00'\"");
    }
}
