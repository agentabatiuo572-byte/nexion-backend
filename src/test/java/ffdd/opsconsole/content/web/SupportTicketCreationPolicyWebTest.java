package ffdd.opsconsole.content.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import ffdd.opsconsole.content.application.*;
import ffdd.opsconsole.content.application.SupportTicketCreationPolicyService.CreationPolicy;
import ffdd.opsconsole.shared.exception.GlobalExceptionHandler;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class SupportTicketCreationPolicyWebTest {
    @Test void policyUsesAuthenticatedCustomerAndIsNotCacheable() {
        var service = mock(SupportTicketCreationPolicyService.class);
        var guard = mock(ProductionSupportPathGuard.class);
        var controller = new AppSupportController(mock(AppSupportService.class), guard, service);
        var auth = new UsernamePasswordAuthenticationToken("42", null, List.of());
        auth.setDetails(Map.of("subjectType", "USER"));
        when(service.policy(42L)).thenReturn(allowed());
        var response = new MockHttpServletResponse();
        assertThat(controller.ticketCreationPolicy(auth, response).getData()).isEqualTo(allowed());
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        var order = inOrder(guard, service);
        order.verify(guard).requireAllowed(42L);
        order.verify(service).policy(42L);
        auth.setDetails(Map.of("subjectType", "ADMIN"));
        assertThat(controller.ticketCreationPolicy(auth, response).getCode()).isEqualTo(403);
        verifyNoMoreInteractions(service);
    }

    @Test void businessExceptionEnvelopePreservesPolicyAheadOfTheGenericBizHandler() throws Exception {
        var app = mock(AppSupportService.class);
        var policy = new CreationPolicy(false, "SUPPORT_TICKET_CREATE_DUPLICATE", 30, null,
                "TK-42", 60, 24, 10, 3, 1, 1);
        when(app.createTicket(anyLong(), anyString(), any())).thenThrow(new SupportTicketCreationRejectedException(policy));
        var controller = new AppSupportController(app, mock(ProductionSupportPathGuard.class), mock(SupportTicketCreationPolicyService.class));
        var mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler(mock(ffdd.opsconsole.shared.audit.AuditLogService.class)),
                        new SupportTicketCreationExceptionHandler()).build();
        var auth = new UsernamePasswordAuthenticationToken("42", null, List.of());
        auth.setDetails(Map.of("subjectType", "USER"));
        mvc.perform(post("/api/app/support/tickets").principal(auth).header("Idempotency-Key", "repeat")
                .contentType("application/json").content("{\"category\":\"other\",\"title\":\"Title\",\"body\":\"Body\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value(409))
                .andExpect(jsonPath("$.message").value(policy.reasonCode()))
                .andExpect(jsonPath("$.data.existingTicketNo").value("TK-42"))
                .andExpect(jsonPath("$.data.allowed").value(false));
    }

    private CreationPolicy allowed() { return new CreationPolicy(true, null, 0, null, null, 60, 24, 10, 3, 0, 0); }
}
