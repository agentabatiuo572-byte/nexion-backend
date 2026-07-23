package ffdd.opsconsole.content.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.content.application.OpsSupportTicketService;
import ffdd.opsconsole.content.application.OpsSupportAgentService;
import ffdd.opsconsole.content.dto.SupportLoadConfigUpdateRequest;
import ffdd.opsconsole.content.dto.SupportLoadRebalanceRequest;
import ffdd.opsconsole.content.dto.SupportTicketAssigneeRequest;
import ffdd.opsconsole.content.dto.SupportTicketArchiveRequest;
import ffdd.opsconsole.content.dto.SupportTicketCreateRequest;
import ffdd.opsconsole.content.dto.SupportTicketEscalateRequest;
import ffdd.opsconsole.content.dto.SupportTicketPriorityRequest;
import ffdd.opsconsole.content.dto.SupportTicketReplyRequest;
import ffdd.opsconsole.content.dto.SupportTicketStatusRequest;
import ffdd.opsconsole.shared.api.ApiResult;
import java.util.Map;
import org.junit.jupiter.api.Test;

class OpsSupportTicketControllerTest {
    private final OpsSupportTicketService ticketService = mock(OpsSupportTicketService.class);
    private final OpsSupportAgentService supportAgentService = mock(OpsSupportAgentService.class);
    private final OpsSupportTicketController controller = new OpsSupportTicketController(ticketService, supportAgentService);

    @Test
    void overviewDelegatesToService() {
        when(ticketService.overview()).thenReturn(ApiResult.ok(Map.of("active", 1)));

        assertThat(controller.overview().getData()).containsEntry("active", 1);

        verify(ticketService).overview();
    }

    @Test
    void loadConfigDelegatesToService() {
        when(ticketService.loadConfig()).thenReturn(ApiResult.ok(Map.of("defaultCap", 8)));

        assertThat(controller.loadConfig().getData()).containsEntry("defaultCap", 8);

        verify(ticketService).loadConfig();
    }

    @Test
    void updateLoadConfigDelegatesWithIdempotencyHeader() {
        SupportLoadConfigUpdateRequest request =
                new SupportLoadConfigUpdateRequest(true, 7, 11, 75, true, "备勤队列", Map.of(), "superadmin", "rebalance load");
        when(ticketService.updateLoadConfig("idem-m1-load", request)).thenReturn(ApiResult.ok(Map.of("ok", true)));
        when(supportAgentService.canManageSupportSeats()).thenReturn(true);

        assertThat(controller.updateLoadConfig("idem-m1-load", request).getData()).containsEntry("ok", true);

        verify(ticketService).updateLoadConfig("idem-m1-load", request);
    }

    @Test
    void rebalanceLoadDelegatesWithIdempotencyHeader() {
        SupportLoadRebalanceRequest request =
                new SupportLoadRebalanceRequest(java.util.List.of(Map.of("id", "agent-1", "cap", 4)), "superadmin", "rebalance load");
        when(ticketService.rebalanceLoad("idem-m1-rebalance", request)).thenReturn(ApiResult.ok(Map.of("ok", true)));
        when(supportAgentService.canManageSupportSeats()).thenReturn(true);

        assertThat(controller.rebalanceLoad("idem-m1-rebalance", request).getData()).containsEntry("ok", true);

        verify(ticketService).rebalanceLoad("idem-m1-rebalance", request);
    }

    @Test
    void loadConfigMutationRejectsNonSupervisorEvenWithWriteAuthority() {
        SupportLoadConfigUpdateRequest request =
                new SupportLoadConfigUpdateRequest(true, 7, 11, 75, true, "备勤队列", Map.of(), "risk", "unauthorized load change");
        when(supportAgentService.canManageSupportSeats()).thenReturn(false);

        assertThat(controller.updateLoadConfig("idem-m1-denied", request).getCode()).isEqualTo(403);
    }

