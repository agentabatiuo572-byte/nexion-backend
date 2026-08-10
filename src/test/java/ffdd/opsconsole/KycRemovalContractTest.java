package ffdd.opsconsole;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Product invariant: KYC was retired and must not return as an active decision boundary. */
class KycRemovalContractTest {
    private static final List<String> ACTIVE_BOUNDARIES = List.of(
            "src/main/java/ffdd/opsconsole/finance/application/AppWithdrawalService.java",
            "src/main/java/ffdd/opsconsole/finance/mapper/AppWithdrawalMapper.java",
            "src/main/java/ffdd/opsconsole/market/application/AppExchangeService.java",
            "src/main/java/ffdd/opsconsole/market/application/AppGenesisService.java",
            "src/main/java/ffdd/opsconsole/shared/canonical/AppCanonicalBoundaryController.java",
            "src/main/java/ffdd/opsconsole/shared/canonical/AppCanonicalBoundaryService.java",
            "src/main/java/ffdd/opsconsole/user/web/OpsUserController.java",
            "src/main/java/ffdd/opsconsole/risk/web/OpsRiskController.java",
            "src/main/java/ffdd/opsconsole/market/web/OpsExchangeController.java");

    @Test
    void activeBoundariesContainNoKycContractOrDecision() throws IOException {
        for (String file : ACTIVE_BOUNDARIES) {
            String source = Files.readString(Path.of(file)).toLowerCase();
            assertThat(source)
                    .as("active KYC residue in %s", file)
                    .doesNotContain("kyc");
        }
    }

    @Test
    void activeRuntimeContainsNoKycReference() throws IOException {
        try (var files = Files.walk(Path.of("src/main/java"))) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                String source = Files.readString(file).toLowerCase();
                if (file.endsWith(Path.of("risk/infrastructure/MybatisRiskOpsRepository.java"))) {
                    assertThat(source)
                            .contains("legacy_k4_dimension_key = \"kycstatus\"")
                            .contains("\"kyc.reviewscore\", \"kyc.pendingscore\", \"kyc.rejectedscore\", \"kyc.sanctionedscore\"");
                    source = source
                            .replace("legacy_k4_dimension_key", "legacy_dimension_key")
                            .replace("legacy_k4_mapping_keys", "legacy_mapping_keys")
                            .replace("kycstatus", "")
                            .replace("kyc.reviewscore", "")
                            .replace("kyc.pendingscore", "")
                            .replace("kyc.rejectedscore", "")
                            .replace("kyc.sanctionedscore", "");
                }
                assertThat(source)
                        .as("active KYC residue in %s", file)
                        .doesNotContain("kyc");
            }
        }

        for (String file : List.of(
                "scripts/schema.sql",
                "scripts/rbac-classic-seed/01-menu-seed.sql",
                "scripts/rbac-classic-seed/02-role-permission-seed.sql")) {
            assertThat(Files.readString(Path.of(file)).toLowerCase())
                    .as("active KYC residue in %s", file)
                    .doesNotContain("kyc");
        }
    }

    @Test
    void withdrawalUsesAUserScopedPayoutAddressInsteadOfIdentityState() throws IOException {
        String service = Files.readString(Path.of(ACTIVE_BOUNDARIES.get(0)));
        String mapper = Files.readString(Path.of(ACTIVE_BOUNDARIES.get(1)));
        assertThat(service).contains("PayoutAddressRow").doesNotContain("KycWalletRow");
        assertThat(mapper).contains("nx_user_payout_address").doesNotContain("nx_kyc_profile");
    }

    @Test
    void retirementMigrationIsMandatoryAndDoesNotOverwriteUserManagedAddresses() throws IOException {
        String runner = Files.readString(Path.of("scripts/apply_startup_schema_migrations.ps1"));
        String migration = Files.readString(Path.of("scripts/migrations/20260807_remove_kyc_runtime.sql"));

        assertThat(runner).contains("20260807_remove_kyc_runtime.sql");
        assertThat(migration)
                .contains("information_schema.COLUMNS")
                .contains("config_key='kyc.network_whitelist'")
                .contains("ON DUPLICATE KEY UPDATE user_id=VALUES(user_id)")
                .doesNotContain("ON DUPLICATE KEY UPDATE address=VALUES(address)");
    }
}
