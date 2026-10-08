package ffdd.opsconsole.content.web;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import ffdd.opsconsole.content.application.*;
import ffdd.opsconsole.content.mapper.SupportAcceptanceSandboxMapper;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.env.MockEnvironment;

class SupportWorkbenchProfileTest {
    @org.junit.jupiter.api.Test
    void explicitModesReachTheFactoryAndUnknownModesAreBadRequests() throws Exception {
        var workbench=mock(SupportWorkbenchService.class);
        var controller=new SupportWorkbenchController(workbench,mock(SupportMaintenanceService.class),
                mock(ProductionSupportPathGuard.class),mock(SupportCustomerProfileService.class));
        var mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(controller).build();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/admin/content/support-workbench/overview")
                .param("mode","MANAGED").param("groupId","10").param("agentId","2"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
        verify(workbench).snapshot(ffdd.opsconsole.content.domain.SupportGroupFacts.ReadMode.MANAGED,10L,2L,"ALL",null,1,20,null,null);
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/admin/content/support-workbench/overview")
                .param("mode","unknown"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest());
        verifyNoMoreInteractions(workbench);
    }
    @ParameterizedTest @ValueSource(strings={"test","invalid","dev,prod"})
    void forbiddenProfilesCannotReadOrAdvanceCanonicalWatermark(String profiles) {
        var environment=new MockEnvironment();environment.setActiveProfiles(profiles.split(","));
        var workbench=mock(SupportWorkbenchService.class);var maintenance=mock(SupportMaintenanceService.class);
        var controller=new SupportWorkbenchController(workbench,maintenance,
                new ProductionSupportPathGuard(environment,mock(SupportAcceptanceSandboxMapper.class)),mock(SupportCustomerProfileService.class));
        assertThatThrownBy(()->controller.snapshot(null,"ALL",null,1,20,null,null,null,null)).hasMessage("SUPPORT_PRODUCTION_PATH_FORBIDDEN");
        assertThatThrownBy(()->controller.detail(1L)).hasMessage("SUPPORT_PRODUCTION_PATH_FORBIDDEN");
        assertThatThrownBy(()->controller.history(1L,1,20)).hasMessage("SUPPORT_PRODUCTION_PATH_FORBIDDEN");
        verifyNoInteractions(workbench,maintenance);
    }
    @ParameterizedTest @ValueSource(strings={"dev","prod"})
    void canonicalProfilesKeepAllThreeReadRoutesAvailable(String profile) {
        var environment=new MockEnvironment();environment.setActiveProfiles(profile);
        var workbench=mock(SupportWorkbenchService.class);var maintenance=mock(SupportMaintenanceService.class);
        var controller=new SupportWorkbenchController(workbench,maintenance,
                new ProductionSupportPathGuard(environment,mock(SupportAcceptanceSandboxMapper.class)),mock(SupportCustomerProfileService.class));
        controller.snapshot(null,"ALL",null,1,20,null,null,null,null);controller.detail(1L);controller.history(1L,1,20);
        verify(workbench).snapshot(null,null,null,"ALL",null,1,20,null,null);verify(workbench).detail(1L);
        verify(maintenance).history(1L,1,20);
    }
}
