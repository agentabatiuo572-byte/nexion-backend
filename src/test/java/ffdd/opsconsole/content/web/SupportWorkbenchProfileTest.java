package ffdd.opsconsole.content.web;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import ffdd.opsconsole.content.application.*;
import ffdd.opsconsole.content.mapper.SupportAcceptanceSandboxMapper;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.env.MockEnvironment;

class SupportWorkbenchProfileTest {
    @ParameterizedTest @ValueSource(strings={"test","invalid","dev,prod"})
    void forbiddenProfilesCannotReadOrAdvanceCanonicalWatermark(String profiles) {
        var environment=new MockEnvironment();environment.setActiveProfiles(profiles.split(","));
        var workbench=mock(SupportWorkbenchService.class);var maintenance=mock(SupportMaintenanceService.class);
        var controller=new SupportWorkbenchController(workbench,maintenance,
                new ProductionSupportPathGuard(environment,mock(SupportAcceptanceSandboxMapper.class)),mock(SupportCustomerProfileService.class));
        assertThatThrownBy(()->controller.snapshot(null,"ALL",null,1,20,null,null)).hasMessage("SUPPORT_PRODUCTION_PATH_FORBIDDEN");
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
        controller.snapshot(null,"ALL",null,1,20,null,null);controller.detail(1L);controller.history(1L,1,20);
        verify(workbench).snapshot(null,"ALL",null,1,20,null,null);verify(workbench).detail(1L);
        verify(maintenance).history(1L,1,20);
    }
}
