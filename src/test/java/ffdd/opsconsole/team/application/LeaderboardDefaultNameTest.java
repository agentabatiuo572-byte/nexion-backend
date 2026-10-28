package ffdd.opsconsole.team.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import ffdd.opsconsole.team.mapper.AppTeamInsightsMapper;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.env.MockEnvironment;

class LeaderboardDefaultNameTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void liveAndDurableFrozenRowsNormalizeOnlyVerifiedDefaultsBeforeMasking(boolean paused) throws Exception {
        var mapper = mock(AppTeamInsightsMapper.class);
        var config = mock(PlatformConfigFacade.class);
        when(config.activeValue(anyString())).thenReturn(Optional.empty());
        when(mapper.userScope(7L)).thenReturn(new AppTeamInsightsMapper.UserScope(0, "V5"));
        List<String> names = Arrays.asList("NexGrid 1058", "Nexion 3778", "NexGrid1058", "NexGrid 1058 ",
                " NexGrid 1058", "NEXGRID 1058", "NexGrid 1059", "NexGrid 1058 custom", null, "", "Al",
                "NexGrid 1058", "NexGrid 1058", "NexGrid 1058");
        List<AppTeamInsightsMapper.LeaderboardRow> source = new ArrayList<>();
        List<AppTeamInsightsMapper.LeaderboardNameIdentity> identities = new ArrayList<>();
        for (int i = 0; i < names.size(); i++) {
            source.add(new AppTeamInsightsMapper.LeaderboardRow(i + 1, 7L + i, names.get(i), "V5",
                    new BigDecimal("131.89"), 0, 3, 1));
            if (i != 13) identities.add(new AppTeamInsightsMapper.LeaderboardNameIdentity(7L + i,
                    i == 11 ? "abcd" : i == 12 ? null : "1058"));
        }
        when(mapper.leaderboardNameIdentities(anyList(), eq(0))).thenReturn(identities);
        when(mapper.leaderboardEligible(eq("week"), eq(0), any(), any(), any(), anyInt(), any())).thenReturn(source);
        var snapshots = mock(LeaderboardPauseSnapshotService.class);
        var json = new ObjectMapper();
        var envelope = json.createObjectNode();
        envelope.putObject("appPeriods").putObject("week").put("poolUsd", 1).set("rows", json.valueToTree(source));
        String frozenJson = envelope.toString();
        String at = "2026-10-17T08:00:00Z";
        String version = "a".repeat(64);
        when(snapshots.current()).thenReturn(paused
                ? new LeaderboardPauseSnapshotService.State(true, "FROZEN", "", "fixture", at, version, envelope)
                : LeaderboardPauseSnapshotService.State.live());
        var service = new AppTeamInsightsService(mapper, mock(LeadershipPoolConfigGuard.class), config,
                new MockEnvironment(), snapshots);

        var data = service.leaderboard(7L, "week", 1, 100, at, null).getData();
        @SuppressWarnings("unchecked") var rows = (List<Map<String, Object>>) data.get("rows");
        assertThat(rows).extracting(row -> row.get("handle")).containsExactly("UVE***", "Nex***", "Nex***",
                "Nex***", "Nex***", "UVT***", "Nex***", "Nex***", "Use***", "Use***", "Al",
                "Nex***", "Nex***", "Nex***");
        assertThat(rows).allSatisfy(row -> {
            assertThat(row).containsEntry("earnedUSDT", new BigDecimal("131.89"))
                    .containsEntry("directs", 0).containsEntry("teamSize", 3).containsEntry("hasDevice", true);
            assertThat(row).doesNotContainKeys("phone", "phoneSuffix", "nickname", "userId");
        });
        assertThat(data).containsEntry("totalRows", names.size()).containsEntry("myRank", 1)
                .containsEntry("snapshotAt", at).containsEntry("snapshotState", paused ? "FROZEN" : "LIVE");
        assertThat(source).extracting(AppTeamInsightsMapper.LeaderboardRow::nickname).containsExactlyElementsOf(names);
        assertThat(envelope.toString()).isEqualTo(frozenJson);
        verify(mapper).leaderboardNameIdentities(List.of(7L, 13L, 18L, 19L, 20L), 0);
        var nextPage = service.leaderboard(7L, "week", 2, 1, at, (String) data.get("snapshotVersion")).getData();
        assertThat(nextPage).containsEntry("snapshotVersion", data.get("snapshotVersion"))
                .containsEntry("totalRows", names.size()).containsEntry("myRank", 1);
        assertThat(nextPage.get("rows")).isEqualTo(List.of(rows.get(1)));
        if (paused) verify(mapper, never()).leaderboardEligible(anyString(), anyInt(), any(), any(), any(), anyInt(), any());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void emptyOrCustomOnlyBoardDoesNotQueryIdentity(boolean empty) {
        var mapper = mock(AppTeamInsightsMapper.class);
        var config = mock(PlatformConfigFacade.class);
        when(config.activeValue(anyString())).thenReturn(Optional.empty());
        when(mapper.userScope(7L)).thenReturn(new AppTeamInsightsMapper.UserScope(0, "V5"));
        when(mapper.leaderboardEligible(eq("week"), eq(0), any(), any(), any(), anyInt(), any()))
                .thenReturn(empty ? List.of() : List.of(new AppTeamInsightsMapper.LeaderboardRow(
                        1, 7L, "Nexion 3778", "V5", BigDecimal.ONE, 8, 9, 1)));
        var data = new AppTeamInsightsService(mapper, mock(LeadershipPoolConfigGuard.class), config,
                new MockEnvironment(), null).leaderboard(7L, "week").getData();
        assertThat(data).containsEntry("totalRows", empty ? 0 : 1);
        verify(mapper, never()).leaderboardNameIdentities(anyList(), anyInt());
    }

    @Test
    void identityLookupBindsOnlyRequestedUsersAndNamespaceWithoutSelectingFullPhoneOrNickname() {
        var configuration = new Configuration();
        configuration.addMapper(AppTeamInsightsMapper.class);
        var query = configuration.getMappedStatement(AppTeamInsightsMapper.class.getName() + ".leaderboardNameIdentities")
                .getBoundSql(Map.of("userIds", List.of(7L, 8L), "sandbox", 0));
        assertThat(query.getSql().replaceAll("\\s+", " ")).contains("SELECT id userId,RIGHT(phone,4) phoneSuffix",
                "sandbox=? AND is_deleted=0 AND id IN", "( ? , ? )").doesNotContain("nickname", "status=", "UPDATE");
        assertThat(query.getParameterMappings()).hasSize(3);
    }
}
