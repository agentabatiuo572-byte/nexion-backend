package ffdd.opsconsole.team.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class OpsBinarySettlementControllerContractTest {
    @Test
    void exposesOnlyProtectedExplicitAssignmentAndSettlementWriters() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/ffdd/opsconsole/team/web/OpsBinarySettlementController.java"));

        assertThat(source).contains("/teams/binary", "/assignments", "/settlements");
        assertThat(source).contains("hasAuthority('network_f3_write')");
        assertThat(source).contains("@Valid @RequestBody BinaryLegAssignmentRequest");
        assertThat(source).contains("@Valid @RequestBody BinarySettlementRequest");
        assertThat(source).contains("Authentication authentication", "actor.id()", "actor.username()");
        assertThat(source).doesNotContain("request.operator()");
    }

    @Test
    void immutableLegConflictKeepsTheSharedBizExceptionHttp409Contract() throws Exception {
        String service = Files.readString(Path.of(
                "src/main/java/ffdd/opsconsole/team/application/BinaryCommissionSettlementService.java"));
        String advice = Files.readString(Path.of(
                "src/main/java/ffdd/opsconsole/shared/exception/GlobalExceptionHandler.java"));

        assertThat(service).contains("new BizException(409, \"BINARY_LEG_ASSIGNMENT_IMMUTABLE\")");
        assertThat(advice).contains("@ExceptionHandler(BizException.class)",
                "ApiResult.fail(ex.getCode(), ex.getMessage())");
    }
}
