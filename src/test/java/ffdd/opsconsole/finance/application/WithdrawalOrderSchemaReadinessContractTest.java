package ffdd.opsconsole.finance.application;

import static org.assertj.core.api.Assertions.assertThat;

import ffdd.opsconsole.finance.mapper.AppWithdrawalMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;

class WithdrawalOrderSchemaReadinessContractTest {
    private static final String MIGRATION = "20261015_withdrawal_order_schema_readiness.sql";

    @Test
    void startupGuardCoversEveryColumnUsedByListDetailAndSubmission() {
        Set<String> required = new LinkedHashSet<>();
        for (var method : AppWithdrawalMapper.class.getDeclaredMethods()) {
            if (method.getName().equals("insertWithdrawal")) {
                String sql = String.join("\n", method.getAnnotation(Insert.class).value());
                var columns = Pattern.compile("(?s)INSERT INTO nx_withdrawal_order\\s*\\((.*?)\\)\\s*VALUES").matcher(sql);
                assertThat(columns.find()).isTrue();
                Arrays.stream(columns.group(1).split(",")).map(String::trim).forEach(required::add);
            }
            if (List.of("userWithdrawalsAt", "userWithdrawal").contains(method.getName())) {
                String sql = String.join("\n", method.getAnnotation(Select.class).value());
                Pattern.compile("\\bw\\.([a-z0-9_]+)").matcher(sql).results()
                        .map(match -> match.group(1)).forEach(required::add);
            }
        }
        assertThat(WithdrawalAttemptSchemaInitializer.REQUIRED_ORDER_COLUMNS)
                .doesNotHaveDuplicates().containsExactlyInAnyOrderElementsOf(required);
    }

    @Test
    void readinessReadsOnlyTheCurrentDatabaseMetadata() throws Exception {
        String sql = String.join("\n", AppWithdrawalMapper.class.getMethod("withdrawalOrderColumns")
                .getAnnotation(Select.class).value());
        assertThat(sql).contains("information_schema.COLUMNS", "TABLE_SCHEMA=DATABASE()",
                "TABLE_NAME='nx_withdrawal_order'").doesNotContain("ALTER", "UPDATE", "CREATE");
        String expirySql = String.join("\n", AppWithdrawalMapper.class.getMethod("withdrawalQuoteExpiryColumnCount")
                .getAnnotation(Select.class).value());
        assertThat(expirySql).contains("information_schema.COLUMNS", "TABLE_SCHEMA=DATABASE()",
                "TABLE_NAME='nx_bank_payout_quote_expiry'", "COLUMN_NAME IN ('quote_no','expired_at')")
                .doesNotContain("ALTER", "UPDATE", "CREATE");
    }

    @Test
    void quoteExpiryMigrationFollowsBankSchemaAndHasAStartupPostcondition() throws Exception {
        String runner = Files.readString(Path.of("scripts/apply_startup_schema_migrations.ps1"));
        String registry = runner.substring(runner.indexOf("$migrations = @("), runner.indexOf("# Retirement invariant:"));
        String expiryMigration = "20260916_bank_beneficiary_verification.sql";
        assertThat(registry.split(Pattern.quote(expiryMigration), -1)).hasSize(2);
        assertThat(registry.indexOf("20260915_hdpay_bank_withdrawal.sql")).isGreaterThanOrEqualTo(0)
                .isLessThan(registry.indexOf(expiryMigration));
        assertThat(runner).contains("TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_bank_payout_quote_expiry'",
                "COLUMN_NAME IN ('quote_no','expired_at')",
                "$LASTEXITCODE -ne 0 -or (@($requiredQuoteExpiryColumnCount)",
                "Withdrawal quote expiry schema startup postcondition failed");
    }

    @Test
    void forwardMigrationOnlyAddsMissingSnapshotColumnsWithoutRewritingHistory() throws Exception {
        String sql = Files.readString(Path.of("scripts/migrations", MIGRATION));
        String statements = sql.replaceAll("(?m)^--.*$", "");
        List<String> missing = List.of("d2_network_fee_rate", "d2_network_fee_min", "d2_network_fee_max",
                "d2_network_fee", "d2_routing_priority", "d2_k3_risk_route", "d2_k4_risk_score",
                "d2_k4_model_version", "d2_k4_as_of", "d2_k4_band_low_max", "d2_k4_band_high_min",
                "d2_k4_auto_escalate_score");
        assertThat(Pattern.compile("ADD COLUMN ([a-z0-9_]+)").matcher(statements).results()
                .map(match -> match.group(1)).toList()).containsExactlyInAnyOrderElementsOf(missing);
        for (String column : missing) {
            assertThat(statements).contains("COLUMN_NAME='" + column + "')=0",
                    "ALTER TABLE nx_withdrawal_order ADD COLUMN " + column);
        }
        assertThat(statements).doesNotContain("nx_event_", "nx_admin_risk_", "nx_wallet_", "nx_user_wallet");
        assertThat(Pattern.compile("(?i)\\b(UPDATE|INSERT|DELETE|REPLACE|DROP|TRUNCATE|MODIFY|CHANGE)\\b")
                .matcher(statements).find()).isFalse();
    }

    @Test
    void controlledStartupRegistersForwardRepairAfterPrerequisitesAndChecksTheSameColumns() throws Exception {
        String runner = Files.readString(Path.of("scripts/apply_startup_schema_migrations.ps1"));
        String registry = runner.substring(runner.indexOf("$migrations = @("), runner.indexOf("# Retirement invariant:"));
        assertThat(registry.split(Pattern.quote(MIGRATION), -1)).hasSize(2);
        for (String prerequisite : List.of("20260720_d2_withdrawal_closure.sql", "20260807_nexion_hard_blockers.sql",
                "20260816_withdrawal_attempt_authority.sql", "20260823_withdrawal_terminal_refund_projection.sql",
                "20260828_cd_finance_canonical.sql")) {
            assertThat(registry.indexOf(prerequisite)).isGreaterThanOrEqualTo(0).isLessThan(registry.indexOf(MIGRATION));
        }
        assertThat(registry).doesNotContain("20260727_d5_withdrawal_execution_closure.sql",
                "20260722_k4_d2_routing_event_closure.sql");
        var array = Pattern.compile("(?s)\\$withdrawalOrderColumns = @\\((.*?)\\)").matcher(runner);
        assertThat(array.find()).isTrue();
        List<String> postconditionColumns = Pattern.compile("'([a-z0-9_]+)'").matcher(array.group(1)).results()
                .map(match -> match.group(1)).toList();
        assertThat(postconditionColumns).doesNotHaveDuplicates()
                .containsExactlyInAnyOrderElementsOf(WithdrawalAttemptSchemaInitializer.REQUIRED_ORDER_COLUMNS);
        assertThat(runner).contains("TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_withdrawal_order'",
                "$LASTEXITCODE -ne 0 -or (@($requiredWithdrawalColumnCount)",
                "-ne [string]$withdrawalOrderColumns.Count", "Withdrawal order schema startup postcondition failed");
    }
}
