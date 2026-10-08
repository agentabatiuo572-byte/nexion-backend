package ffdd.opsconsole.team.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import ffdd.opsconsole.platform.application.A2ReplayContext;
import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.team.mapper.AppTeamInsightsMapper;
import ffdd.opsconsole.team.mapper.LeaderboardPauseSnapshotMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;

/** In-memory persistence adapter, real production service and MyBatis SQL builder; no external DB. */
class LeaderboardPauseSnapshotServiceTest {
    private final ObjectMapper json = new ObjectMapper();
    private final LeaderboardPauseSnapshotMapper mapper = mock(LeaderboardPauseSnapshotMapper.class);
    private final PlatformConfigFacade config = mock(PlatformConfigFacade.class);
    private final AtomicReference<String> paused = new AtomicReference<>("off");
    private final AtomicReference<String> pointer = new AtomicReference<>("");
    private final Map<String, String> durable = new LinkedHashMap<>();
    private final LeaderboardPauseSnapshotService service = new LeaderboardPauseSnapshotService(mapper, config, json);

    @AfterEach void clearReplay() { A2ReplayContext.exitReplay(); }

    private void persistence() {
        when(mapper.insert(anyString(), anyString())).thenAnswer(invocation -> {
            String key = invocation.getArgument(0);
            if (durable.putIfAbsent(key, invocation.getArgument(1)) != null) throw new IllegalStateException("unique key");
            return 1;
        });
        doAnswer(invocation -> { pointer.set(invocation.getArgument(1)); return null; })
                .when(config).upsertAdminValue(anyString(), anyString(), anyString(), anyString(), anyString());
        when(mapper.current()).thenAnswer(invocation -> {
            String key = pointer.get().isBlank() ? null : json.readTree(pointer.get()).path("key").asText();
            return new LeaderboardPauseSnapshotMapper.CurrentRow(paused.get(), pointer.get(),
                    durable.containsKey(key) ? key : null, durable.get(key));
        });
    }

    private LeaderboardPauseSnapshotMapper.CaptureRow row(String kind, Map<String, Object> data) throws Exception {
        return new LeaderboardPauseSnapshotMapper.CaptureRow(kind, json.writeValueAsString(data));
    }

    private List<LeaderboardPauseSnapshotMapper.CaptureRow> facts(String minimum) throws Exception {
        List<LeaderboardPauseSnapshotMapper.CaptureRow> result = new ArrayList<>();
        result.add(row("config", Map.of("minUsd", minimum, "weekPoolUsd", "$120.50",
                "periodPrize", "{\"today\":10,\"week\":20,\"month\":30,\"allTime\":40}")));
        result.add(row("pcSummary", Map.of("participantCount", 88, "fraudHitCount", 2,
                "poolUsd", new BigDecimal("900.00"), "periodStatus", "flagged")));
        result.add(row("pcPodium", Map.of("rank", 1, "memberUserId", 77, "userId", "U00000077",
                "gmvLabel", "$1000", "volumeUsd", new BigDecimal("1000"), "tip", "本期 GV", "className", "r-1")));
        result.add(row("pcPodium", Map.of("rank", 2, "memberUserId", 78, "userId", "U00000078",
                "gmvLabel", "$10", "volumeUsd", new BigDecimal("10"), "tip", "本期 GV", "className", "r-2")));
        for (String period : List.of("today", "week", "month", "all")) {
            int count = "today".equals(period) ? 20 : 21;
            for (int rank = 1; rank <= count; rank++) {
                result.add(row(period, Map.of("rank", rank, "userId", rank + 6, "nickname", "Fixture" + rank,
                        "vRank", "V5", "earnedUsdt", new BigDecimal(100 - rank), "directs", 1, "teamSize", 3, "hasDevice", 1)));
            }
        }
        return result;
    }

    private LeaderboardPauseSnapshotService.State capture(String minimum) throws Exception {
        persistence();
        when(mapper.capture(anyMap())).thenReturn(facts(minimum));
        A2ReplayContext.enterReplay("fixture-durable-pause-1");
        var state = service.capture();
        paused.set("on"); // Test adapter transition after the capture; never touches business configuration.
        return state;
    }

