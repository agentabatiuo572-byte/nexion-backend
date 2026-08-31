package ffdd.opsconsole.team.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ffdd.opsconsole.shared.api.ApiResultHttpStatusAdvice;
import ffdd.opsconsole.team.application.CommissionGuideRuleService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class AppCommissionGuideControllerTest {

    @Test
    void isExplicitlyPublicInTheApplicationSecurityRules() throws Exception {
        String securityConfig = Files.readString(Path.of("src/main/java/ffdd/opsconsole/shared/security/SecurityConfig.java"));

        assertThat(securityConfig).contains("/api/config/commission/rates\", \"/api/config/commission/guide");
    }

    @Test
    void returnsThePublicReadOnlyGuideAtTheStablePath() throws Exception {
        CommissionGuideRuleService service = mock(CommissionGuideRuleService.class);
        Map<String, Object> guide = new LinkedHashMap<>();
        guide.put("source", "server");
        guide.put("serverCanonical", true);
        guide.put("sourceEnvironment", "PRODUCTION");
        guide.put("runId", null);
        guide.put("coolingDays", null);
        Map<String, Object> network = new LinkedHashMap<>();
        network.put("depthGateLayer", null);
        network.put("depthGateRank", null);
        network.put("exitCapRate", null);
        guide.put("network", network);
        guide.put("binary", null);
        guide.put("leadership", null);
        guide.put("capabilities", Map.of("peer", false, "genesis", false));
        when(service.guide(org.mockito.ArgumentMatchers.any())).thenReturn(guide);

        mvc(service).perform(get("/api/config/commission/guide"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.source").value("server"))
                .andExpect(jsonPath("$.data.network").isMap())
                .andExpect(jsonPath("$.data.network.depthGateLayer").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.data.network.depthGateRank").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.data.network.exitCapRate").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.data.capabilities.peer").value(false));
    }

    @Test
    void mapsUnexpectedConfigurationInfrastructureFailureTo503Envelope() throws Exception {
        CommissionGuideRuleService service = mock(CommissionGuideRuleService.class);
        when(service.guide(org.mockito.ArgumentMatchers.any())).thenThrow(new IllegalStateException("database unavailable"));

        mvc(service).perform(get("/api/config/commission/guide"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value(503))
                .andExpect(jsonPath("$.message").value("COMMISSION_GUIDE_CONFIG_UNAVAILABLE"))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    private MockMvc mvc(CommissionGuideRuleService service) {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("prod");
        return MockMvcBuilders.standaloneSetup(new AppCommissionGuideController(service, environment))
                .setControllerAdvice(new ApiResultHttpStatusAdvice())
                .build();
    }
}
