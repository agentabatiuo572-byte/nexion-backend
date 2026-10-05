package ffdd.opsconsole.team.web;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.exception.GlobalExceptionHandler;
import ffdd.opsconsole.team.application.DirectReferralPolicyService;
import ffdd.opsconsole.team.application.DirectReferralService;
import ffdd.opsconsole.team.domain.DirectReferralPolicy;
import ffdd.opsconsole.team.dto.DirectReferralPolicyRequest;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class DirectReferralControllerTest {
    @Configuration
    @EnableMethodSecurity
    static class Config {
        @Bean DirectReferralPolicyService policies() {
            return spy(new DirectReferralPolicyService(
                    mock(ffdd.opsconsole.team.mapper.DirectReferralMapper.class),
                    mock(ffdd.opsconsole.platform.facade.PlatformConfigFacade.class),
                    mock(ffdd.opsconsole.market.mapper.NexMarketMapper.class),
                    mock(ffdd.opsconsole.treasury.facade.TreasuryCoverageFacade.class),
                    mock(AuditLogService.class), new com.fasterxml.jackson.databind.ObjectMapper(),
                    java.time.Clock.systemUTC(), new org.springframework.mock.env.MockEnvironment()));
        }
        @Bean DirectReferralService settlements() { return mock(DirectReferralService.class); }
        @Bean DirectReferralController controller(DirectReferralPolicyService policies, DirectReferralService settlements) {
            return new DirectReferralController(policies, settlements);
        }
    }

    private AnnotationConfigApplicationContext context;
    private DirectReferralController controller;
    private DirectReferralPolicyService policies;
    private DirectReferralService settlements;
    private MockMvc http;

    @BeforeEach void setUp() {
        context = new AnnotationConfigApplicationContext(Config.class);
        controller = context.getBean(DirectReferralController.class);
        policies = context.getBean(DirectReferralPolicyService.class);
        settlements = context.getBean(DirectReferralService.class);
        http = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler(mock(AuditLogService.class))).build();
    }

    @AfterEach void tearDown() {
        SecurityContextHolder.clearContext();
        context.close();
    }

    @Test void publicPolicyIsAReadOnlyProjection() throws Exception {
        doReturn(Map.of("policyVersion", 0, "configured", false)).when(policies).current();
        http.perform(get("/api/config/commission/direct-referral"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.configured").value(false));
        verify(policies).current();
        verifyNoInteractions(settlements);
    }

    @Test void adminReadPermissionDoesNotGrantWritePermission() {
        authenticate("ops-reader", "ADMIN", "network_f2_read");
        doReturn(Map.of("policyVersion", 1)).when(policies).current();
        assertThat(controller.adminPolicy().getData()).containsEntry("policyVersion", 1);
        assertThatThrownBy(() -> controller.update("idempotent-key", request()))
                .isInstanceOf(AccessDeniedException.class);
        verify(policies, never()).publish(anyString(), any());
    }

    @Test void unrelatedAuthorityCannotReadOrPublishAdminPolicy() {
        authenticate("other-operator", "ADMIN", "network_f5_read");
        assertThatThrownBy(controller::adminPolicy).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> controller.update("idempotent-key", request()))
                .isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(policies, settlements);
    }

    @Test void httpWriteBindsOneVersionAndBothRulesThenDefersToApprovalGuard() throws Exception {
        var auth = authenticate("ops-editor", "ADMIN", "network_f2_royalty_rate");
        http.perform(put("/api/admin/teams/direct-referral-policy").principal(auth)
                .header("Idempotency-Key", "dr-policy-1").contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"expectedVersion":3,"purchase":{"enabled":false,"totalRatePct":0,"usdtSharePct":50,"coolingDays":0},
                     "deviceEarning":{"enabled":false,"totalRatePct":0,"usdtSharePct":50,"coolingDays":0},"reason":"approval must be mandatory"}
                    """))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.message").value("A2_CONFIRMATION_REQUIRED"));
        var captured = org.mockito.ArgumentCaptor.forClass(DirectReferralPolicyRequest.class);
        verify(policies).publish(eq("dr-policy-1"), captured.capture());
        assertThat(captured.getValue().expectedVersion()).isEqualTo(3L);
        assertThat(captured.getValue().purchase()).isEqualTo(captured.getValue().deviceEarning());
        verifyNoInteractions(settlements);
    }

    @Test void realPolicyServiceRejectsMissingKeyShortReasonAndApprovalBypassOverHttp() throws Exception {
        var auth = authenticate("ops-editor", "ADMIN", "network_f2_royalty_rate");
        String body = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(request());
        http.perform(put("/api/admin/teams/direct-referral-policy").principal(auth)
                .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("IDEMPOTENCY_KEY_REQUIRED"));
        http.perform(put("/api/admin/teams/direct-referral-policy").principal(auth).header("Idempotency-Key", "dr-short")
                .contentType(MediaType.APPLICATION_JSON).content(body.replace("permission boundary check", "short")))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.message").value("DIRECT_REFERRAL_POLICY_REQUEST_INVALID"));
        http.perform(put("/api/admin/teams/direct-referral-policy").principal(auth).header("Idempotency-Key", "dr-bypass")
                .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.message").value("A2_CONFIRMATION_REQUIRED"));
        verifyNoInteractions(settlements);
    }

    @Test void rejectsClientEffectiveTimeAndPriceBeforePolicyExecution() throws Exception {
        var auth = authenticate("ops-editor", "ADMIN", "network_f2_royalty_rate");
        String body = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(request());
        for (String extra : List.of("\"effectiveAt\":\"2099-01-01T00:00:00Z\"", "\"nexUsdtPrice\":1")) {
            http.perform(put("/api/admin/teams/direct-referral-policy").principal(auth).header("Idempotency-Key", "dr-invalid-field")
                    .contentType(MediaType.APPLICATION_JSON).content(body.substring(0, body.length()-1) + "," + extra + "}"))
                    .andExpect(jsonPath("$.code").value(400)).andExpect(jsonPath("$.message").value("REQUEST_BODY_INVALID"));
        }
        verifyNoInteractions(policies, settlements);
    }

    @Test void unrelatedAuthorityCannotWriteOverHttp() throws Exception {
        var auth = authenticate("ops-reader", "ADMIN", "network_f2_read");
        http.perform(put("/api/admin/teams/direct-referral-policy").principal(auth).header("Idempotency-Key", "dr-no-permission")
                .contentType(MediaType.APPLICATION_JSON).content(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(request())))
                .andExpect(jsonPath("$.code").value(403));
        verifyNoInteractions(policies, settlements);
    }

    @Test void insightsRejectMissingAdminAndMalformedMemberSubjects() {
        assertThat(controller.insights(null, "all", 1, 20, null).getCode()).isEqualTo(403);
        var admin = authenticate("42", "ADMIN", "network_f2_read");
        assertThat(controller.insights(admin, "all", 1, 20, null).getCode()).isEqualTo(403);
        var malformed = authenticate("not-a-member-id", "USER");
        assertThat(controller.insights(malformed, "all", 1, 20, null).getCode()).isEqualTo(403);
        var unauthenticated = new UsernamePasswordAuthenticationToken("42", "unused");
        unauthenticated.setDetails(Map.of("subjectType", "USER"));
        assertThat(controller.insights(unauthenticated, "all", 1, 20, null).getCode()).isEqualTo(403);
        verifyNoInteractions(settlements);
    }

    @Test void insightsIgnoreForgedMemberQueryAndUseAuthenticatedSubject() throws Exception {
        var member = authenticate("42", "USER");
        when(settlements.insights(42L, "month", 1, 20, null)).thenReturn(Map.of("totalRows", 0));
        http.perform(get("/api/app/team/insights/direct-referral").principal(member).param("userId", "999"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalRows").value(0));
        verify(settlements).insights(42L, "month", 1, 20, null);
        verifyNoMoreInteractions(settlements);
    }

    private DirectReferralPolicyRequest request() {
        return new DirectReferralPolicyRequest(0L, DirectReferralPolicy.DISABLED, DirectReferralPolicy.DISABLED, "permission boundary check");
    }

    private UsernamePasswordAuthenticationToken authenticate(String principal, String subject, String... authorities) {
        var auth = new UsernamePasswordAuthenticationToken(principal, null,
                java.util.Arrays.stream(authorities).map(SimpleGrantedAuthority::new).toList());
        auth.setDetails(Map.of("subjectType", subject));
        SecurityContextHolder.getContext().setAuthentication(auth);
        return auth;
    }
}
