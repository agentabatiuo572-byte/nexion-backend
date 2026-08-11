package ffdd.opsconsole.shared.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.platform.application.A2RuntimePolicy;
import ffdd.opsconsole.auth.application.OpsAdminAuthService;
import ffdd.opsconsole.auth.web.OpsAdminAuthController;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.exception.GlobalExceptionHandler;
import ffdd.opsconsole.shared.security.GatewaySecurityProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import java.util.Map;
import java.util.List;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.core.Ordered;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AdminReasonConstraintAdviceTest {
    private final A2RuntimePolicy policy = mock(A2RuntimePolicy.class);
    private final AdminReasonConstraintAdvice advice = new AdminReasonConstraintAdvice(policy);

    @BeforeEach
    void initializeAdvisorLifecycle() {
        advice.initializeAdvisor();
    }

    @Test
    void enforcesUnicodeCodePointMinimumForEveryReasonBearingAdminWriteBody() {
        when(policy.reasonMinChars()).thenReturn(4);

        assertThatThrownBy(() -> advice.validateReasonBearingBody(new ReasonBody("三字短")))
                .isInstanceOf(BizException.class)
                .hasMessage("REASON_TOO_SHORT_MIN_4");
        ReasonBody accepted = new ReasonBody("四字刚好");
        assertThat(advice.validateReasonBearingBody(accepted)).isSameAs(accepted);
    }

    @Test
    void leavesBodiesWithoutAReasonContractUntouched() {
        Object body = new Object();
        assertThat(advice.validateReasonBearingBody(body)).isSameAs(body);
    }

    @Test
    void enforcesTheSamePolicyForMapBasedAdminCommands() {
        when(policy.reasonMinChars()).thenReturn(8);
        assertThatThrownBy(() -> advice.validateReasonBearingBody(Map.of("reason", "short")))
                .isInstanceOf(BizException.class)
                .hasMessage("REASON_TOO_SHORT_MIN_8");
    }

    @Test
    void recursivelyFindsNestedAndListReasonsAndRejectsInvisibleOnlyText() {
        when(policy.reasonMinChars()).thenReturn(4);
        assertThatThrownBy(() -> advice.validateReasonBearingBody(List.of(Map.of(
                "command", Map.of("reason", "\u200B\u200D\u0001\u0002")))))
                .isInstanceOf(BizException.class)
                .hasMessage("REASON_TOO_SHORT_MIN_4");
        assertThat(advice.validateReasonBearingBody(List.of(Map.of(
                "command", Map.of("reason", "审计通过四字"))))).isNotNull();
    }

    @Test
    void controlledBootstrapUsesProposedTtlWhileOtherMissingPolicyWritesFailClosed() {
        when(policy.reasonPolicyMissing()).thenReturn(true);
        Map<String, Object> bootstrap = Map.of("value", "12 字", "reason", "abcdefghijkl");
        assertThat(advice.validateReasonBearingBody(bootstrap,
                "/api/admin/platform/audit/mechanism-params/ttl")).isSameAs(bootstrap);
        assertThatThrownBy(() -> advice.validateReasonBearingBody(Map.of("reason", "abcdefghijkl"),
                "/api/admin/platform/events/params/day0"))
                .isInstanceOf(BizException.class)
                .hasMessage("A2_REASON_POLICY_UNAVAILABLE");
    }

    @Test
    void advisorRunsAfterMethodSecuritySoUnauthorizedCallersAreRejectedBeforePolicyLookup() {
        assertThat(advice.getOrder()).isEqualTo(Ordered.LOWEST_PRECEDENCE);
    }

    @Test
    void sensitiveGetExportConsumesHeaderOrRequestParamReason() {
        when(policy.reasonMinChars()).thenReturn(8);
        MockHttpServletRequest headerRequest = new MockHttpServletRequest(
                "GET", "/api/admin/audit/exports/current.csv");
        headerRequest.addHeader("X-Operation-Reason", "export audit evidence");
        assertThat(advice.protectedAdminRequest(headerRequest)).isTrue();
        advice.validateAdminRequest(new Object[0], headerRequest);

        MockHttpServletRequest queryRequest = new MockHttpServletRequest(
                "GET", "/api/admin/audit/download");
        queryRequest.addParameter("reason", "download audit evidence");
        advice.validateAdminRequest(new Object[0], queryRequest);
    }

    @Test
    void controlledBootstrapAcceptsHeaderReasonButStillDerivesMinimumFromProposedValue() {
        when(policy.reasonPolicyMissing()).thenReturn(true);
        MockHttpServletRequest request = new MockHttpServletRequest(
                "PUT", "/api/admin/platform/audit/mechanism-params/ttl");
        request.addHeader("X-Operation-Reason", "abcdefghijkl");

        advice.validateAdminRequest(new Object[]{Map.of("value", "12 字")}, request);
        request.removeHeader("X-Operation-Reason");
        request.addHeader("X-Operation-Reason", "short");
        assertThatThrownBy(() -> advice.validateAdminRequest(
                new Object[]{Map.of("value", "12 字")}, request))
                .isInstanceOf(BizException.class)
                .hasMessage("REASON_TOO_SHORT_MIN_12");
    }

    @Test
    void realMockMvcAuthLifecycleWritesAreNeverBlockedByA2ReasonAdvice() throws Exception {
        OpsAdminAuthService authService = mock(OpsAdminAuthService.class);
        OpsAdminAuthController target = new OpsAdminAuthController(authService, new GatewaySecurityProperties());
        MockMvc mvc = MockMvcBuilders.standaloneSetup(advised(target)).build();
        TestingAuthenticationToken authentication = new TestingAuthenticationToken(
                "admin-1", "n/a", "platform_a1_read");
        SecurityContextHolder.getContext().setAuthentication(authentication);
        try {
            mvc.perform(post("/api/admin/auth/login")
                            .header("Authorization", "Bearer already-present")
                            .contentType("application/json")
                            .content("{\"username\":\"admin\",\"password\":\"secret\"}"))
                    .andExpect(status().isOk());
            mvc.perform(post("/api/admin/auth/logout").principal(authentication))
                    .andExpect(status().isOk());
            mvc.perform(post("/api/admin/auth/password/change")
                            .principal(authentication)
                            .contentType("application/json")
                            .content("{\"currentPassword\":\"old\",\"newPassword\":\"new\"}"))
                    .andExpect(status().isOk());

            verify(authService).logout(authentication);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    @Test
    void realMockMvcReasonContractRejectsNMinusOneAndAcceptsN() throws Exception {
        when(policy.reasonMinChars()).thenReturn(8);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(advised(new ReasonContractController()))
                .setControllerAdvice(new GlobalExceptionHandler(mock(AuditLogService.class)))
                .build();
        SecurityContextHolder.getContext().setAuthentication(
                new TestingAuthenticationToken("admin-1", "n/a", "platform_a1_write"));
        try {
            mvc.perform(post("/api/admin/test-reason")
                            .contentType("application/json")
                            .content("{\"reason\":\"1234567\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(422));
            mvc.perform(post("/api/admin/test-reason")
                            .contentType("application/json")
                            .content("{\"reason\":\"12345678\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(0));
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    @Test
    void realMockMvcOrdinaryB2B3B4AndL6ExportsAreNotSelectedByTheirUrls() throws Exception {
        MockMvc mvc = MockMvcBuilders.standaloneSetup(advised(new OrdinaryExportController())).build();
        SecurityContextHolder.getContext().setAuthentication(
                new TestingAuthenticationToken("admin-1", "n/a", "read-export"));
        try {
            for (String path : List.of(
                    "/api/admin/treasury/b2/liabilities/export",
                    "/api/admin/funnel/export",
                    "/api/admin/phase/distribution/export",
                    "/api/admin/bi/export/behavior")) {
                mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(path))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.code").value(0));
            }
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    @Test
    void realMockMvcExplicitReasonAnnotationRejectsMissingAndNMinusOneButAcceptsN() throws Exception {
        when(policy.reasonMinChars()).thenReturn(8);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(advised(new ReasonContractController()))
                .setControllerAdvice(new GlobalExceptionHandler(mock(AuditLogService.class)))
                .build();
        SecurityContextHolder.getContext().setAuthentication(
                new TestingAuthenticationToken("admin-1", "n/a", "high-risk-write"));
        try {
            mvc.perform(post("/api/admin/test-reason/annotated"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(422));
            mvc.perform(post("/api/admin/test-reason/annotated")
                            .header("X-Operation-Reason", "1234567"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(422));
            mvc.perform(post("/api/admin/test-reason/annotated")
                            .header("X-Operation-Reason", "12345678"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0));
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    @Test
    void realMockMvcReasonTextQueryUsesTheSameNMinusOneAndNPolicy() throws Exception {
        when(policy.reasonMinChars()).thenReturn(8);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(advised(new ReasonContractController()))
                .setControllerAdvice(new GlobalExceptionHandler(mock(AuditLogService.class)))
                .build();
        SecurityContextHolder.getContext().setAuthentication(
                new TestingAuthenticationToken("admin-1", "n/a", "reason-query"));
        try {
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                            "/api/admin/test-reason/query").queryParam("reasonText", "1234567"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(422));
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                            "/api/admin/test-reason/query").queryParam("reasonText", "12345678"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0));
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    @SuppressWarnings("unchecked")
    private <T> T advised(T target) {
        ProxyFactory proxyFactory = new ProxyFactory(target);
        proxyFactory.addAdvisor(advice);
        return (T) proxyFactory.getProxy();
    }

    @RestController
    @RequestMapping("/api/admin/test-reason")
    static class ReasonContractController {
        @PostMapping
        ApiResult<Map<String, Boolean>> mutate(@RequestBody ReasonBody body) {
            return ApiResult.ok(Map.of("accepted", true));
        }

        @A2ReasonRequired
        @PostMapping("/annotated")
        ApiResult<Map<String, Boolean>> annotated() {
            return ApiResult.ok(Map.of("accepted", true));
        }

        @GetMapping("/query")
        ApiResult<Map<String, Boolean>> query(@RequestParam(required = false) String reasonText) {
            return ApiResult.ok(Map.of("accepted", true));
        }
    }

    @RestController
    @RequestMapping("/api/admin")
    static class OrdinaryExportController {
        @GetMapping({
                "/treasury/b2/liabilities/export",
                "/funnel/export",
                "/phase/distribution/export",
                "/bi/export/behavior"})
        ApiResult<Map<String, Boolean>> export() {
            return ApiResult.ok(Map.of("accepted", true));
        }
    }

    private record ReasonBody(String reason) {}
}
