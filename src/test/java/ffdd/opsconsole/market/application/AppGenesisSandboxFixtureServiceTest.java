package ffdd.opsconsole.market.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import ffdd.opsconsole.market.mapper.AppMarketSandboxMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class AppGenesisSandboxFixtureServiceTest {
    @Test
    void acceptsOnlyMatchingRunAndSandboxUsers() {
        MockEnvironment env = new MockEnvironment().withProperty("NEXION_ACCEPTANCE_RUN_ID", "genesis-fixture-run");
        env.setActiveProfiles("dev");
        AppMarketSandboxMapper mapper = mock(AppMarketSandboxMapper.class);
        when(mapper.userSandbox(7L)).thenReturn(1);
        when(mapper.userSandbox(8L)).thenReturn(1);
        AppGenesisSandboxFixtureService service = new AppGenesisSandboxFixtureService(mapper, env);

        service.replace("genesis-fixture-run", 7L, List.of(
                new AppGenesisSandboxFixtureService.HolderSpec(7L, 2),
                new AppGenesisSandboxFixtureService.HolderSpec(8L, 2)));

        assertThat(service.holdings("genesis-fixture-run", 7L)).hasSize(2);
        assertThat(service.priorityRank("genesis-fixture-run", 7L)).isEqualTo(1);
        assertThat(service.activeHolderCount("genesis-fixture-run")).isEqualTo(2);
        verify(mapper).userSandbox(8L);
    }

    @Test
    void rejectsProductionAndMismatchedRunBeforeWriting() {
        MockEnvironment env = new MockEnvironment().withProperty("NEXION_ACCEPTANCE_RUN_ID", "genesis-fixture-run");
        env.setActiveProfiles("prod");
        AppMarketSandboxMapper mapper = mock(AppMarketSandboxMapper.class);
        AppGenesisSandboxFixtureService service = new AppGenesisSandboxFixtureService(mapper, env);

        assertThatThrownBy(() -> service.replace("other-run", 7L, List.of(
                new AppGenesisSandboxFixtureService.HolderSpec(7L, 1))))
                .hasMessage("GENESIS_SANDBOX_FIXTURE_SCOPE_INVALID");
        verifyNoInteractions(mapper);
    }

    @Test
    void rejectsUnknownProfilesEvenWhenRunMatches() {
        MockEnvironment env = new MockEnvironment().withProperty("NEXION_ACCEPTANCE_RUN_ID", "genesis-fixture-run");
        env.setActiveProfiles("staging");
        AppMarketSandboxMapper mapper = mock(AppMarketSandboxMapper.class);
        AppGenesisSandboxFixtureService service = new AppGenesisSandboxFixtureService(mapper, env);

        assertThatThrownBy(() -> service.replace("genesis-fixture-run", 7L, List.of(
                new AppGenesisSandboxFixtureService.HolderSpec(7L, 1))))
                .hasMessage("GENESIS_SANDBOX_FIXTURE_SCOPE_INVALID");
        verifyNoInteractions(mapper);
    }
}
