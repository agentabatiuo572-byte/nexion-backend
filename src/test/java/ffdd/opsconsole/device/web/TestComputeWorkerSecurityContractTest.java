package ffdd.opsconsole.device.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import ffdd.opsconsole.device.application.AppTaskAssignmentService;
import ffdd.opsconsole.device.mapper.AppTaskAssignmentMapper;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.device.application.TestComputeWorkerService;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.exception.GlobalExceptionHandler;
import ffdd.opsconsole.user.infrastructure.UserEntity;
import ffdd.opsconsole.content.terms.LegalTermsService;
import ffdd.opsconsole.developer.application.AppDeveloperApiService;
import ffdd.opsconsole.developer.web.DeveloperApiKeyAuthenticationFilter;
import ffdd.opsconsole.shared.audit.AuditTraceFilter;
import ffdd.opsconsole.shared.security.*;
import ffdd.opsconsole.shared.security.mapper.AuthSessionMapper;
import ffdd.opsconsole.user.mapper.UserOpsMapper;
import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import ffdd.opsconsole.platform.mapper.AdminAccountStateMapper;
import java.net.URI;
import java.net.http.*;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Map;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;

/** Real embedded Servlet container: also catches duplicate global JWT-filter registration. */
class TestComputeWorkerSecurityContractTest {
    static final String TASK = "CTA-TEST-ONE";
    static final String TOKEN = "tw1_" + Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]);
    @Test
    void disabledNamespaceIs503BeforeAuthenticationOrAnyDatabaseWork() throws Exception {
        try (var context = start(false)) {
            clearInvocations(context.getBean(AppTaskAssignmentService.class));
            var response = request(context, "POST", "/api/test/compute-workers/v1/tasks/CTA-TEST-ONE/claim", null, "{}");
            assertThat(response.statusCode()).isEqualTo(503);
            assertThat(response.body()).contains("TEST_COMPUTE_WORKER_DISABLED");
            verifyNoInteractions(context.getBean(AppTaskAssignmentService.class), context.getBean(AppTaskAssignmentMapper.class));
        }
    }

    @Test
    void realContainerPreservesWorkerIdentityAndKeepsOrdinaryUserJwtInItsOriginalChain() throws Exception {
        try (var context = start(true)) {
            var assignments = context.getBean(AppTaskAssignmentService.class);
            clearInvocations(assignments);
            when(assignments.testWorkerClaim(any(), eq(TASK), any())).thenReturn(ApiResult.ok(Map.of("executionKind", TestComputeWorkerService.KIND)));
            var response = request(context, "POST", "/api/test/compute-workers/v1/tasks/" + TASK + "/claim", TOKEN, "{}");
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).contains(TestComputeWorkerService.KIND);
            var captor = org.mockito.ArgumentCaptor.forClass(TestComputeWorkerService.Grant.class);
            verify(assignments).testWorkerClaim(captor.capture(), eq(TASK), any());
            assertThat(captor.getValue().toString()).isEqualTo("test-worker:executor-one");
            assertThat(context.getServletContext().getFilterRegistrations().values())
                    .noneMatch(registration -> registration.getClassName().equals(JwtAuthenticationFilter.class.getName()));
            assertThat(request(context, "GET", "/api/fixture/who", TOKEN, "").statusCode()).isEqualTo(401);
            var oldUser = request(context, "GET", "/api/fixture/who", "fixture-user", "");
            assertThat(oldUser.statusCode()).isEqualTo(200);
            assertThat(oldUser.body()).contains("USER", "7");
            assertThat(request(context, "POST", "/api/fixture/write", "fixture-user", "{}").statusCode()).isEqualTo(428);
            assertThat(request(context, "POST", "/api/test/compute-workers/v1/tasks/" + TASK + "/claim", "fixture-user", "{}").statusCode()).isEqualTo(401);
            assertThat(request(context, "POST", "/api/test/compute-workers/v1/tasks/" + TASK + "/claim", null, "{}").statusCode()).isEqualTo(401);
            assertThat(request(context, "POST", "/api/test/compute-workers/v1/tasks/" + TASK + "/claim", TOKEN + "=", "{}").statusCode()).isEqualTo(401);
            assertThat(request(context, "GET", "/api/test/compute-workers/v1/tasks/" + TASK + "/claim", TOKEN, "").statusCode()).isEqualTo(403);
            assertThat(request(context, "POST", "/api/test/compute-workers/v1/other", TOKEN, "{}").statusCode()).isEqualTo(403);
            verifyNoInteractions(context.getBean(AppTaskAssignmentMapper.class));
        }
    }

    @Test
    void actualBodyReaderRejectsExtraDuplicateWrongTypeTrailingAndOversizeBytesBeforeService() throws Exception {
        try (var context = start(true)) {
            var service = context.getBean(AppTaskAssignmentService.class);
            clearInvocations(service);
            String route = "/api/test/compute-workers/v1/tasks/" + TASK;
            for (String invalid : new String[]{"[]", "null", "{\"ownerId\":7}", "{}{}", "{\"x\":1,\"x\":2}", "{broken"}) {
                assertThat(request(context, "POST", route + "/claim", TOKEN, invalid).statusCode()).as(invalid).isEqualTo(400);
            }
            String valid = "{\"specVersion\":\"UVEL_TEST_VECTOR_STATS_V1\",\"inputHash\":\"a\",\"resultHash\":\"b\",\"resultArtifactBase64\":\"c\",\"proofNonce\":\"d\",\"proofTimestamp\":1}";
            for (String invalid : new String[]{valid.replace("\"proofTimestamp\":1", "\"proofTimestamp\":\"1\""),
                    valid.replace("\"proofTimestamp\":1", "\"proofTimestamp\":1.0"), valid.replace("\"proofTimestamp\":1", "\"proofTimestamp\":9223372036854775808"),
                    valid.replace("\"proofTimestamp\":1", "\"proofTimestamp\":1,\"proofTimestamp\":2"), valid + "{}"}) {
                assertThat(request(context, "POST", route + "/complete", TOKEN, invalid).statusCode()).isEqualTo(400);
            }
            assertThat(request(context, "POST", route + "/complete", TOKEN, " ".repeat(8193)).statusCode()).isEqualTo(413);
            assertThat(request(context, "POST", route + "/claim", TOKEN, "{\"x\":\"\ud800\"}").statusCode()).isEqualTo(400);
            assertThat(request(context, "POST", route.replace(TASK, "CTA-ANOTHER") + "/claim", TOKEN, "{}").statusCode()).isEqualTo(403);
            verifyNoInteractions(service, context.getBean(AppTaskAssignmentMapper.class));
        }
    }

    static ServletWebServerApplicationContext start(boolean enabled) {
        var builder = new SpringApplicationBuilder(WireConfig.class).properties(
                "server.port=0", "server.address=127.0.0.1", "spring.main.banner-mode=off", "spring.profiles.active=dev",
                "spring.cloud.discovery.enabled=false", "spring.cloud.nacos.config.enabled=false",
                "spring.cloud.nacos.discovery.enabled=false", "spring.config.location=optional:classpath:/no-fixture-config.yml",
                "nexion.compute-task.test-worker.enabled=" + enabled,
                "nexion.compute-task.test-worker.deployment-scope=TEST",
                "nexion.compute-task.test-worker.executor-id=executor-one", "nexion.compute-task.test-worker.owner-id=7",
                "nexion.compute-task.test-worker.device-id=11", "nexion.compute-task.test-worker.instance-no=NEX-TEST-INSTANCE",
                "nexion.compute-task.test-worker.task-no=" + TASK, "nexion.compute-task.test-worker.task-config-id=TASK-EM",
                "nexion.compute-task.test-worker.run-id=TEST355-WIRE", "nexion.compute-task.test-worker.issued-at=1790942340000",
                "nexion.compute-task.test-worker.expires-at=1790943000000",
                "nexion.compute-task.test-worker.credential-sha256=" + TestComputeWorkerService.sha256(new byte[32]));
        // Same runnable test on the original baseline: absent TEST chain naturally returns the old 401.
        for (String name : new String[]{"ffdd.opsconsole.device.application.TestComputeWorkerService",
                "ffdd.opsconsole.device.web.TestComputeWorkerSecurityConfig", "ffdd.opsconsole.device.web.TestComputeWorkerController"}) {
            try { builder.sources(Class.forName(name)); } catch (ClassNotFoundException absentOnBaseline) { }
        }
        // Local embedded container only. The original prod startup guard correctly rejects missing deployment DB bundles.
        return (ServletWebServerApplicationContext) builder.run("--spring.profiles.active=dev");
    }

    static HttpResponse<String> request(ServletWebServerApplicationContext context, String method, String path,
                                        String bearer, String body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + context.getWebServer().getPort() + path))
                .header("Content-Type", "application/json")
                .header("Idempotency-Key", "TEST355-CLAIM-" + TestComputeWorkerService.sha256(TASK.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                .method(method, HttpRequest.BodyPublishers.ofString(body));
        if (bearer != null) request.header("Authorization", "Bearer " + bearer);
        return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration(exclude = {DataSourceAutoConfiguration.class, RedisAutoConfiguration.class, RedisRepositoriesAutoConfiguration.class, UserDetailsServiceAutoConfiguration.class})
    @Import({SecurityConfig.class, GlobalExceptionHandler.class, FixtureController.class, AuditTraceFilter.class})
    static class WireConfig {
        @Bean Clock clock() { return Clock.fixed(Instant.parse("2026-10-02T12:00:00Z"), ZoneOffset.UTC); }
        @Bean AppTaskAssignmentService assignments() { return mock(AppTaskAssignmentService.class); }
        @Bean AppTaskAssignmentMapper mapper() { return mock(AppTaskAssignmentMapper.class); }
        @Bean AuditLogService audit() { return mock(AuditLogService.class); }
        @Bean UserBusinessWriteGateFilter userWriteGate() { return new UserBusinessWriteGateFilter(mock(UserOpsMapper.class), mock(LegalTermsService.class)); }
        @Bean UserBlocklistEnforcementFilter userBlocklist() { return new UserBlocklistEnforcementFilter(userId -> false); }
        @Bean DeveloperApiKeyAuthenticationFilter developerKeys() { return new DeveloperApiKeyAuthenticationFilter(mock(AppDeveloperApiService.class)); }
        @Bean AdminRbacAuthorizationFilter rbac(AuditLogService audit) { return new AdminRbacAuthorizationFilter(audit, mock(AdminAccountStateMapper.class)); }
        @Bean JwtAuthenticationFilter jwt(Environment env) {
            var provider = mock(JwtTokenProvider.class);
            when(provider.parse("fixture-user")).thenReturn(Jwts.claims().subject("7").add("subjectType", "USER")
                    .add("sessionId", "fixture-session").add("authEnvironment", "PRODUCTION").build());
            var sessions = mock(AuthSessionMapper.class);
            when(sessions.touchActiveUserSession("fixture-session", 7L, 30)).thenReturn(1);
            var users = mock(UserOpsMapper.class); var user = new UserEntity();
            user.setId(7L); user.setSandbox(0); user.setCountryCode("84"); user.setPhone("912345678");
            when(users.selectById(7L)).thenReturn(user);
            return new JwtAuthenticationFilter(provider, sessions, users, env, new GatewaySecurityProperties(), mock(AdminSessionRegistry.class),
                    mock(AdminPermissionCache.class), mock(ImpersonationSessionVerifier.class), mock(PlatformConfigFacade.class));
        }
    }

    @org.springframework.web.bind.annotation.RestController
    static class FixtureController {
        @org.springframework.web.bind.annotation.GetMapping("/api/fixture/who")
        Map<String, Object> who(org.springframework.security.core.Authentication authentication) {
            return Map.of("name", authentication.getName(), "details", authentication.getDetails());
        }
        @org.springframework.web.bind.annotation.PostMapping("/api/fixture/write")
        Map<String, Object> write(org.springframework.security.core.Authentication authentication) { return who(authentication); }
    }
}
