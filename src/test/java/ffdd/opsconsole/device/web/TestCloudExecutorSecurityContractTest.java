package ffdd.opsconsole.device.web;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import ffdd.opsconsole.device.application.*;
import ffdd.opsconsole.device.mapper.*;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.exception.GlobalExceptionHandler;
import ffdd.opsconsole.emergency.web.GeoBlockEnforcementFilter;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.*;
import org.springframework.mock.web.MockServletContext;
import org.springframework.core.env.*;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

class TestCloudExecutorSecurityContractTest {
    static final String BASE="/api/test/cloud-executors/v1",TOKEN="ct1_"+Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]);
    AnnotationConfigWebApplicationContext context;
    MockMvc mvc;
    TestCloudExecutorService service;
    @Configuration @EnableWebSecurity @EnableWebMvc
    @Import({TestCloudExecutorSecurityConfig.class,TestCloudExecutorController.class,GlobalExceptionHandler.class})
    static class Config {
        @Bean ObjectMapper json() { return new ObjectMapper(); }
        @Bean AuditLogService audit() { return mock(AuditLogService.class); }
        @Bean TestCloudExecutorService service(Environment environment,AuditLogService audit) {
            return spy(new TestCloudExecutorService(mock(AppTaskAssignmentMapper.class),mock(TestCloudExecutorMapper.class),
                    mock(AppTaskAssignmentService.class),audit,Clock.systemUTC(),environment));
        }
    }
    @BeforeEach void start() {
        context=new AnnotationConfigWebApplicationContext(); context.setServletContext(new MockServletContext());
        context.getEnvironment().setActiveProfiles("dev");
        Map<String,Object> properties=new HashMap<>();
        properties.put("server.forward-headers-strategy","none");
        Map.ofEntries(Map.entry("enabled","true"),Map.entry("deployment-scope","TEST"),Map.entry("credential-sha256",TestCloudExecutorService.sha256(new byte[32])),
                Map.entry("owner-id","7"),Map.entry("device-id","11"),Map.entry("instance-no","NEX-UNIT-CLOUD"),
                Map.entry("executor-id","cloud-unit"),Map.entry("task-config-id","EM-UNIT"),Map.entry("model","bge-m3:567m"),
                Map.entry("model-digest","sha256:7907646426070047a77226ac3e684fbbe8410524f7b4a74d02837e43f2146bab"))
                .forEach((key,value)->properties.put(TestCloudExecutorService.PREFIX+key,value));
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("cloud-unit",properties));
        context.register(Config.class); context.refresh();
        service=context.getBean(TestCloudExecutorService.class);
        doReturn(ApiResult.ok(Map.of("idle",true))).when(service).next(any(),any());
        mvc=MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }
    @AfterEach void close() { context.close(); SecurityContextHolder.clearContext(); }
    @Test void onlyDedicatedCredentialCanReachExactNamespace() throws Exception {
        for(String token:List.of("Bearer user-jwt","Bearer admin-jwt","Bearer tc1_"+TOKEN.substring(4),"Bearer tw1_"+TOKEN.substring(4))) {
            assertThat(mvc.perform(post(BASE+"/next-task").header("Authorization",token).content("{}")).andReturn().getResponse().getStatus()).isEqualTo(401);
        }
        assertThat(mvc.perform(post(BASE+"/next-task").header("Authorization","Bearer "+TOKEN)
                .header("Idempotency-Key","next-cloud-unit").content("{}")).andReturn().getResponse().getStatus()).isEqualTo(200);
        verify(service,times(1)).next(any(),eq("next-cloud-unit"));
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }
    @Test void forwardedSpoofRemotePeerAndEnabledProfileAreRejectedBeforeBusinessMethod() throws Exception {
        for(String header:List.of("Forwarded","X-Forwarded-For","X-Forwarded-Host","X-Real-IP")) {
            assertThat(mvc.perform(post(BASE+"/next-task").header("Authorization","Bearer "+TOKEN).header(header,"127.0.0.1")
                    .content("{}")).andReturn().getResponse().getStatus()).isEqualTo(403);
        }
        assertThat(mvc.perform(post(BASE+"/next-task").with(req->{req.setRemoteAddr("203.0.113.5");return req;})
                .header("Authorization","Bearer "+TOKEN).content("{}")).andReturn().getResponse().getStatus()).isEqualTo(403);
        context.getEnvironment().setActiveProfiles("prod");
        assertThat(mvc.perform(post(BASE+"/next-task").header("Authorization","Bearer "+TOKEN).content("{}")).andReturn().getResponse().getStatus()).isEqualTo(503);
        verify(service,never()).next(any(),any());
    }
    @Test void duplicateUnknownTrailingAndOversizeBodyCannotReachBusinessMethod() throws Exception {
        for(String body:List.of("{\"ownerId\":7}","{} {}","{\"a\":1,\"a\":2}","[]")) {
            assertThat(mvc.perform(post(BASE+"/next-task").header("Authorization","Bearer "+TOKEN).content(body))
                    .andReturn().getResponse().getStatus()).isEqualTo(400);
        }
        assertThat(mvc.perform(post(BASE+"/next-task").header("Authorization","Bearer "+TOKEN).content(" ".repeat(20001)))
                .andReturn().getResponse().getStatus()).isEqualTo(413);
        assertThat(mvc.perform(get(BASE+"/next-task").header("Authorization","Bearer "+TOKEN)).andReturn().getResponse().getStatus()).isEqualTo(403);
        verify(service,never()).next(any(),any());
    }
    @Test void exactCloudRoutesAreEnforcedBeforeForwardedHeaderRewriting() {
        var request=new org.springframework.mock.web.MockHttpServletRequest("POST",BASE+"/next-task");
        request.setRemoteAddr("127.0.0.1"); assertThat(GeoBlockEnforcementFilter.isTestCloudRoute(request)).isTrue();
        assertThat(GeoBlockEnforcementFilter.isDirectLoopback(request)).isTrue();
        request.addHeader("Forwarded","for=127.0.0.1"); assertThat(GeoBlockEnforcementFilter.isDirectLoopback(request)).isFalse();
        request.setRequestURI(BASE+"/next-task/"); assertThat(GeoBlockEnforcementFilter.isTestCloudRoute(request)).isFalse();
        request.setMethod("GET"); request.setRequestURI(BASE+"/instances");
        assertThat(GeoBlockEnforcementFilter.isTestCloudRoute(request)).isTrue();
        request.setRequestURI(BASE+"/instances/"); assertThat(GeoBlockEnforcementFilter.isTestCloudRoute(request)).isFalse();
    }
    @Test void fleetDiscoveryHasItsOwnScopeAndRetainsTheDedicatedLoopbackCredentialBoundary() throws Exception {
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("fleet",Map.of(
                TestCloudExecutorService.PREFIX+"fleet-enabled","true",
                TestCloudExecutorService.PREFIX+"fleet-started-at","2026-10-30T00:00:00")));
        doReturn(ApiResult.ok(Map.of("instances",List.of(),"hasMore",false,"nextAfterDeviceId",0L)))
                .when(service).instances(any(),eq(0L),eq(100));
        assertThat(mvc.perform(get(BASE+"/instances").header("Authorization","Bearer "+TOKEN))
                .andReturn().getResponse().getStatus()).isEqualTo(200);
        assertThat(mvc.perform(get(BASE+"/instances").header("Authorization","Bearer "+TOKEN)
                .header("X-UVEL-Executor-Id","fleet-999")).andReturn().getResponse().getStatus()).isEqualTo(401);
        assertThat(mvc.perform(get(BASE+"/instances").header("Authorization","Bearer "+TOKEN)
                .header("Forwarded","for=127.0.0.1")).andReturn().getResponse().getStatus()).isEqualTo(403);
        verify(service,times(1)).instances(any(),eq(0L),eq(100));
    }
}