    @Test
    void loadConfigEndpointsUseM1Authorities() throws Exception {
        assertThat(OpsSupportTicketController.class.getMethod("loadConfig")
                .getAnnotation(org.springframework.security.access.prepost.PreAuthorize.class).value())
                .isEqualTo("hasAuthority('service_m1_read')");
        assertThat(OpsSupportTicketController.class.getMethod(
                        "updateLoadConfig", String.class, SupportLoadConfigUpdateRequest.class)
                .getAnnotation(org.springframework.security.access.prepost.PreAuthorize.class).value())
                .isEqualTo("hasAuthority('service_m1_write')");
        assertThat(OpsSupportTicketController.class.getMethod(
                        "rebalanceLoad", String.class, SupportLoadRebalanceRequest.class)
                .getAnnotation(org.springframework.security.access.prepost.PreAuthorize.class).value())
                .isEqualTo("hasAuthority('service_m1_write')");
    }

    @Test
    void createDelegatesWithIdempotencyHeader() {
        SupportTicketCreateRequest request = new SupportTicketCreateRequest(
                1001L, "withdrawal", "high", "Pending withdrawal", "Please check.", 1L, "Marina K.", "Marina K.", "customer request");
        when(ticketService.create("idem-m2-create", request)).thenReturn(ApiResult.ok(null));

        assertThat(controller.create("idem-m2-create", request).getCode()).isZero();

        verify(ticketService).create("idem-m2-create", request);
    }

    @Test
    void replyDelegatesWithIdempotencyHeader() {
        SupportTicketReplyRequest request = new SupportTicketReplyRequest("checking", "Marina K.", null);
        when(ticketService.reply("TK-1", "idem-m2-reply", request)).thenReturn(ApiResult.ok(null));

        assertThat(controller.reply("TK-1", "idem-m2-reply", request).getCode()).isZero();

        verify(ticketService).reply("TK-1", "idem-m2-reply", request);
    }

    @Test
    void updateStatusDelegatesWithIdempotencyHeader() {
        SupportTicketStatusRequest request = new SupportTicketStatusRequest("RESOLVED", "Marina K.", "issue finished");
        when(ticketService.updateStatus("TK-1", "idem-m2-status", request)).thenReturn(ApiResult.ok(null));

        assertThat(controller.updateStatus("TK-1", "idem-m2-status", request).getCode()).isZero();

        verify(ticketService).updateStatus("TK-1", "idem-m2-status", request);
    }

    @Test
    void updatePriorityDelegatesWithIdempotencyHeader() {
        SupportTicketPriorityRequest request = new SupportTicketPriorityRequest("URGENT", "Marina K.", "payment escalation");
        when(ticketService.updatePriority("TK-1", "idem-m2-priority", request)).thenReturn(ApiResult.ok(null));

        assertThat(controller.updatePriority("TK-1", "idem-m2-priority", request).getCode()).isZero();

        verify(ticketService).updatePriority("TK-1", "idem-m2-priority", request);
    }

    @Test
    void assignDelegatesWithIdempotencyHeader() {
        SupportTicketAssigneeRequest request = new SupportTicketAssigneeRequest(7L, "Tomas R.", "Marina K.", "kyc specialist");
        when(ticketService.assign("TK-1", "idem-m2-assign", request)).thenReturn(ApiResult.ok(null));

        assertThat(controller.assign("TK-1", "idem-m2-assign", request).getCode()).isZero();

        verify(ticketService).assign("TK-1", "idem-m2-assign", request);
    }

    @Test
    void archiveDelegatesWithIdempotencyHeader() {
        SupportTicketArchiveRequest request = new SupportTicketArchiveRequest(true, "Marina K.", "routine archive");
        when(ticketService.archive("TK-1", "idem-m2-archive", request)).thenReturn(ApiResult.ok(null));

        assertThat(controller.archive("TK-1", "idem-m2-archive", request).getCode()).isZero();

        verify(ticketService).archive("TK-1", "idem-m2-archive", request);
    }

    @Test
    void escalateDelegatesWithIdempotencyHeader() {
        SupportTicketEscalateRequest request = new SupportTicketEscalateRequest("agent-1", "Marina K.", "Marina K.", "customer needs realtime help");
        when(ticketService.escalate("TK-1", "idem-m2-escalate", request)).thenReturn(ApiResult.ok(null));

        assertThat(controller.escalate("TK-1", "idem-m2-escalate", request).getCode()).isZero();

        verify(ticketService).escalate("TK-1", "idem-m2-escalate", request);
    }
}
