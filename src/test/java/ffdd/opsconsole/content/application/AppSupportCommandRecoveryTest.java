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
    private final ConversationRepository conversations = mock(ConversationRepository.class);
    private final AppSupportService service = new AppSupportService(
            mock(SupportTicketRepository.class), conversations, mock(SupportKnowledgeRepository.class),
            mock(AdminIdempotencyService.class), mock(AuditLogService.class), mock(ApplicationEventPublisher.class),
            Clock.fixed(Instant.parse("2026-08-31T00:00:00Z"), ZoneOffset.UTC),
            mock(ProductionSupportPathGuard.class), records, new ObjectMapper().findAndRegisterModules(),
            mock(SupportAgentRepository.class), mock(PlatformConfigFacade.class), ffdd.opsconsole.content.SupportTestDependencies.ownership(), ffdd.opsconsole.content.SupportTestDependencies.humanMessages(),
            ffdd.opsconsole.content.SupportTestDependencies.visibility(conversations));

    @Test
    void committedCommandIsRecoveredOnlyFromTheAuthenticatedUsersScope() throws Exception {
        AdminIdempotencyRecordEntity record = record("APP_CONVERSATION_CREATE:42", "SUCCEEDED",
                new ObjectMapper().writeValueAsString(ApiResult.ok(java.util.Map.of("conversation", java.util.Map.of("conversationNo", "CV-1")))));
        when(records.selectSupportCommand(any(), any())).thenReturn(List.of(record));
        var now = java.time.LocalDateTime.of(2026, 10, 27, 0, 0);
        var conversation = new ffdd.opsconsole.content.domain.ContentConversationView(1L, "CV-1", 42L, "advisor",
                "OPEN", "7", "Agent", 0, "current", now, null, null, null, null, null, null, null, now);
        when(conversations.findByConversationNo("CV-1")).thenReturn(java.util.Optional.of(conversation));

        var result = service.commandResult(42L, "support-ticket-123");

        assertThat(result.getCode()).isZero();
        assertThat(result.getData()).containsEntry("resultType", "conversation");
        verify(records).selectSupportCommand(List.of(
                "APP_SUPPORT_TICKET_CREATE:42", "APP_SUPPORT_TICKET_REPLY:42", "APP_SUPPORT_TICKET_CLOSE:42",
                "APP_CONVERSATION_CREATE:42", "APP_CONVERSATION_REPLY:42", "APP_CONVERSATION_USER_CLEAR:42", "APP_CONVERSATION_TO_TICKET:42"),
                "support-ticket-123");
    }

    @Test
    void retiredTicketReceiptsCannotRestoreTicketWorkflows() {
        for (String scope : List.of("APP_SUPPORT_TICKET_CREATE:42", "APP_SUPPORT_TICKET_REPLY:42", "APP_CONVERSATION_TO_TICKET:42")) {
            when(records.selectSupportCommand(any(), any())).thenReturn(List.of(record(scope, "SUCCEEDED", "{}")));
            var result = service.commandResult(42L, "support-ticket-123");
            assertThat(result.getCode()).isEqualTo(410);
            assertThat(result.getMessage()).isEqualTo("SUPPORT_TICKETS_RETIRED");
            assertThat(result.getData()).isNull();
        }
    }

    @Test
    void unknownCommandRemainsFailClosedAndIsNeverReportedAsMissing() {
        when(records.selectSupportCommand(any(), any())).thenReturn(List.of(record("APP_CONVERSATION_REPLY:42", "UNKNOWN", null)));

        var result = service.commandResult(42L, "support-reply-123");

        assertThat(result.getCode()).isEqualTo(409);
        assertThat(result.getMessage()).isEqualTo("SUPPORT_COMMAND_RESULT_UNKNOWN");
    }

    @Test
    void retainedClearRefusalsRemainAuthoritativeAfterTheirOriginalResponseIsLost() throws Exception {
        for (String reason : List.of("CONVERSATION_USER_CLEAR_CONFLICT", "CONVERSATION_USER_CLEAR_MEMBERS_CHANGED")) {
            String response = new ObjectMapper().writeValueAsString(ApiResult.fail(409, reason));
            when(records.selectSupportCommand(any(), any())).thenReturn(List.of(
                    record("APP_CONVERSATION_USER_CLEAR:42", "SUCCEEDED", response)));
            var result = service.commandResult(42L, "clear-original-key");
            assertThat(result.getCode()).isEqualTo(409);
            assertThat(result.getMessage()).isEqualTo(reason);
            assertThat(result.getData()).isNull();
        }
    }

    @Test
    void nonterminalAndUnrelatedErrorsCannotClaimAnAuthoritativeClearRefusal() throws Exception {
        String response = new ObjectMapper().writeValueAsString(ApiResult.fail(409, "CONVERSATION_USER_CLEAR_CONFLICT"));
        for (String status : List.of("PROCESSING", "FAILED", "UNKNOWN")) {
            when(records.selectSupportCommand(any(), any())).thenReturn(List.of(
                    record("APP_CONVERSATION_USER_CLEAR:42", status, response)));
            assertThat(service.commandResult(42L, "clear-original-key").getMessage())
                    .isNotEqualTo("CONVERSATION_USER_CLEAR_CONFLICT");
        }
        for (String scope : List.of("APP_CONVERSATION_REPLY:42", "APP_CONVERSATION_USER_CLEAR:99")) {
            when(records.selectSupportCommand(any(), any())).thenReturn(List.of(record(scope, "SUCCEEDED", response)));
            assertThat(service.commandResult(42L, "clear-original-key").getMessage())
                    .isEqualTo("SUPPORT_COMMAND_NOT_COMMITTED");
        }
        for (ApiResult<?> error : List.of(ApiResult.fail(409, "IDEMPOTENCY_REQUEST_HASH_CONFLICT"),
                ApiResult.fail(500, "CONVERSATION_USER_CLEAR_CONFLICT"))) {
            when(records.selectSupportCommand(any(), any())).thenReturn(List.of(
                    record("APP_CONVERSATION_USER_CLEAR:42", "SUCCEEDED", new ObjectMapper().writeValueAsString(error))));
            assertThat(service.commandResult(42L, "clear-original-key").getMessage())
                    .isEqualTo("SUPPORT_COMMAND_NOT_COMMITTED");
        }
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
