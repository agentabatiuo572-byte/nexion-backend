package ffdd.opsconsole.content.application;

import ffdd.opsconsole.content.mapper.SupportAcceptanceSandboxMapper;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SupportLeaderboardSamplingSchedulerTest {
    @Test void actualRuntimeGuardAllowsOnlyCanonicalProfiles(){for(String profile:new String[]{"dev","prod",""}){
        MockEnvironment environment=new MockEnvironment();if(!profile.isEmpty())environment.setActiveProfiles(profile);
        assertTrue(new ProductionSupportPathGuard(environment,mock(SupportAcceptanceSandboxMapper.class)).productionSupportAutomationAllowed());}
        for(String[] profiles:new String[][]{{"test"},{"unknown"},{"dev","prod"}}){MockEnvironment environment=new MockEnvironment();environment.setActiveProfiles(profiles);
            assertFalse(new ProductionSupportPathGuard(environment,mock(SupportAcceptanceSandboxMapper.class)).productionSupportAutomationAllowed());}}
    @Test void pollDelegatesWithoutInstallingAnyPrincipal(){var service=mock(SupportLeaderboardSamplingService.class);
        when(service.sample()).thenReturn(new SupportLeaderboardSamplingService.Result("IDLE",8,8,0,0,0,0,0,0));
        new SupportLeaderboardSamplingScheduler(service).poll();verify(service).sample();}
}
