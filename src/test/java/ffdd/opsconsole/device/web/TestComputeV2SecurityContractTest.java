package ffdd.opsconsole.device.web;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import ffdd.opsconsole.device.application.*;
import ffdd.opsconsole.emergency.web.GeoBlockEnforcementFilter;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.exception.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.*;
import org.springframework.core.env.MapPropertySource;
import org.springframework.mock.web.*;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

class TestComputeV2SecurityContractTest {
    static final String ROOT="/api/test/cloud-executors/v2",TOKEN="Bearer local-test-executor",TASK="CV2-"+"A".repeat(32);
    static final TestCloudExecutorService.Identity IDENTITY=new TestCloudExecutorService.Identity("fleet",0,0,"fleet","EM","bge-m3:567m","sha256:"+"b".repeat(64));
    AnnotationConfigWebApplicationContext context;MockMvc mvc;
    @Configuration @EnableWebSecurity @EnableWebMvc
    @Import({TestCloudExecutorSecurityConfig.class,TestComputeV2Controller.class,GlobalExceptionHandler.class})
    static class Config {
        @Bean ObjectMapper json(){return new ObjectMapper();}
        @Bean AuditLogService audit(){return mock(AuditLogService.class);}
        @Bean TestComputeV2Service v2(){var service=mock(TestComputeV2Service.class);when(service.next(IDENTITY,"runner-1")).thenReturn(Map.of("state","IDLE"));return service;}
        @Bean TestCloudExecutorService cloud(){var service=mock(TestCloudExecutorService.class);
            when(service.authenticate(any(),any())).thenAnswer(call->{if(TOKEN.equals(call.getArgument(0))&&"fleet".equals(call.getArgument(1)))return IDENTITY;throw new BizException(401,"TEST_CLOUD_AUTH_INVALID");});return service;}
    }
    @BeforeEach void setup(){context=new AnnotationConfigWebApplicationContext();context.setServletContext(new MockServletContext());
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("v2",Map.of("nexion.compute-v2.enabled","true")));
        context.register(Config.class);context.refresh();mvc=MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();}
    @AfterEach void close(){context.close();}
    @Test void exactLoopbackWorkerCredentialCanClaimButUserTokensAndProxyHeadersCannot()throws Exception{
        assertThat(mvc.perform(post(ROOT+"/next-task").header("Authorization",TOKEN).header("X-UVEL-Executor-Id","fleet").content("{\"runner\":\"runner-1\"}")).andReturn().getResponse().getStatus()).isEqualTo(200);
        for(String token:List.of("Bearer user-jwt","Bearer admin-jwt"))assertThat(mvc.perform(post(ROOT+"/next-task").header("Authorization",token).header("X-UVEL-Executor-Id","fleet").content("{\"runner\":\"runner-1\"}")).andReturn().getResponse().getStatus()).isEqualTo(401);
        assertThat(mvc.perform(post(ROOT+"/next-task").header("Authorization",TOKEN).header("X-UVEL-Executor-Id","fleet").header("Forwarded","for=127.0.0.1").content("{\"runner\":\"runner-1\"}")).andReturn().getResponse().getStatus()).isEqualTo(403);
        assertThat(mvc.perform(post(ROOT+"/next-task").header("Authorization",TOKEN).header("X-UVEL-Executor-Id","fleet").with(req->{req.setRemoteAddr("203.0.113.8");return req;}).content("{\"runner\":\"runner-1\"}")).andReturn().getResponse().getStatus()).isEqualTo(403);
    }
    @Test void wrongVerbAndUnlistedV2NamespaceRemainDenied()throws Exception{
        for(String path:List.of(ROOT+"/next-task",ROOT+"/tasks/"+TASK+"/exited",ROOT+"/tasks/"+TASK+"/observed-exit",ROOT+"/unknown"))
            assertThat(mvc.perform(get(path).header("Authorization",TOKEN).header("X-UVEL-Executor-Id","fleet")).andReturn().getResponse().getStatus()).isEqualTo(403);
    }
    @Test void geoBoundaryRecognizesOnlyExactMachineMethodsBeforeForwardingCanStripHeaders(){
        for(String path:List.of(ROOT+"/next-task",ROOT+"/tasks/"+TASK+"/heartbeat",ROOT+"/tasks/"+TASK+"/complete",ROOT+"/tasks/"+TASK+"/observed-exit")){
            var raw=new MockHttpServletRequest("POST",path);assertThat(GeoBlockEnforcementFilter.isTestCloudRoute(raw)).isTrue();
            raw.setMethod("GET");assertThat(GeoBlockEnforcementFilter.isTestCloudRoute(raw)).isFalse();
        }
        assertThat(GeoBlockEnforcementFilter.isTestCloudRoute(new MockHttpServletRequest("GET",ROOT+"/tasks/"+TASK+"/receipt"))).isTrue();
        assertThat(GeoBlockEnforcementFilter.isTestCloudRoute(new MockHttpServletRequest("POST",ROOT+"/tasks/CTA-old/exited"))).isFalse();
    }
}
