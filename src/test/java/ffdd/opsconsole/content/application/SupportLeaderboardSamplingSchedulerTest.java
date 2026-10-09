package ffdd.opsconsole.content.application;

import ffdd.opsconsole.content.mapper.SupportAcceptanceSandboxMapper;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.scheduling.annotation.Scheduled;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SupportLeaderboardSamplingSchedulerTest {
    @Test void schedulerIsDedicatedBoundedAndDoesNotReplaceSharedTaskScheduler() throws Exception {
        Scheduled schedule=SupportLeaderboardSamplingScheduler.class.getMethod("poll").getAnnotation(Scheduled.class);
        assertEquals("supportLeaderboardTaskScheduler",schedule.scheduler());assertEquals(30_000,schedule.fixedDelay());assertEquals(30_000,schedule.initialDelay());
        var configuration=new SupportLeaderboardSamplingSchedulerConfiguration();var scheduler=configuration.supportLeaderboardTaskScheduler();
        assertEquals(1,scheduler.getPoolSize());assertEquals("support-leaderboard-",scheduler.getThreadNamePrefix());
        Bean bean=configuration.getClass().getDeclaredMethod("supportLeaderboardTaskScheduler").getAnnotation(Bean.class);
        assertArrayEquals(new String[]{"supportLeaderboardTaskScheduler"},bean.name());}
    @Test void actualRuntimeGuardAllowsOnlyCanonicalProfiles(){for(String profile:new String[]{"dev","prod",""}){
        MockEnvironment environment=new MockEnvironment();if(!profile.isEmpty())environment.setActiveProfiles(profile);
        assertTrue(new ProductionSupportPathGuard(environment,mock(SupportAcceptanceSandboxMapper.class)).productionSupportAutomationAllowed());}
        for(String[] profiles:new String[][]{{"test"},{"unknown"},{"dev","prod"}}){MockEnvironment environment=new MockEnvironment();environment.setActiveProfiles(profiles);
            assertFalse(new ProductionSupportPathGuard(environment,mock(SupportAcceptanceSandboxMapper.class)).productionSupportAutomationAllowed());}}
    @Test void pollDelegatesWithoutInstallingAnyPrincipal(){var service=mock(SupportLeaderboardSamplingService.class);
        when(service.sample()).thenReturn(new SupportLeaderboardSamplingService.Result("IDLE",8,8,0,0,0,0,0,0));
        new SupportLeaderboardSamplingScheduler(service).poll();verify(service).sample();}
}
