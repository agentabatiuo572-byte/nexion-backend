package ffdd.opsconsole.team.web;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.exception.GlobalExceptionHandler;
import ffdd.opsconsole.team.application.BinaryCommissionSettlementService;
import ffdd.opsconsole.team.application.BinaryCommissionSettlementService.AssignmentPage;
import ffdd.opsconsole.team.dto.BinaryLegAssignmentRequest;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.*;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class BinaryAssignmentReadControllerTest {
    @Configuration @EnableMethodSecurity
    static class Config {
        @Bean BinaryCommissionSettlementService service() { return mock(BinaryCommissionSettlementService.class); }
        @Bean OpsBinarySettlementController controller(BinaryCommissionSettlementService service) {
            return new OpsBinarySettlementController(service);
        }
    }
    AnnotationConfigApplicationContext context;
    BinaryCommissionSettlementService service;
    OpsBinarySettlementController controller;
    @BeforeEach void setup() {
        context = new AnnotationConfigApplicationContext(Config.class);
        service = context.getBean(BinaryCommissionSettlementService.class);
        controller = context.getBean(OpsBinarySettlementController.class);
    }
    @AfterEach void cleanup() { SecurityContextHolder.clearContext(); context.close(); }

    @Test void exposesOwnerScopedReadWithoutInvokingAWriter() throws Exception {
        var auth = authenticate("ADMIN", "network_f3_read");
        when(service.assignments(42L, null, "ALL", 1, 20))
                .thenReturn(new AssignmentPage(42L, "ALL", 1, 20, 0, List.of()));
        MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler(mock(AuditLogService.class))).build()
                .perform(get("/api/admin/teams/binary/assignments")
                        .param("ownerUserId", "42").principal(auth))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.ownerUserId").value(42))
                .andExpect(jsonPath("$.data.total").value(0)).andExpect(jsonPath("$.data.records").isEmpty());
        verify(service).assignments(42L, null, "ALL", 1, 20);
        verifyNoMoreInteractions(service);
    }

    @Test void readPermissionCannotWriteAndWriterPermissionAloneCannotRead() {
        var reader = authenticate("ADMIN", "network_f3_read");
        controller.assignments(42L, 80L, "ALL", 1, 20, reader);
        verify(service).assignments(42L, 80L, "ALL", 1, 20);
        assertThatThrownBy(() -> controller.assign(new BinaryLegAssignmentRequest(42L, 80L, "A"), reader))
                .isInstanceOf(AccessDeniedException.class);
        var writer = authenticate("ADMIN", "network_f3_write");
        assertThatThrownBy(() -> controller.assignments(42L, null, "ALL", 1, 20, writer))
                .isInstanceOf(AccessDeniedException.class);
        verifyNoMoreInteractions(service);
    }

    @Test void forgedUserSubjectCannotReadEvenWithMatchingAuthority() {
        var user = authenticate("USER", "network_f3_read");
        assertThatThrownBy(() -> controller.assignments(42L, null, "ALL", 1, 20, user))
                .isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(service);
    }

    @Test void missingAuthorityCannotRead() {
        var admin = authenticate("ADMIN", "network_f1_read");
        assertThatThrownBy(() -> controller.assignments(42L, null, "ALL", 1, 20, admin))
                .isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(service);
    }

    @Test void writerStillUsesAuthenticatedActorAndExistingReplayResponse() {
        var writer = authenticate("ADMIN", "network_f3_write");
        when(service.assignLeg(42L, 80L, "B", 7L, "operator"))
                .thenReturn(new BinaryCommissionSettlementService.AssignmentResult(42L, 80L, "B", true));
        assertThat(controller.assign(new BinaryLegAssignmentRequest(42L, 80L, "B"), writer)
                .getData().replayed()).isTrue();
        verify(service).assignLeg(42L, 80L, "B", 7L, "operator");
        verifyNoMoreInteractions(service);
    }

    private UsernamePasswordAuthenticationToken authenticate(String subject, String permission) {
        var auth = new UsernamePasswordAuthenticationToken("7", "", List.of(new SimpleGrantedAuthority(permission)));
        auth.setDetails(Map.of("subjectType", subject, "username", "operator"));
        SecurityContextHolder.getContext().setAuthentication(auth);
        return auth;
    }
}
