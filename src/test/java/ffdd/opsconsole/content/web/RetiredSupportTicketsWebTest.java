package ffdd.opsconsole.content.web;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ffdd.opsconsole.content.application.OpsSupportAgentService;
import ffdd.opsconsole.content.application.OpsSupportLoadConfigService;
import ffdd.opsconsole.content.application.ProductionSupportPathGuard;
import ffdd.opsconsole.shared.api.ApiResult;
import java.util.Map;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpMethod;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The ticket controllers were removed, so retired URLs no longer have a dedicated handler.
 * Old App/console clients must still get the explicit 410 retirement contract, not a 404/405,
 * while the seat-capacity endpoints that share the historical prefix stay reachable.
 */
@WebMvcTest
@AutoConfigureMockMvc(addFilters = false)
@ContextConfiguration(classes = {RetiredSupportTickets.class, OpsSupportLoadConfigController.class})
class RetiredSupportTicketsWebTest {
    @Autowired private MockMvc mockMvc;
    @MockBean private OpsSupportLoadConfigService loadConfigService;
    @MockBean private OpsSupportAgentService supportAgentService;
    @MockBean private ProductionSupportPathGuard productionPathGuard;

    @ParameterizedTest
    @CsvSource({
            // App ticket workflow
            "GET,/api/app/support/tickets",
            "GET,/api/app/support/tickets/cursor",
            "GET,/api/app/support/tickets/creation-policy",
            "GET,/api/app/support/tickets/TK-1",
            "POST,/api/app/support/tickets",
            "POST,/api/app/support/tickets/TK-1/read",
            "POST,/api/app/support/tickets/TK-1/replies",
            "POST,/api/app/support/tickets/TK-1/close",
            "POST,/api/app/support/conversations/CV-1/ticket",
            "GET,/api/app/support/sla-targets",
            // App acceptance sandbox
            "GET,/api/app/support/acceptance/tickets",
            "POST,/api/app/support/acceptance/tickets/ATK-1/replies",
            "POST,/api/app/support/acceptance/conversations/ACV-1/ticket",
            "GET,/api/app/support/acceptance/sla-targets",
            // Admin ticket desk
            "GET,/api/admin/content/tickets",
            "GET,/api/admin/content/tickets/overview",
            "GET,/api/admin/content/tickets/assignee-candidates",
            "POST,/api/admin/content/tickets",
            "GET,/api/admin/content/tickets/TK-1",
            "POST,/api/admin/content/tickets/TK-1/replies",
            "POST,/api/admin/content/tickets/TK-1/internal-notes",
            "PATCH,/api/admin/content/tickets/TK-1/status",
            "PATCH,/api/admin/content/tickets/TK-1/priority",
            "PATCH,/api/admin/content/tickets/TK-1/assignee",
            "PATCH,/api/admin/content/tickets/TK-1/archive",
            "POST,/api/admin/content/tickets/TK-1/escalate",
            "POST,/api/admin/content/conversations/CV-1/ticket",
            "PATCH,/api/admin/content/knowledge/sla/technical",
            // Admin acceptance sandbox
            "GET,/api/admin/content/support/acceptance/tickets",
            "GET,/api/admin/content/support/acceptance/tickets/ATK-1",
            "POST,/api/admin/content/support/acceptance/tickets/ATK-1/reply",
            "POST,/api/admin/content/support/acceptance/tickets/ATK-1/close",
    })
    void retiredTicketRoutesStillAnswer410(String method, String path) throws Exception {
        // DispatcherServlet is mapped at "/", so in the container the servlet path is the whole API path.
        mockMvc.perform(request(HttpMethod.valueOf(method), path).servletPath(path)
                        .header("Idempotency-Key", "retired-ticket-key")
                        .contentType("application/json").content("{}"))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value(410))
                .andExpect(jsonPath("$.message").value("SUPPORT_TICKETS_RETIRED"));
    }

    @Test
    void seatCapacityUnderTheHistoricalPrefixStaysReachable() throws Exception {
        when(loadConfigService.loadConfig()).thenReturn(ApiResult.ok(Map.of("domain", "M1")));
        when(loadConfigService.rebalanceLoad(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(ApiResult.ok(Map.of("rebalanced", true)));
        when(supportAgentService.canManageSupportSeats()).thenReturn(true);

        mockMvc.perform(request(HttpMethod.GET, "/api/admin/content/tickets/load-config")
                        .servletPath("/api/admin/content/tickets/load-config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.domain").value("M1"));
        mockMvc.perform(request(HttpMethod.POST, "/api/admin/content/tickets/load-config/rebalance")
                        .servletPath("/api/admin/content/tickets/load-config/rebalance")
                        .header("Idempotency-Key", "idem-m1-rebalance")
                        .contentType("application/json").content("{\"expectedVersion\":1,\"agents\":[],\"reason\":\"rebalance load\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rebalanced").value(true));
    }
}
