package ffdd.opsconsole.content.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.content.domain.SupportAcceptanceSandboxObservationWindow;
import ffdd.opsconsole.content.mapper.SupportAcceptanceSandboxMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Locale;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;

class SupportAcceptanceSandboxObservationContractTest {
    @Test
    void projectionRequiresRunScopedSandboxFactsAndAllFormalSupportDeltas() throws Exception {
        String service = Files.readString(Path.of("src/main/java/ffdd/opsconsole/content/application/SupportAcceptanceSandboxService.java"));
        String mapper = Files.readString(Path.of("src/main/java/ffdd/opsconsole/content/mapper/SupportAcceptanceSandboxMapper.java"));

        assertThat(service).contains("proofFor(run(null))", "mapper.observationWindow(runId)",
                "\"INSUFFICIENT\"", "\"VERIFIED_ZERO\"", "\"VIOLATION\"",
                "productionTicketDelta", "productionTicketMessageDelta", "productionConversationDelta",
                "productionConversationMessageDelta", "productionReceiptDelta", "productionAuditDelta",
                "productionIdempotencyDelta", "productionOutboxDelta");
        assertThat(mapper).contains("DATE_SUB(MIN(f.at), INTERVAL 1 MINUTE)",
                "DATE_ADD(MAX(f.at), INTERVAL 1 MINUTE)", "u.sandbox=1",
                "OR updated_at BETWEEN #{fromAt} AND #{toAt}");
        assertThat(mapper).doesNotContain("LIMIT 1");
    }

    @Test
    void idempotencyObservationCoversFormalTicketAndConversationScopesForEveryRunAccount() throws Exception {
        Select query = java.util.Arrays.stream(SupportAcceptanceSandboxMapper.class.getMethods())
                .filter(method -> method.getName().equals("productionIdempotencyDelta"))
                .findFirst().orElseThrow().getAnnotation(Select.class);
        String sql = String.join(" ", query.value()).toUpperCase(Locale.ROOT);

        assertThat(sql).contains("APP_SUPPORT_%:", "APP_CONVERSATION_%:",
                "S.RUN_ID=#{RUNID}", "CREATED_AT BETWEEN #{FROMAT} AND #{TOAT}",
                "UPDATED_AT BETWEEN #{FROMAT} AND #{TOAT}")
                .doesNotContain("LIMIT 1");
    }

    @Test
    void formalConversationOnlyIdempotencyWriteIsAProductionViolation() {
        SupportAcceptanceSandboxProfileGuard guard = mock(SupportAcceptanceSandboxProfileGuard.class);
        SupportAcceptanceSandboxMapper mapper = mock(SupportAcceptanceSandboxMapper.class);
        LocalDateTime fromAt = LocalDateTime.of(2026, 8, 12, 10, 0);
        LocalDateTime toAt = fromAt.plusMinutes(2);
        when(mapper.observationWindow("run-1"))
                .thenReturn(new SupportAcceptanceSandboxObservationWindow(2, 1, fromAt, toAt));
        when(mapper.productionIdempotencyDelta("run-1", fromAt, toAt)).thenReturn(1);
        SupportAcceptanceSandboxService service = new SupportAcceptanceSandboxService(guard, mapper, Clock.systemUTC(), "run-1");

        Map<String, Object> proof = service.adminProof();

        assertThat((Map<String, Object>) proof.get("productionDelta"))
                .containsEntry("idempotency", 1).containsEntry("status", "VIOLATION");
    }

    @Test
    void formalOpsAuditWithoutUserIdIsAProductionViolation() {
        SupportAcceptanceSandboxProfileGuard guard = mock(SupportAcceptanceSandboxProfileGuard.class);
        SupportAcceptanceSandboxMapper mapper = mock(SupportAcceptanceSandboxMapper.class);
        LocalDateTime fromAt = LocalDateTime.of(2026, 8, 12, 10, 0);
        LocalDateTime toAt = fromAt.plusMinutes(2);
        when(mapper.observationWindow("run-1"))
                .thenReturn(new SupportAcceptanceSandboxObservationWindow(2, 1, fromAt, toAt));
        when(mapper.productionAuditDelta("run-1", fromAt, toAt)).thenReturn(1);
        SupportAcceptanceSandboxService service = new SupportAcceptanceSandboxService(guard, mapper, Clock.systemUTC(), "run-1");

        Map<String, Object> proof = service.adminProof();

        assertThat((Map<String, Object>) proof.get("productionDelta"))
                .containsEntry("audit", 1).containsEntry("status", "VIOLATION");
    }

    @Test
    void opsSideEffectsAreObservedByRunScopedCommandKeysAndSandboxBusinessIds() throws Exception {
        assertObservationSql("productionAuditDelta", "NX_AUDIT_LOG", "COMMAND_KEY", "RESOURCE_ID", "BIZ_NO",
                "IDEMPOTENCYKEY", "NX_SUPPORT_ACCEPTANCE_SANDBOX_TICKET", "NX_SUPPORT_ACCEPTANCE_SANDBOX_CONVERSATION");
        assertObservationSql("productionIdempotencyDelta", "NX_ADMIN_IDEMPOTENCY_RECORD", "IDEMPOTENCY_KEY",
                "COMMAND_KEY", "M3_CONVERSATION_%", "APP_SUPPORT_%:", "APP_CONVERSATION_%:");
        assertObservationSql("productionOutboxDelta", "NX_EVENT_OUTBOX", "AGGREGATE_ID", "PAYLOAD", "COMMAND_KEY",
                "#{RUNID}", "NX_SUPPORT_ACCEPTANCE_SANDBOX_TICKET", "NX_SUPPORT_ACCEPTANCE_SANDBOX_CONVERSATION");
    }

    private void assertObservationSql(String methodName, String... fragments) {
        Select query = java.util.Arrays.stream(SupportAcceptanceSandboxMapper.class.getMethods())
                .filter(method -> method.getName().equals(methodName)).findFirst().orElseThrow().getAnnotation(Select.class);
        assertThat(String.join(" ", query.value()).toUpperCase(Locale.ROOT)).contains(fragments).doesNotContain("LIMIT 1");
    }
}
