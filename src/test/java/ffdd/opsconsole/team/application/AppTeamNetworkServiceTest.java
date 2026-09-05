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
        when(mapper.membersPage(7L, 0L, 501)).thenReturn(List.of(new AppTeamNetworkMapper.MemberRow(
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
        when(mapper.membersPage(7L, 0L, 501)).thenReturn(List.of());
        MockEnvironment environment = developmentEnvironment();

        var result = new AppTeamNetworkService(mapper, environment).snapshot(7L);

        assertThat(result.getCode()).isZero();
        assertThat(result.getData()).containsEntry("sourceEnvironment", "PRODUCTION")
                .containsEntry("runId", "").containsEntry("serverCanonical", true);
        verify(mapper).membersPage(7L, 0L, 501);
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
    void returnsEmptyPageForZeroMembers() {
        AppTeamNetworkMapper mapper = mock(AppTeamNetworkMapper.class);
        when(mapper.userScope(7L)).thenReturn(new AppTeamNetworkMapper.UserScope(0));
        when(mapper.membersPage(7L, 0L, 501)).thenReturn(List.of());

        var result = new AppTeamNetworkService(mapper, new MockEnvironment()).snapshot(7L, 0L);

        assertThat(result.getData()).containsEntry("totalMembers", 0).containsEntry("nextCursor", null);
    }

    @Test
    void returnsSingleFiveHundredMemberPageWithoutCursor() {
        AppTeamNetworkMapper mapper = mock(AppTeamNetworkMapper.class);
        when(mapper.userScope(7L)).thenReturn(new AppTeamNetworkMapper.UserScope(0));
        when(mapper.membersPage(7L, 0L, 501)).thenReturn(rows(1, 500));

        var result = new AppTeamNetworkService(mapper, new MockEnvironment()).snapshot(7L, 0L);

        assertThat(result.getData()).containsEntry("totalMembers", 500).containsEntry("nextCursor", null);
    }

    @Test
    void pagesFiveHundredAndOneMembersInsteadOfFailingClosed() {
        AppTeamNetworkMapper mapper = mock(AppTeamNetworkMapper.class);
        when(mapper.userScope(7L)).thenReturn(new AppTeamNetworkMapper.UserScope(0));
        when(mapper.membersPage(7L, 0L, 501)).thenReturn(rows(1, 501));
        when(mapper.membersPage(7L, 500L, 501)).thenReturn(rows(501, 1));
        AppTeamNetworkService service = new AppTeamNetworkService(mapper, new MockEnvironment());

        var first = service.snapshot(7L, 0L);
        var second = service.snapshot(7L, 500L);

        assertThat(first.getData()).containsEntry("totalMembers", 500).containsEntry("nextCursor", "500");
        assertThat(second.getData()).containsEntry("totalMembers", 1).containsEntry("nextCursor", null);
    }

    @Test
    void producesThreeCursorPagesForOneThousandAndOneMembers() {
        AppTeamNetworkMapper mapper = mock(AppTeamNetworkMapper.class);
        when(mapper.userScope(7L)).thenReturn(new AppTeamNetworkMapper.UserScope(0));
        when(mapper.membersPage(7L, 0L, 501)).thenReturn(rows(1, 501));
        when(mapper.membersPage(7L, 500L, 501)).thenReturn(rows(501, 501));
        when(mapper.membersPage(7L, 1000L, 501)).thenReturn(rows(1001, 1));
        AppTeamNetworkService service = new AppTeamNetworkService(mapper, new MockEnvironment());

        assertThat(service.snapshot(7L, 0L).getData()).containsEntry("nextCursor", "500");
        assertThat(service.snapshot(7L, 500L).getData()).containsEntry("nextCursor", "1000");
        assertThat(service.snapshot(7L, 1000L).getData()).containsEntry("totalMembers", 1).containsEntry("nextCursor", null);
    }

    @Test
    void rejectsNegativeCursor() {
        AppTeamNetworkMapper mapper = mock(AppTeamNetworkMapper.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> new AppTeamNetworkService(mapper, new MockEnvironment()).snapshot(7L, -1L))
                .hasMessage("TEAM_NETWORK_CURSOR_INVALID");
    }

    private List<AppTeamNetworkMapper.MemberRow> rows(int startInclusive, int count) {
        return IntStream.range(startInclusive, startInclusive + count).mapToObj(index ->
                new AppTeamNetworkMapper.MemberRow((long) index, "Member " + index, null, "V1", 1,
                        index % 2 == 0 ? "A" : "B", 7L, LocalDateTime.of(2026, 8, 13, 0, 0),
                        BigDecimal.ZERO, null, "ACTIVE", "SG")).toList();
    }

    private MockEnvironment developmentEnvironment() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("dev");
        return environment;
    }
}
