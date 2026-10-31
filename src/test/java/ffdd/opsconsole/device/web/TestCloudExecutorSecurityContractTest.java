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
                    mock(AppTaskAssignmentService.class),audit,Clock.systemUTC(),environment,TestCloudRequestTestSupport.passthrough()));
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
        doReturn(ApiResult.ok(Map.of("idle",true))).when(service).next(any(),any(),anyString(),nullable(String.class),nullable(Long.class));
        mvc=MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }
    @AfterEach void close() { context.close(); SecurityContextHolder.clearContext(); }
    @Test void onlyDedicatedCredentialCanReachExactNamespace() throws Exception {
        for(String token:List.of("Bearer user-jwt","Bearer admin-jwt","Bearer tc1_"+TOKEN.substring(4),"Bearer tw1_"+TOKEN.substring(4))) {
            assertThat(mvc.perform(post(BASE+"/next-task").header("Authorization",token).content("{}")).andReturn().getResponse().getStatus()).isEqualTo(401);
        }
        assertThat(mvc.perform(post(BASE+"/next-task").header("Authorization","Bearer "+TOKEN)
                .header("Idempotency-Key","next-cloud-unit").content("{}")).andReturn().getResponse().getStatus()).isEqualTo(200);
        verify(service,times(1)).next(any(),eq("next-cloud-unit"),eq(TestCloudExecutorService.sha256("{}".getBytes(java.nio.charset.StandardCharsets.UTF_8))),isNull(),isNull());
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }
    @Test void singleEligibilityRetainsTheExactDedicatedLoopbackGetBoundary() throws Exception {
        String path=BASE+"/eligibility";
        doReturn(ApiResult.ok(Map.of("eligible",false))).when(service).eligibility(any());
        assertThat(mvc.perform(get(path).header("Authorization","Bearer "+TOKEN)).andReturn().getResponse().getStatus()).isEqualTo(200);
        for(String token:List.of("user-jwt","admin-jwt"))
            assertThat(mvc.perform(get(path).header("Authorization","Bearer "+token)).andReturn().getResponse().getStatus()).isEqualTo(401);
        assertThat(mvc.perform(get(path).header("Authorization","Bearer "+TOKEN).header("Forwarded","for=127.0.0.1"))
                .andReturn().getResponse().getStatus()).isEqualTo(403);
        assertThat(mvc.perform(get(path).header("Authorization","Bearer "+TOKEN).with(req->{req.setRemoteAddr("203.0.113.5");return req;}))
                .andReturn().getResponse().getStatus()).isEqualTo(403);
        assertThat(mvc.perform(post(path).header("Authorization","Bearer "+TOKEN)).andReturn().getResponse().getStatus()).isEqualTo(403);
        assertThat(mvc.perform(get(path+"/").header("Authorization","Bearer "+TOKEN)).andReturn().getResponse().getStatus()).isEqualTo(403);
        verify(service,times(1)).eligibility(any());
        var raw=new org.springframework.mock.web.MockHttpServletRequest("GET",path);
        assertThat(GeoBlockEnforcementFilter.isTestCloudRoute(raw)).isTrue();
        raw.setMethod("POST"); assertThat(GeoBlockEnforcementFilter.isTestCloudRoute(raw)).isFalse();
    }
    @Test void fleetEligibilityIsCanonicalReadOnlyAcrossCurrentTaskInactiveAndForeignBinding() throws Exception {
        var requests=(TestCloudRequests)org.springframework.test.util.ReflectionTestUtils.getField(service,"requests");
        doAnswer(call->((java.util.function.Supplier<?>)call.getArgument(0)).get()).when(requests).outside(any());
        doAnswer(call->((java.util.function.Supplier<?>)call.getArgument(0)).get()).when(requests).db(any());
        var mapper=(TestCloudExecutorMapper)org.springframework.test.util.ReflectionTestUtils.getField(service,"mapper");
        var assignments=(AppTaskAssignmentMapper)org.springframework.test.util.ReflectionTestUtils.getField(service,"assignments");
        var now=java.time.LocalDateTime.of(2026,10,31,0,0);
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("fleet",Map.of(
                TestCloudExecutorService.PREFIX+"fleet-enabled","true",
                TestCloudExecutorService.PREFIX+"fleet-started-at",now.minusDays(1).toString())));
        var binding=new TestCloudExecutorMapper.Binding("fleet-12",7,12,"NEX-FLEET-12","EM-UNIT","bge-m3:567m",
                context.getEnvironment().getProperty(TestCloudExecutorService.PREFIX+"model-digest"));
        when(mapper.binding("fleet-12")).thenReturn(binding); when(mapper.lockBinding("fleet-12")).thenReturn(binding);
        when(assignments.lockProductionUser(7L)).thenReturn(7L);
        when(assignments.lockOwnedDevice(7L,12L)).thenReturn(new AppTaskAssignmentMapper.DeviceRow(12L,"NEX-FLEET-12","DEVICE","S1","S1",
                "ACTIVE","stellarbox-s1",now.minusDays(1),now.minusHours(1),96,"unit","ONLINE",null,false));
        when(mapper.lockActiveTaskNos(7,12)).thenReturn(List.of("CTA-FLEET-CURRENT"));
        when(mapper.liveFleetBinding(now.minusDays(1),null,7,12,"NEX-FLEET-12",false,false)).thenReturn(1);
        var path=BASE+"/eligibility";
        var response=mvc.perform(get(path).header("Authorization","Bearer "+TOKEN).header("X-UVEL-Executor-Id","fleet-12"))
                .andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(200);
        var data=new ObjectMapper().readTree(response.getContentAsString()).path("data");
        assertThat(data.path("eligible").booleanValue()).isTrue(); assertThat(data.path("activeTaskNo").asText()).isEqualTo("CTA-FLEET-CURRENT");
        assertThat(data.path("ownerId").longValue()).isEqualTo(7); assertThat(data.path("deviceId").longValue()).isEqualTo(12);
        when(mapper.liveFleetBinding(now.minusDays(1),null,7,12,"NEX-FLEET-12",false,false)).thenReturn(null);
        response=mvc.perform(get(path).header("Authorization","Bearer "+TOKEN).header("X-UVEL-Executor-Id","fleet-12")).andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(200);
        data=new ObjectMapper().readTree(response.getContentAsString()).path("data");
        assertThat(data.path("eligible").booleanValue()).isFalse(); assertThat(data.path("activeTaskNo").asText()).isEqualTo("CTA-FLEET-CURRENT");
        when(assignments.lockOwnedDevice(7L,12L)).thenReturn(new AppTaskAssignmentMapper.DeviceRow(12L,"NEX-FLEET-12","DEVICE","S1","S1",
                "DEACTIVATED","stellarbox-s1",now.minusDays(1),now.minusHours(1),96,"unit","OFFLINE","USER_DEACTIVATED",false));
        when(mapper.lockActiveTaskNos(7,12)).thenReturn(List.of());
        response=mvc.perform(get(path).header("Authorization","Bearer "+TOKEN).header("X-UVEL-Executor-Id","fleet-12")).andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(200);
        data=new ObjectMapper().readTree(response.getContentAsString()).path("data");
        assertThat(data.path("eligible").booleanValue()).isFalse(); assertThat(data.path("activeTaskNo").isNull()).isTrue();
        when(assignments.lockOwnedDevice(7L,12L)).thenReturn(new AppTaskAssignmentMapper.DeviceRow(12L,"FOREIGN-INSTANCE","DEVICE","S1","S1",
                "ACTIVE","stellarbox-s1",now.minusDays(1),now.minusHours(1),96,"unit","ONLINE",null,false));
        assertThat(mvc.perform(get(path).header("Authorization","Bearer "+TOKEN).header("X-UVEL-Executor-Id","fleet-12"))
                .andReturn().getResponse().getStatus()).isEqualTo(403);
        when(assignments.lockOwnedDevice(7L,12L)).thenReturn(null);
        assertThat(mvc.perform(get(path).header("Authorization","Bearer "+TOKEN).header("X-UVEL-Executor-Id","fleet-12"))
                .andReturn().getResponse().getStatus()).isEqualTo(403);
        assertThat(mvc.perform(get(path).header("Authorization","Bearer "+TOKEN).header("X-UVEL-Executor-Id","fleet"))
                .andReturn().getResponse().getStatus()).isEqualTo(403);
        verify(mapper,never()).register(any(),any()); verify(mapper,never()).insert(any(TestCloudExecutorMapper.Execution.class));
        verify(mapper,never()).heartbeat(anyLong(),any(),any(),any());
        verify(assignments,never()).clearRuntimeTask(anyLong(),anyLong(),anyString(),any());
        verify(assignments,never()).lockPaidCloudShareDailyNex(7L,12L);
        verify(mapper,never()).saveResult(any(),any(),any(),any(),any());
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
        verify(service,never()).next(any(),any(),anyString(),nullable(String.class),nullable(Long.class));
    }
    @Test void duplicateUnknownTrailingAndOversizeBodyCannotReachBusinessMethod() throws Exception {
        for(String body:List.of("{\"ownerId\":7}","{} {}","{\"a\":1,\"a\":2}","[]")) {
            assertThat(mvc.perform(post(BASE+"/next-task").header("Authorization","Bearer "+TOKEN).content(body))
                    .andReturn().getResponse().getStatus()).isEqualTo(400);
        }
        assertThat(mvc.perform(post(BASE+"/next-task").header("Authorization","Bearer "+TOKEN).content(" ".repeat(20001)))
                .andReturn().getResponse().getStatus()).isEqualTo(413);
        assertThat(mvc.perform(get(BASE+"/next-task").header("Authorization","Bearer "+TOKEN)).andReturn().getResponse().getStatus()).isEqualTo(403);
        verify(service,never()).next(any(),any(),anyString(),nullable(String.class),nullable(Long.class));
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
        request.setRequestURI(BASE+"/tasks/CTA-ONE/manifest"); assertThat(GeoBlockEnforcementFilter.isTestCloudRoute(request)).isTrue();
        request.setRequestURI(BASE+"/tasks/CTA-ONE/manifest/"); assertThat(GeoBlockEnforcementFilter.isTestCloudRoute(request)).isFalse();
    }
    @Test void originalManifestIsAnExactLocalDedicatedReadAndRequiresItsOriginalKey() throws Exception {
        String path=BASE+"/tasks/CTA-ONE/manifest",key="BGE-NEXT-"+"d".repeat(32);
        doReturn(ApiResult.ok(Map.of("resumeAllowed",true))).when(service).manifest(any(),eq("CTA-ONE"),eq(key));
        assertThat(mvc.perform(get(path).header("Authorization","Bearer "+TOKEN).header("Idempotency-Key",key))
                .andReturn().getResponse().getStatus()).isEqualTo(200);
        assertThat(mvc.perform(get(path).header("Authorization","Bearer "+TOKEN))
                .andReturn().getResponse().getStatus()).isEqualTo(422);
        for(String token:List.of("user-jwt","admin-jwt"))
            assertThat(mvc.perform(get(path).header("Authorization","Bearer "+token).header("Idempotency-Key",key))
                    .andReturn().getResponse().getStatus()).isEqualTo(401);
        assertThat(mvc.perform(get(path).header("Authorization","Bearer "+TOKEN).header("Idempotency-Key",key)
                .with(req->{req.setRemoteAddr("203.0.113.5");return req;})).andReturn().getResponse().getStatus()).isEqualTo(403);
        assertThat(mvc.perform(get(path).header("Authorization","Bearer "+TOKEN).header("Idempotency-Key",key)
                .header("Forwarded","for=127.0.0.1")).andReturn().getResponse().getStatus()).isEqualTo(403);
        assertThat(mvc.perform(post(path).header("Authorization","Bearer "+TOKEN).header("Idempotency-Key",key))
                .andReturn().getResponse().getStatus()).isEqualTo(403);
        verify(service,times(1)).manifest(any(),eq("CTA-ONE"),eq(key));
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

    @Test void reviewedRollbackIsOnlyAnExactDedicatedLoopbackGet() throws Exception {
        String path=BASE+"/tasks/CTA-ONE/rollback-review",next="BGE-NEXT-"+"d".repeat(32),complete="BGE-COMPLETE-"+"e".repeat(64);
        doReturn(ApiResult.ok(Map.of("rollbackConfirmed",true))).when(service).rollbackReview(any(),eq("CTA-ONE"),eq(complete),eq(next),any());
        var request=get(path).header("Authorization","Bearer "+TOKEN).header("Idempotency-Key",complete)
                .header("X-UVEL-Original-Next-Key",next).param("originalRequestHash","a".repeat(64)).param("resultHash","b".repeat(64));
        assertThat(mvc.perform(request).andReturn().getResponse().getStatus()).isEqualTo(200);
        for(String token:List.of("user-jwt","admin-jwt"))
            assertThat(mvc.perform(get(path).header("Authorization","Bearer "+token)).andReturn().getResponse().getStatus()).isEqualTo(401);
        assertThat(mvc.perform(get(path).header("Authorization","Bearer "+TOKEN).header("Forwarded","for=127.0.0.1"))
                .andReturn().getResponse().getStatus()).isEqualTo(403);
        assertThat(mvc.perform(get(path).header("Authorization","Bearer "+TOKEN).with(req->{req.setRemoteAddr("203.0.113.5");return req;}))
                .andReturn().getResponse().getStatus()).isEqualTo(403);
        assertThat(mvc.perform(post(path).header("Authorization","Bearer "+TOKEN)).andReturn().getResponse().getStatus()).isEqualTo(403);
        assertThat(mvc.perform(get(path+"/").header("Authorization","Bearer "+TOKEN)).andReturn().getResponse().getStatus()).isEqualTo(403);
        verify(service,times(1)).rollbackReview(any(),any(),any(),any(),any());
        var raw=new org.springframework.mock.web.MockHttpServletRequest("GET",path);
        assertThat(GeoBlockEnforcementFilter.isTestCloudRoute(raw)).isTrue();
        raw.setRequestURI(path+"/"); assertThat(GeoBlockEnforcementFilter.isTestCloudRoute(raw)).isFalse();
    }
    @Test void requestStatusAndStabilityReviewRetainExactLoopbackReadBoundary() throws Exception {
        for(String suffix:List.of("/requests/status","/tasks/CTA-ONE/stability-review")) {
            String path=BASE+suffix;
            var raw=new org.springframework.mock.web.MockHttpServletRequest("GET",path);
            assertThat(GeoBlockEnforcementFilter.isTestCloudRoute(raw)).isTrue();
            assertThat(mvc.perform(get(path).header("Authorization","Bearer "+TOKEN).header("Forwarded","for=127.0.0.1"))
                    .andReturn().getResponse().getStatus()).isEqualTo(403);
            assertThat(mvc.perform(get(path).header("Authorization","Bearer "+TOKEN).with(req->{req.setRemoteAddr("203.0.113.5");return req;}))
                    .andReturn().getResponse().getStatus()).isEqualTo(403);
            assertThat(mvc.perform(post(path).header("Authorization","Bearer "+TOKEN)).andReturn().getResponse().getStatus()).isEqualTo(403);
            raw.setRequestURI(path+"/"); assertThat(GeoBlockEnforcementFilter.isTestCloudRoute(raw)).isFalse();
        }
        String path=BASE+"/requests/status",hash="a".repeat(64);
        doReturn(ApiResult.ok(Map.of("state","NOT_FOUND"))).when(service).requestStatus(any(),eq("COMPLETE"),eq("original-complete-key"),eq(hash),eq("CTA-ONE"));
        assertThat(mvc.perform(get(path).header("Authorization","Bearer "+TOKEN).header("Idempotency-Key","original-complete-key")
                .param("operation","COMPLETE").param("requestHash",hash).param("taskNo","CTA-ONE"))
                .andReturn().getResponse().getStatus()).isEqualTo(200);
        verify(service,times(1)).requestStatus(any(),anyString(),anyString(),anyString(),anyString());
    }
    @Test void controllerBindsTheExactWireBytesRatherThanReserializedJson() throws Exception {
        String raw="{ }\n";
        assertThat(mvc.perform(post(BASE+"/next-task").header("Authorization","Bearer "+TOKEN).header("Idempotency-Key","exact-wire-bytes")
                .content(raw)).andReturn().getResponse().getStatus()).isEqualTo(200);
        verify(service).next(any(),eq("exact-wire-bytes"),eq(TestCloudExecutorService.sha256(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8))),isNull(),isNull());
    }
}
