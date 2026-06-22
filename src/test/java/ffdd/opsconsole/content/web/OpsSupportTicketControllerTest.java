package ffdd.opsconsole.content.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.content.application.OpsSupportTicketService;
import ffdd.opsconsole.content.dto.SupportLoadConfigUpdateRequest;
import ffdd.opsconsole.content.dto.SupportLoadRebalanceRequest;
import ffdd.opsconsole.content.dto.SupportTicketAssigneeRequest;
import ffdd.opsconsole.content.dto.SupportTicketCreateRequest;
import ffdd.opsconsole.content.dto.SupportTicketPriorityRequest;
import ffdd.opsconsole.content.dto.SupportTicketReplyRequest;
import ffdd.opsconsole.content.dto.SupportTicketStatusRequest;
import ffdd.opsconsole.shared.api.ApiResult;
import java.util.Map;
import org.junit.jupiter.api.Test;

class OpsSupportTicketControllerTest {
    private final OpsSupportTicketService ticketService = mock(OpsSupportTicketService.class);
    private final OpsSupportTicketController controller = new OpsSupportTicketController(ticketService);

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

        assertThat(controller.updateLoadConfig("idem-m1-load", request).getData()).containsEntry("ok", true);

        verify(ticketService).updateLoadConfig("idem-m1-load", request);
    }

    @Test
    void rebalanceLoadDelegatesWithIdempotencyHeader() {
        SupportLoadRebalanceRequest request =
                new SupportLoadRebalanceRequest(java.util.List.of(Map.of("id", "agent-1", "cap", 4)), "superadmin", "rebalance load");
        when(ticketService.rebalanceLoad("idem-m1-rebalance", request)).thenReturn(ApiResult.ok(Map.of("ok", true)));

        assertThat(controller.rebalanceLoad("idem-m1-rebalance", request).getData()).containsEntry("ok", true);

        verify(ticketService).rebalanceLoad("idem-m1-rebalance", request);
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
}
