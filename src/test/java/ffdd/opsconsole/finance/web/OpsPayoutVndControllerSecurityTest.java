package ffdd.opsconsole.finance.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ffdd.opsconsole.finance.application.PayoutVndCommandBoundary;
import ffdd.opsconsole.finance.application.PayoutVndConfigService;
import ffdd.opsconsole.finance.application.PayoutVndSandboxService;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import ffdd.opsconsole.shared.security.AdminRbacAuthorizationFilter;
import ffdd.opsconsole.shared.security.JwtAuthenticationFilter;
import ffdd.opsconsole.shared.security.SecurityConfig;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(OpsPayoutVndController.class)
@ActiveProfiles("dev")
@Import({SecurityConfig.class, PayoutVndCommandBoundary.class})
@ContextConfiguration(classes = {
        OpsPayoutVndController.class,
        SecurityConfig.class,
        PayoutVndCommandBoundary.class
})
class OpsPayoutVndControllerSecurityTest {
    @Autowired private MockMvc mockMvc;
    @MockBean private PayoutVndConfigService service;
    @MockBean private PayoutVndSandboxService sandbox;
    @MockBean private AuditLogService audit;
    @MockBean private AdminIdempotencyService idempotency;
    @MockBean private JwtAuthenticationFilter jwtAuthenticationFilter;
    @MockBean private AdminRbacAuthorizationFilter adminRbacAuthorizationFilter;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        doAnswer(invocation -> {
            var chain = invocation.getArgument(2, jakarta.servlet.FilterChain.class);
            chain.doFilter(invocation.getArgument(0), invocation.getArgument(1));
            return null;
        }).when(jwtAuthenticationFilter).doFilter(any(), any(), any());
        doAnswer(invocation -> {
            var chain = invocation.getArgument(2, jakarta.servlet.FilterChain.class);
            chain.doFilter(invocation.getArgument(0), invocation.getArgument(1));
            return null;
        }).when(adminRbacAuthorizationFilter).doFilter(any(), any(), any());

        Map<String, Object> replay = new HashMap<>();
        when(idempotency.execute(anyString(), anyString(), anyString(), any(), any()))
                .thenAnswer(invocation -> {
                    String cacheKey = invocation.getArgument(0) + ":" + invocation.getArgument(1);
                    if (replay.containsKey(cacheKey)) return replay.get(cacheKey);
                    Object result = ((Supplier<Object>) invocation.getArgument(4)).get();
                    replay.put(cacheKey, result);
                    return result;
                });
        when(service.overview()).thenReturn(ApiResult.ok(Map.of("version", 1L)));
        when(service.update(any())).thenReturn(ApiResult.ok(Map.of("version", 2L)));
        when(service.updateChannel(any())).thenReturn(ApiResult.ok(Map.of("version", 2L)));
    }

    @Test
    void anonymousAndUnrelatedAuthoritiesCannotReadOrWriteD7() throws Exception {
        mockMvc.perform(get("/api/admin/finance/payout-vnd/config"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/admin/finance/payout-vnd/config")
                        .with(user("other").authorities(() -> "finance_d6_read")))
                .andExpect(status().isForbidden());
        mockMvc.perform(patch("/api/admin/finance/payout-vnd/config")
                        .with(user("reader").authorities(() -> "finance_d7_read"))
                        .header("Idempotency-Key", "d7-reader-denied")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody(false)))
                .andExpect(status().isForbidden());
        mockMvc.perform(patch("/api/admin/finance/payout-vnd/channel")
                        .with(user("manager").authorities(() -> "finance_d7_manage"))
                        .header("Idempotency-Key", "d7-manager-channel-denied")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(channelBody()))
                .andExpect(status().isForbidden());
    }

    @Test
    void dedicatedReadManageForceAndChannelAuthoritiesAreEnforcedByHttp() throws Exception {
        mockMvc.perform(get("/api/admin/finance/payout-vnd/config")
                        .with(user("reader").authorities(() -> "finance_d7_read")))
                .andExpect(status().isOk());
        mockMvc.perform(patch("/api/admin/finance/payout-vnd/config")
                        .with(user("manager").authorities(() -> "finance_d7_manage"))
                        .header("Idempotency-Key", "d7-manage-ok")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody(false)))
                .andExpect(status().isOk());
        mockMvc.perform(patch("/api/admin/finance/payout-vnd/config")
                        .with(user("manager").authorities(() -> "finance_d7_manage"))
                        .header("Idempotency-Key", "d7-force-denied")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody(true)))
                .andExpect(status().isForbidden());
        mockMvc.perform(patch("/api/admin/finance/payout-vnd/config")
                        .with(user("risk-lead").authorities(
                                () -> "finance_d7_manage", () -> "finance_d7_force_inverted"))
                        .header("Idempotency-Key", "d7-force-ok")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody(true)))
                .andExpect(status().isOk());
        mockMvc.perform(patch("/api/admin/finance/payout-vnd/channel")
                        .with(user("channel-owner").authorities(() -> "finance_d7_channel_toggle"))
                        .header("Idempotency-Key", "d7-channel-ok")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(channelBody()))
                .andExpect(status().isOk());
    }

    @Test
    void replayedRejectedCommandWritesOneAuditForTheSameIdempotencyKey() throws Exception {
        when(service.update(any())).thenReturn(ApiResult.fail(409, "D7_CONFIG_VERSION_CONFLICT"));
        for (int attempt = 0; attempt < 2; attempt++) {
            mockMvc.perform(patch("/api/admin/finance/payout-vnd/config")
                            .with(user("manager").authorities(() -> "finance_d7_manage"))
                            .header("Idempotency-Key", "d7-rejected-replay")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(updateBody(false)))
                    .andExpect(status().isOk());
        }
        verify(service, times(1)).update(any());
        verify(audit, times(1)).recordRequiredInNewTransaction(any());
    }

    private String updateBody(boolean forceInverted) {
        return """
                {"sellSpreadPct":1.5,"quoteTtlMinWithdraw":9,"requoteTolerancePct":2,
                 "feeRatePct":1,"feeMinUsd":1,"feeMaxUsd":25,"minAmountUsd":20,
                 "maxAmountUsd":5000,"expectedVersion":1,"reason":"D7 security contract test",
                 "forceInverted":%s}
                """.formatted(forceInverted);
    }

    private String channelBody() {
        return """
                {"enabled":false,"expectedVersion":1,"reason":"D7 channel security contract test"}
                """;
    }
}