    @ParameterizedTest @ValueSource(strings = {"today", "week", "month", "all"})
    void newInstancesReadSameDurablePeriodRowsAndRejectOtherCursorThenResumeLive(String period) throws Exception {
        var captured = capture("0");
        var restartedSnapshots = new LeaderboardPauseSnapshotService(mapper, config, new ObjectMapper());
        var appMapper = mock(AppTeamInsightsMapper.class);
        when(appMapper.userScope(7L)).thenReturn(new AppTeamInsightsMapper.UserScope(0, "V5"));
        var restartedApp = new AppTeamInsightsService(appMapper, mock(LeadershipPoolConfigGuard.class), config, new MockEnvironment(), null);
        ReflectionTestUtils.setField(restartedApp, "pauseSnapshots", restartedSnapshots);
        var first = restartedApp.leaderboard(7L, period, 1, 20, null, null).getData();
        assertThat(first).containsEntry("snapshotState", "FROZEN").containsEntry("dataAvailable", true)
                .containsEntry("pauseSnapshotId", captured.id()).containsEntry("snapshotVersion", captured.version())
                .containsEntry("snapshotAt", captured.capturedAt());
        assertThat((List<?>) first.get("rows")).hasSize(20);
        var second = restartedApp.leaderboard(7L, period, 2, 20, captured.capturedAt(), captured.version()).getData();
        assertThat((List<?>) second.get("rows")).hasSize("today".equals(period) ? 0 : 1);
        assertThat(second).containsEntry("snapshotVersion", captured.version()).containsEntry("paused", true);
        assertThatThrownBy(() -> restartedApp.leaderboard(7L, period, 2, 20, captured.capturedAt(), "b".repeat(64)))
                .isInstanceOf(BizException.class).hasMessage("TEAM_LEADERBOARD_SNAPSHOT_STALE");
        assertThat(service.pcSummary(restartedSnapshots.current())).containsEntry("poolUsd", new BigDecimal("9E+2"))
                .containsEntry("participantCount", 88);
        assertThat(service.pcPodium(restartedSnapshots.current())).hasSize(2);
        verify(appMapper, never()).leaderboardEligible(anyString(), any(), any(), any(), any(), anyInt(), any());
        service.resume(); paused.set("off");
        assertThat(restartedSnapshots.current().mode()).isEqualTo("LIVE");
        assertThat(durable).hasSize(1); // Resume does not mutate or delete the historical envelope.
        assertThatThrownBy(() -> restartedApp.leaderboard(7L, period, 2, 20, captured.capturedAt(), captured.version()))
                .hasMessage("TEAM_LEADERBOARD_SNAPSHOT_STALE");
        verify(mapper, times(1)).capture(anyMap());
        verify(mapper, times(1)).insert(anyString(), anyString());
    }

    @Test void pcAndAppKeepTheirExistingMinimumParsingAndDistinctAmounts() throws Exception {
        var captured = capture("$90"); // PC invalid plain decimal => zero; App strips currency => 90.
        assertThat(service.pcPodium(captured)).hasSize(2);
        assertThat(captured.appRows("week", json)).hasSize(10);
        assertThat(captured.envelope().path("appPeriods").path("week").path("poolUsd").decimalValue())
                .isEqualByComparingTo("120.50");
        assertThat(service.pcSummary(captured).get("poolUsd")).isEqualTo(new BigDecimal("9E+2"));
        assertThat(captured.appRows("week", json).get(9).rank()).isEqualTo(10);
    }

    @Test void jsonObjectReorderingAndNumericNormalizationDoNotInvalidateHashButChangedFactDoes() throws Exception {
        var captured = capture("0");
        ObjectNode envelope = (ObjectNode) json.readTree(durable.get(captured.id()));
        ObjectNode reordered = json.createObjectNode();
        List<String> names = new ArrayList<>(); envelope.fieldNames().forEachRemaining(names::add);
        java.util.Collections.reverse(names);
        for (String name : names) reordered.set(name, envelope.get(name));
        ((ObjectNode) reordered.path("pcProjection").path("summary")).put("poolUsd", new BigDecimal("900.0000"));
        durable.put(captured.id(), json.writeValueAsString(reordered));
        assertThat(service.current().mode()).isEqualTo("FROZEN");
        ((ObjectNode) reordered.path("pcProjection").path("summary")).put("poolUsd", new BigDecimal("901"));
        durable.put(captured.id(), json.writeValueAsString(reordered));
        assertThat(service.current().mode()).isEqualTo("UNAVAILABLE");
        assertThat(service.current().reason()).isEqualTo("SNAPSHOT_INVALID");
        verify(mapper, times(1)).insert(anyString(), anyString());
    }

