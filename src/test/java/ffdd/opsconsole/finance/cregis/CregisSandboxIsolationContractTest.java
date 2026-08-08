package ffdd.opsconsole.finance.cregis;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class CregisSandboxIsolationContractTest {
    @Test
    void localSandboxHasNoLedgerBalanceWithdrawalOrTransactionalDependency() throws Exception {
        String sandbox = Files.readString(Path.of(
                "src/main/java/ffdd/opsconsole/finance/cregis/LocalCregisSandboxGateway.java"));
        String probe = Files.readString(Path.of(
                "src/main/java/ffdd/opsconsole/finance/cregis/CregisSandboxService.java"));
        String active = (sandbox + probe).toLowerCase();

        assertThat(active)
                .doesNotContain("treasuryledger")
                .doesNotContain("withdrawalorder")
                .doesNotContain("userwallet")
                .doesNotContain("balance")
                .doesNotContain("@transactional")
                .doesNotContain("nx_");
    }

    @Test
    void applicationConfigurationDefaultsToDisabledAndContainsNoCredentialDefault() throws Exception {
        String yaml = Files.readString(Path.of("src/main/resources/application.yml"));
        assertThat(yaml)
                .contains("mode: ${NEXION_CREGIS_MODE:DISABLED}")
                .contains("api-key: ${NEXION_CREGIS_API_KEY:}")
                .doesNotContain("NEXION_CREGIS_MODE:PROVIDER")
                .doesNotContain("NEXION_CREGIS_MODE:LOCAL_SANDBOX");
    }

    @Test
    void configurationObjectNeverPrintsTheApiKey() {
        CregisProperties properties = new CregisProperties();
        properties.setApiKey("secret-marker-that-must-not-appear");

        assertThat(properties.toString()).doesNotContain("secret-marker-that-must-not-appear");
    }
}
