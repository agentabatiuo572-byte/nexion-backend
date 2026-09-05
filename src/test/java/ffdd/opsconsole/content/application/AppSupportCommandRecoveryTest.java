package ffdd.opsconsole.content.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.content.domain.ConversationRepository;
import ffdd.opsconsole.content.domain.SupportKnowledgeRepository;
import ffdd.opsconsole.content.domain.SupportAgentRepository;
import ffdd.opsconsole.content.domain.SupportTicketRepository;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyRecordEntity;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import ffdd.opsconsole.shared.idempotency.mapper.AdminIdempotencyRecordMapper;
import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

class AppSupportCommandRecoveryTest {
    private final AdminIdempotencyRecordMapper records = mock(AdminIdempotencyRecordMapper.class);
    private final AppSupportService service = new AppSupportService(
            mock(SupportTicketRepository.class), mock(ConversationRepository.class), mock(SupportKnowledgeRepository.class),
            mock(AdminIdempotencyService.class), mock(AuditLogService.class), mock(ApplicationEventPublisher.class),
            Clock.fixed(Instant.parse("2026-08-31T00:00:00Z"), ZoneOffset.UTC),
            mock(ProductionSupportPathGuard.class), records, new ObjectMapper().findAndRegisterModules(),
            mock(SupportAgentRepository.class), mock(PlatformConfigFacade.class));

    @Test
    void committedCommandIsRecoveredOnlyFromTheAuthenticatedUsersScope() throws Exception {
        AdminIdempotencyRecordEntity record = record("APP_SUPPORT_TICKET_CREATE:42", "SUCCEEDED",
                new ObjectMapper().writeValueAsString(ApiResult.ok(java.util.Map.of("ticket", java.util.Map.of("ticketNo", "TK-1")))));
        when(records.selectSupportCommand(any(), any())).thenReturn(List.of(record));

        var result = service.commandResult(42L, "support-ticket-123");

        assertThat(result.getCode()).isZero();
        assertThat(result.getData()).containsEntry("resultType", "ticket");
        verify(records).selectSupportCommand(List.of(
                "APP_SUPPORT_TICKET_CREATE:42", "APP_SUPPORT_TICKET_REPLY:42", "APP_SUPPORT_TICKET_CLOSE:42",
                "APP_CONVERSATION_CREATE:42", "APP_CONVERSATION_REPLY:42", "APP_CONVERSATION_TO_TICKET:42"),
                "support-ticket-123");
    }

    @Test
    void unknownCommandRemainsFailClosedAndIsNeverReportedAsMissing() {
        when(records.selectSupportCommand(any(), any())).thenReturn(List.of(record("APP_CONVERSATION_REPLY:42", "UNKNOWN", null)));

        var result = service.commandResult(42L, "support-reply-123");

        assertThat(result.getCode()).isEqualTo(409);
        assertThat(result.getMessage()).isEqualTo("SUPPORT_COMMAND_RESULT_UNKNOWN");
    }

    @Test
    void inFlightCommandIsExplicitAndIsNeverPresentedAsACommittedReceipt() {
        when(records.selectSupportCommand(any(), any())).thenReturn(List.of(record("APP_CONVERSATION_REPLY:42", "PROCESSING", null)));

        var result = service.commandResult(42L, "support-reply-123");

        assertThat(result.getCode()).isEqualTo(409);
        assertThat(result.getMessage()).isEqualTo("SUPPORT_COMMAND_IN_PROGRESS");
    }

    private AdminIdempotencyRecordEntity record(String scope, String status, String responseJson) {
        AdminIdempotencyRecordEntity value = new AdminIdempotencyRecordEntity();
        value.setScope(scope);
        value.setStatus(status);
        value.setResponseJson(responseJson);
        value.setIdempotencyKey("support-key");
        return value;
    }
}