    @Test void legacyMissingAndUnsupportedSnapshotsNeverReadLiveFactsOrWrite() throws Exception {
        persistence(); paused.set("on");
        assertThat(service.current().reason()).isEqualTo("PRE_SNAPSHOT_PAUSE");
        pointer.set("{\"key\":\"missing\",\"hash\":\"" + "a".repeat(64) + "\",\"schemaVersion\":1}");
        assertThat(service.current().reason()).isEqualTo("SNAPSHOT_MISSING");
        durable.put("missing", "{\"schemaVersion\":2}");
        assertThat(service.current().reason()).isEqualTo("SNAPSHOT_SCHEMA_UNSUPPORTED");
        pointer.set("bad-json");
        // The production SELECT handles invalid refs; this adapter presents that exact result.
        doReturn(new LeaderboardPauseSnapshotMapper.CurrentRow("on", "bad-json", null, null)).when(mapper).current();
        assertThat(service.current().reason()).isEqualTo("SNAPSHOT_INVALID");
        verify(mapper, never()).capture(anyMap()); verify(mapper, never()).insert(anyString(), anyString());
        verifyNoInteractions(config);
    }

    @Test void noA2AndIncompleteCaptureFailBeforePersistence() {
        assertThatThrownBy(service::capture).hasMessage("A2_CONFIRMATION_REQUIRED");
        verifyNoInteractions(mapper, config);
        A2ReplayContext.enterReplay("fixture-failed-capture");
        when(mapper.capture(anyMap())).thenReturn(List.of());
        assertThatThrownBy(service::capture).hasMessage("F4_LEADERBOARD_SNAPSHOT_CAPTURE_FAILED");
        verify(mapper, never()).insert(anyString(), anyString()); verifyNoInteractions(config);
    }

    @Test void malformedCaptureFactsFailBeforeSnapshotAndPointerPersistence() throws Exception {
        A2ReplayContext.enterReplay("fixture-invalid-capture");
        for (String field : List.of("poolUsd", "participantCount", "fraudHitCount", "periodStatus")) {
            var broken = new ArrayList<>(facts("0"));
            ObjectNode summary = (ObjectNode) json.readTree(broken.get(1).payload());
            summary.remove(field);
            broken.set(1, new LeaderboardPauseSnapshotMapper.CaptureRow("pcSummary", json.writeValueAsString(summary)));
            when(mapper.capture(anyMap())).thenReturn(broken);
            assertThatThrownBy(service::capture).hasMessage("F4_LEADERBOARD_SNAPSHOT_CAPTURE_FAILED");
        }
        verify(mapper, never()).insert(anyString(), anyString()); verifyNoInteractions(config);
    }

    @Test void textSchemaVersionIsNotTheNumericSchemaContract() throws Exception {
        var captured = capture("0");
        ObjectNode ref = (ObjectNode) json.readTree(pointer.get());
        ref.put("schemaVersion", "1"); pointer.set(json.writeValueAsString(ref));
        assertThat(service.current().reason()).isEqualTo("SNAPSHOT_SCHEMA_UNSUPPORTED");
    }

    @Test void actualMybatisBuildsSingleConsistentSelectWithSeparatePeriodBindings() {
        Configuration configuration = new Configuration();
        configuration.addMapper(LeaderboardPauseSnapshotMapper.class);
        Map<String, Object> p = LeaderboardPauseSnapshotService.captureParameters(Instant.parse("2026-10-08T12:00:00Z"));
        var statement = configuration.getMappedStatement(LeaderboardPauseSnapshotMapper.class.getName() + ".capture");
        var bound = statement.getBoundSql(p);
        List<String> bindings = bound.getParameterMappings().stream().map(item -> item.getProperty()).toList();
        assertThat(bindings).contains("todayActionPeriod", "weekActionPeriod", "monthActionPeriod", "allActionPeriod",
                "todayFromInclusive", "weekFromInclusive", "monthFromInclusive", "pcLimit");
        assertThat(bindings).doesNotContain("allFromInclusive", "allToExclusive", "fromInclusive", "toExclusive", "actionPeriod", "limit");
        assertThat(bound.getSql()).startsWith("SELECT 'config'").contains("UNION ALL", "WITH earned", "UPPER(ce.status)='UNLOCKED'")
                .doesNotContain("<if", "#{", ";", "FOR UPDATE");
        assertThat(p).containsEntry("todayLimit", 20).containsEntry("weekLimit", 50)
                .containsEntry("monthLimit", 100).containsEntry("allLimit", 100).containsEntry("sandbox", 0);
    }
}
