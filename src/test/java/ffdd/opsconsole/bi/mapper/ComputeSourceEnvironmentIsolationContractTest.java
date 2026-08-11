package ffdd.opsconsole.bi.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ComputeSourceEnvironmentIsolationContractTest {
    @Test
    void biAndMoneyAggregatesExcludeSandboxTaskAndReceiptRows() throws Exception {
        String bi = read("src/main/java/ffdd/opsconsole/bi/mapper/BiReportMapper.java");
        String canonical = read("src/main/java/ffdd/opsconsole/shared/canonical/mapper/CanonicalStateMapper.java");
        String tradein = read("src/main/java/ffdd/opsconsole/device/mapper/AppTradeinMapper.java");
        String assignment = read("src/main/java/ffdd/opsconsole/device/application/AppTaskAssignmentService.java");

        assertThat(bi).contains(
                "nx_compute_task WHERE is_deleted = 0 AND source_environment = 'PRODUCTION'",
                "status = 'COMPLETED' AND source_environment = 'PRODUCTION'");
        assertThat(canonical).contains("COALESCE(r.source_environment, 'PRODUCTION') = 'PRODUCTION'");
        assertThat(tradein).contains("COALESCE(source_environment, 'PRODUCTION') = 'PRODUCTION'");
        assertThat(assignment).contains("insertSandboxReward", "if (!proof.sandbox())")
                .doesNotContain("proof.sandbox() ? \"SANDBOX\" : \"CREDITED\", now");
    }

    private String read(String path) throws Exception {
        return Files.readString(Path.of(path));
    }
}
