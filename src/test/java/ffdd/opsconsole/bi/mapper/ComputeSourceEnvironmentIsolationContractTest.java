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
        String normalizedBi = bi.replaceAll("\\s+", " ");

        assertThat(normalizedBi).contains(
                "FROM nx_user u WHERE u.is_deleted = 0 AND COALESCE(u.sandbox,0)=0",
                "JOIN nx_user u ON u.id=t.user_id AND COALESCE(u.sandbox,0)=0",
                "t.source_environment = 'PRODUCTION'",
                "t.status = 'COMPLETED'");
        assertThat(selectFor(bi, "long countUserProfiles()"))
                .contains("FROM nx_user_profile p", "JOIN nx_user u ON u.id=p.user_id",
                        "COALESCE(u.sandbox,0)=0");
        assertThat(selectFor(bi, "long countUserDevices()"))
                .contains("FROM nx_user_device d", "JOIN nx_user u ON u.id=d.user_id",
                        "COALESCE(u.sandbox,0)=0");
        assertThat(selectFor(bi, "long countActiveUserDevices()"))
                .contains("FROM nx_user_device d", "JOIN nx_user u ON u.id=d.user_id",
                        "COALESCE(u.sandbox,0)=0");
        assertThat(canonical).contains("COALESCE(r.source_environment, 'PRODUCTION') = 'PRODUCTION'");
        assertThat(tradein).contains("COALESCE(source_environment, 'PRODUCTION') = 'PRODUCTION'");
        assertThat(assignment).doesNotContain("insertSandboxReward")
                .contains("if (proof.sandbox() || !\"PRODUCTION\".equals(sourceEnvironment))")
                .contains("TASK_ASSIGNMENT_PROOF_ENVIRONMENT_INVALID")
                .contains("insertReceipt", "creditWallet", "insertEarningEvent")
                .doesNotContain("proof.sandbox() ? \"SANDBOX\" : \"CREDITED\", now");
    }

    private String read(String path) throws Exception {
        return Files.readString(Path.of(path));
    }

    private String selectFor(String source, String methodSignature) {
        int end = source.indexOf(methodSignature);
        assertThat(end).isGreaterThanOrEqualTo(0);
        int start = source.lastIndexOf("@Select", end);
        assertThat(start).isGreaterThanOrEqualTo(0);
        return source.substring(start, end);
    }
}
