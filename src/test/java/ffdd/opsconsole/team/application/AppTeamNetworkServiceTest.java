package ffdd.opsconsole.team.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.team.mapper.AppTeamNetworkMapper;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class AppTeamNetworkServiceTest {
    @Test
    void returnsServerOwnedTotalsFromMembers() {
        AppTeamNetworkMapper mapper = mock(AppTeamNetworkMapper.class);
        when(mapper.userScope(7L)).thenReturn(new AppTeamNetworkMapper.UserScope(0));
        when(mapper.members(7L)).thenReturn(List.of(new AppTeamNetworkMapper.MemberRow(
                42L, "Member 42", null, "V1", 1, "A", 7L, LocalDateTime.of(2026, 8, 13, 0, 0),
                new BigDecimal("12.5"), new BigDecimal("20"), "ACTIVE", "SG")));
        var result = new AppTeamNetworkService(mapper, new MockEnvironment()).snapshot(7L);
        assertThat(result.getCode()).isZero();
        assertThat(result.getData()).containsEntry("totalMembers", 1).containsEntry("directMembers", 1)
                .containsEntry("source", "server").containsEntry("sourceEnvironment", "PRODUCTION")
                .containsEntry("runId", "").containsEntry("serverCanonical", true)
                .containsEntry("lifetimeVolumeUsdt", null);
        assertThat(result.getData().get("members").toString()).doesNotContain("sponsorId");
    }

    @Test
    void developmentAllowsAnyActiveDevelopmentAccountAndReturnsCanonicalDatabaseFacts() {
        AppTeamNetworkMapper mapper = mock(AppTeamNetworkMapper.class);
        when(mapper.userScope(7L)).thenReturn(new AppTeamNetworkMapper.UserScope(0));
        when(mapper.members(7L)).thenReturn(List.of());
        MockEnvironment environment = developmentEnvironment();

        var result = new AppTeamNetworkService(mapper, environment).snapshot(7L);

        assertThat(result.getCode()).isZero();
        assertThat(result.getData()).containsEntry("sourceEnvironment", "PRODUCTION")
                .containsEntry("runId", "").containsEntry("serverCanonical", true);
        verify(mapper).members(7L);
    }

    @Test
    void sandboxReturnsRunScopedServerOwnedFacts() {
        AppTeamNetworkMapper mapper = mock(AppTeamNetworkMapper.class);
        when(mapper.userScope(7L)).thenReturn(new AppTeamNetworkMapper.UserScope(1));
        MockEnvironment environment = new MockEnvironment().withProperty("NEXION_ACCEPTANCE_RUN_ID", "TEAM-RUN-20260816");
        environment.setActiveProfiles("test");

        var result = new AppTeamNetworkService(mapper, environment).snapshot(7L);
        assertThat(result.getCode()).isZero();
        assertThat(result.getData()).containsEntry("source", "server")
                .containsEntry("sourceEnvironment", "SANDBOX")
                .containsEntry("runId", "TEAM-RUN-20260816")
                .containsEntry("serverCanonical", true);
        assertThat(result.getData().get("members")).asList().isNotEmpty();
        verify(mapper).userScope(7L);
        verifyNoMoreInteractions(mapper);
    }

    @Test
    void sandboxRequiresRunIdBeforeGeneratingFacts() {
        AppTeamNetworkMapper mapper = mock(AppTeamNetworkMapper.class);
        when(mapper.userScope(7L)).thenReturn(new AppTeamNetworkMapper.UserScope(1));
        MockEnvironment environment = new MockEnvironment().withProperty("NEXION_ACCEPTANCE_RUN_ID", "short");
        environment.setActiveProfiles("test");

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new AppTeamNetworkService(mapper, environment).snapshot(7L))
                .hasMessage("TEAM_RUN_ID_REQUIRED");
    }

    @Test
    void sandboxFactsAreFencedByAccountAndRunId() {
        AppTeamNetworkMapper mapper = mock(AppTeamNetworkMapper.class);
        when(mapper.userScope(7L)).thenReturn(new AppTeamNetworkMapper.UserScope(1));
        when(mapper.userScope(8L)).thenReturn(new AppTeamNetworkMapper.UserScope(1));
        MockEnvironment environment = new MockEnvironment().withProperty("NEXION_ACCEPTANCE_RUN_ID", "TEAM-RUN-20260816");
        environment.setActiveProfiles("test");
        AppTeamNetworkService service = new AppTeamNetworkService(mapper, environment);

        Object accountA = service.snapshot(7L).getData().get("members").toString();
        environment.setProperty("NEXION_ACCEPTANCE_RUN_ID", "TEAM-RUN-20260817");
        Object accountARerun = service.snapshot(7L).getData().get("members").toString();
        Object accountB = service.snapshot(8L).getData().get("members").toString();

        assertThat(accountARerun).isNotEqualTo(accountA);
        assertThat(accountB).isNotEqualTo(accountARerun);
    }

    @Test
    void failsClosedInsteadOfPublishingATruncatedFiveHundredMemberTotal() {
        AppTeamNetworkMapper mapper = mock(AppTeamNetworkMapper.class);
        when(mapper.userScope(7L)).thenReturn(new AppTeamNetworkMapper.UserScope(0));
        when(mapper.members(7L)).thenReturn(IntStream.rangeClosed(1, 501).mapToObj(index ->
                new AppTeamNetworkMapper.MemberRow((long) index, "Member " + index, null, "V1", 1,
                        index % 2 == 0 ? "A" : "B", 7L, LocalDateTime.of(2026, 8, 13, 0, 0),
                        BigDecimal.ZERO, null, "ACTIVE", "SG")).toList());

        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> new AppTeamNetworkService(mapper, new MockEnvironment()).snapshot(7L))
                .hasMessage("TEAM_NETWORK_MEMBER_LIMIT_EXCEEDED");
    }

    private MockEnvironment developmentEnvironment() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("dev");
        return environment;
    }
}
