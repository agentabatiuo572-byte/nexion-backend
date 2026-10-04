package ffdd.opsconsole.finance.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ffdd.opsconsole.finance.application.D1BankOrderService;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.exception.GlobalExceptionHandler;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class OpsD1BankOrderControllerTest {
    private AnnotationConfigApplicationContext context;
    private D1BankOrderService service;
    private AuditLogService audit;
    private OpsD1BankOrderController controller;

    @BeforeEach void setUp() {
        context = new AnnotationConfigApplicationContext(SecurityConfig.class);
        service = context.getBean(D1BankOrderService.class);
        audit = context.getBean(AuditLogService.class);
        controller = context.getBean(OpsD1BankOrderController.class);
    }

    @AfterEach void close() { SecurityContextHolder.clearContext(); context.close(); }

    @Test void anonymousAndAppUsersCannotReadOrCreditBankOrders() {
        assertThatThrownBy(() -> controller.list(null, null, null, 1, 20)).isInstanceOf(org.springframework.security.core.AuthenticationException.class);
        authenticate("ROLE_USER");
        ((TestingAuthenticationToken) SecurityContextHolder.getContext().getAuthentication())
                .setDetails(Map.of("subjectType", "USER"));
        assertThatThrownBy(() -> controller.list(null, null, null, 1, 20)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> controller.manualCredit("VQR-TEST", "key", null)).isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(service, audit);
    }

    @Test void readOnlyFinanceOperatorCannotCreditAndUsesServerPagination() {
        authenticate("finance_d1_read");
        when(service.list("HDPAY", "UNKNOWN", "VQR-TEST", 2, 3)).thenReturn(ApiResult.ok(Map.of("total", 8L)));
        assertThat(controller.list("HDPAY", "UNKNOWN", "VQR-TEST", 2, 3).getData()).containsEntry("total", 8L);
        assertThatThrownBy(() -> controller.manualCredit("VQR-TEST", "key", null)).isInstanceOf(AccessDeniedException.class);
        verify(service).list("HDPAY", "UNKNOWN", "VQR-TEST", 2, 3);
        verifyNoInteractions(audit);
    }

    @Test void authorizedBusinessConflictKeepsJsonErrorAndAuditsTheAuthenticatedActor() throws Exception {
        authenticate("finance_d1_bank_reconcile");
        when(service.manualCredit(eq("VQR-TEST"), eq("original-command"), any()))
                .thenThrow(new BizException(409, "HDPAY_MANUAL_VERSION_CONFLICT"));
        var mvc = MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new GlobalExceptionHandler(audit)).build();
        mvc.perform(post("/api/admin/finance/vietqr/orders/VQR-TEST/manual-credit")
                        .header("Idempotency-Key", "original-command").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                          {"expectedVersion":0,"providerVersion":0,"receivedVnd":200000,
                           "paymentReference":"REF-TEST","receivedAt":"2026-10-04T00:00:00Z",
                           "evidenceRef":"media:vqr_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                           "reason":"manual verified receipt","operator":"spoofed"}
                          """))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(409))
                .andExpect(jsonPath("$.message").value("HDPAY_MANUAL_VERSION_CONFLICT"));
        var captured = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
        verify(audit).recordRequiredInNewTransaction(captured.capture());
        assertThat(captured.getValue().getActorUsername()).isEqualTo("test-finance");
        assertThat(captured.getValue().getActorType()).isEqualTo("ADMIN");
        assertThat(captured.getValue().getAction()).isEqualTo("D1_HDPAY_MANUAL_CREDIT_REJECTED");
    }

    private void authenticate(String authority) {
        var auth = new TestingAuthenticationToken("77", "unused", authority);
        auth.setDetails(Map.of("subjectType", "ADMIN", "username", "test-finance"));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableMethodSecurity
    static class SecurityConfig {
        @Bean D1BankOrderService service() { return mock(D1BankOrderService.class); }
        @Bean AuditLogService audit() { return mock(AuditLogService.class); }
        @Bean OpsD1BankOrderController controller(D1BankOrderService service, AuditLogService audit) {
            return new OpsD1BankOrderController(service, audit);
        }
    }
}
