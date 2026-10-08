package ffdd.opsconsole.team.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import ffdd.opsconsole.platform.application.A2ReplayContext;
import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import ffdd.opsconsole.team.mapper.AppTeamInsightsMapper;
import ffdd.opsconsole.team.mapper.LeaderboardPauseSnapshotMapper;
import ffdd.opsconsole.shared.config.DateTimeFormatConfig;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;

/** Offline semantic probes only; no SQL, network, credentials or product changes. */
class LeaderboardPauseSnapshotAdversarialTest {
    private static final List<String> PERIODS = List.of("today", "week", "month", "all");
    private static class Fixture {
        final ObjectMapper json = new ObjectMapper();
        final LeaderboardPauseSnapshotMapper mapper = mock(LeaderboardPauseSnapshotMapper.class);
        final PlatformConfigFacade config = mock(PlatformConfigFacade.class);
        final LeaderboardPauseSnapshotService service = new LeaderboardPauseSnapshotService(mapper, config, json);
        final LeaderboardPauseSnapshotService.State captured;
        ObjectNode stored;
        String hash;
        Fixture() throws Exception {
            List<LeaderboardPauseSnapshotMapper.CaptureRow> facts = new ArrayList<>();
            facts.add(row("config", Map.of("minUsd", "0", "weekPoolUsd", "120", "periodPrize", "{\"today\":10,\"week\":20,\"month\":30,\"allTime\":40}")));
            facts.add(row("pcSummary", Map.of("participantCount", 88, "poolUsd", 900, "fraudHitCount", 0, "periodStatus", "active")));
            facts.add(row("pcPodium", Map.of("rank", 1, "memberUserId", 7, "userId", "U00000007", "gmvLabel", "$1000", "volumeUsd", 1000, "tip", "GV", "className", "r-1")));
            for (String period : PERIODS) facts.add(row(period, Map.of("rank", 1, "userId", 7, "nickname", "Fixture", "vRank", "V5", "earnedUsdt", 100, "directs", 1, "teamSize", 3, "hasDevice", 1)));
            when(mapper.capture(anyMap())).thenReturn(facts);
            when(mapper.insert(anyString(), anyString())).thenReturn(1);
            A2ReplayContext.enterReplay("independent-fixture-real-scope-only");
            try { captured = service.capture(); } finally { A2ReplayContext.exitReplay(); }
            stored = ((ObjectNode)captured.envelope()).deepCopy(); hash = captured.version();
            when(mapper.current()).thenAnswer(x -> new LeaderboardPauseSnapshotMapper.CurrentRow("on", json.writeValueAsString(Map.of("key", captured.id(), "hash", hash, "schemaVersion", 1)), captured.id(), json.writeValueAsString(stored)));
        }
        private LeaderboardPauseSnapshotMapper.CaptureRow row(String kind, Object value) throws Exception { return new LeaderboardPauseSnapshotMapper.CaptureRow(kind, json.writeValueAsString(value)); }
        void rehash() {
            hash = (String)ReflectionTestUtils.invokeMethod(service, "hash", stored);
            stored.put("contentHash", hash);
        }
        Map<String,Object> app(String period) {
            var appMapper = mock(AppTeamInsightsMapper.class);
            when(appMapper.userScope(7L)).thenReturn(new AppTeamInsightsMapper.UserScope(0, "V5"));
            var app = new AppTeamInsightsService(appMapper, mock(LeadershipPoolConfigGuard.class), config, new MockEnvironment(), null);
            ReflectionTestUtils.setField(app, "pauseSnapshots", service);
            return app.leaderboard(7L, period, 1, 20, null, null).getData();
        }
    }

    @ParameterizedTest @ValueSource(strings={"2026-10-08T12:00:00Z", "2026-10-08T17:00:00Z", "2026-10-11T17:00:00Z", "2026-10-31T17:00:00Z"})
    void captureMustPreserveExistingAppBusinessZoneAndPeriodWindows(String raw) {
        Instant at=Instant.parse(raw);
        var p=LeaderboardPauseSnapshotService.captureParameters(at);
        LocalDateTime local=LocalDateTime.ofInstant(at, DateTimeFormatConfig.BUSINESS_ZONE);
        LocalDate today=local.toLocalDate(), monday=today.with(DayOfWeek.MONDAY);
        System.out.println("ZONE_PROBE at="+raw+" actualCutoff="+p.get("snapshotAt")+" expectedCutoff="+local+" actualToday="+p.get("todayFromInclusive")+" expectedToday="+today.atStartOfDay()+" actualWeek="+p.get("weekFromInclusive")+" expectedWeek="+monday.atStartOfDay()+" actualMonth="+p.get("monthFromInclusive")+" expectedMonth="+today.withDayOfMonth(1).atStartOfDay());
        assertThat(p).containsEntry("snapshotAt", local).containsEntry("todayFromInclusive", today.atStartOfDay()).containsEntry("weekFromInclusive", monday.atStartOfDay()).containsEntry("monthFromInclusive", today.withDayOfMonth(1).atStartOfDay());
    }

    @Test void missingPcSummaryWithCorrectHashMustBeUnavailable() throws Exception {
        var f=new Fixture(); ((ObjectNode)f.stored.path("pcProjection")).remove("summary"); f.rehash();
        var state=f.service.current();
        System.out.println("MISSING_PC_SUMMARY mode="+state.mode()+" summary="+f.service.pcSummary(state));
        assertThat(state.mode()).isEqualTo("UNAVAILABLE");
    }
    @Test void missingAppPoolWithCorrectHashMustBeUnavailable() throws Exception {
        var f=new Fixture(); ((ObjectNode)f.stored.path("appPeriods").path("week")).remove("poolUsd"); f.rehash();
        var result=f.app("week");
        System.out.println("MISSING_APP_POOL state="+result.get("snapshotState")+" available="+result.get("dataAvailable")+" pool="+result.get("poolUsd"));
        assertThat(f.service.current().mode()).isEqualTo("UNAVAILABLE");
    }
    @Test void missingEarnedWithCorrectHashMustBeUnavailable() throws Exception {
        var f=new Fixture(); ((ObjectNode)f.stored.path("appPeriods").path("week").path("rows").get(0)).remove("earnedUsdt"); f.rehash();
        var result=f.app("week");
        System.out.println("MISSING_EARNED state="+result.get("snapshotState")+" rows="+result.get("rows"));
        assertThat(f.service.current().mode()).isEqualTo("UNAVAILABLE");
    }
    @Test void numericToTextAppPoolMustNotPassUnchangedHash() throws Exception {
        var f=new Fixture(); ((ObjectNode)f.stored.path("appPeriods").path("week")).put("poolUsd", "120");
        var result=f.app("week");
        System.out.println("POOL_TYPE_WITH_OLD_HASH mode="+f.service.current().mode()+" capturedPool=120 observedPool="+result.get("poolUsd")+" available="+result.get("dataAvailable"));
        assertThat(f.service.current().mode()).isEqualTo("UNAVAILABLE");
    }
    @Test void numericToTextPcPoolMustNotPassUnchangedHash() throws Exception {
        var f=new Fixture(); ((ObjectNode)f.stored.path("pcProjection").path("summary")).put("poolUsd", "900");
        var state=f.service.current();
        System.out.println("PC_POOL_TYPE_WITH_OLD_HASH mode="+state.mode()+" capturedPool=900 observed="+f.service.pcSummary(state));
        assertThat(state.mode()).isEqualTo("UNAVAILABLE");
    }
    @Test void wrongPeriodBasisWithCorrectHashMustBeUnavailable() throws Exception {
        var f=new Fixture(); ((ObjectNode)f.stored.path("appPeriods").path("week")).put("basis", "OTHER_BASIS"); f.rehash();
        System.out.println("WRONG_BASIS mode="+f.service.current().mode());
        assertThat(f.service.current().mode()).isEqualTo("UNAVAILABLE");
    }
    @Test void malformedRowWithCorrectHashMustBeUnavailableInsteadOfConsumerException() throws Exception {
        var f=new Fixture(); ((ObjectNode)f.stored.path("appPeriods").path("week").path("rows").get(0)).putObject("userId"); f.rehash();
        var state=f.service.current();
        String consumer;
        try { f.app("week"); consumer="SUCCESS"; } catch(RuntimeException ex) { consumer=ex.getClass().getSimpleName(); }
        System.out.println("BAD_ROW_TYPE mode="+state.mode()+" consumer="+consumer);
        assertThat(state.mode()).isEqualTo("UNAVAILABLE");
    }

    @Test void changedValueWithoutRehashIsUnavailable() throws Exception {
        var f=new Fixture(); ((ObjectNode)f.stored.path("appPeriods").path("week")).put("poolUsd", 999);
        assertThat(f.service.current().mode()).isEqualTo("UNAVAILABLE");
    }
    @Test void factualEmptyRowsRemainAvailableFrozen() throws Exception {
        var f=new Fixture(); for(String period:PERIODS) ((ObjectNode)f.stored.path("appPeriods").path(period)).putArray("rows"); f.rehash();
        assertThat(f.service.current().mode()).isEqualTo("FROZEN");
        assertThat(f.app("week")).containsEntry("dataAvailable", true).containsEntry("rows", List.of());
    }

    @ParameterizedTest @org.junit.jupiter.params.provider.CsvSource({"false,on", "false,off", "true,on", "true,off"})
    void actualPauseAndResumeWritersMustPropagateRequiredAuditFailure(boolean enabled, String next) throws Exception {
        var adapter=new OpsTeamServiceTest(); adapter.seedPermissionContext();
        var writer=(OpsTeamService)ReflectionTestUtils.getField(adapter,"service");
        var cfg=(PlatformConfigFacade)ReflectionTestUtils.getField(adapter,"configFacade");
        var outbox=(ffdd.opsconsole.shared.outbox.EventOutboxService)ReflectionTestUtils.getField(adapter,"eventOutboxService");
        var f=new Fixture(); var snapshots=mock(LeaderboardPauseSnapshotService.class);
        when(snapshots.capture()).thenReturn(f.captured);
        when(snapshots.current()).thenAnswer(x -> cfg.activeValue("team.ui.F.leaderboard.paused").filter("on"::equals).isPresent() ? f.captured : LeaderboardPauseSnapshotService.State.live());
        when(snapshots.pcSummary(any())).thenReturn(Map.of("poolUsd",new BigDecimal("900"),"participantCount",88));
        when(snapshots.pcPodium(any())).thenReturn(List.of());
        var properties=new ffdd.opsconsole.shared.audit.AuditProperties(); properties.setEnabled(enabled); properties.setFailFast(false);
        var auditMapper=mock(ffdd.opsconsole.shared.audit.mapper.AuditLogMapper.class);
        var runtime=mock(ffdd.opsconsole.platform.application.A2RuntimePolicy.class);
        when(runtime.retentionMonths()).thenReturn(13); when(runtime.schemaVersion()).thenReturn("v3");
        when(auditMapper.insertAuditLog(any())).thenThrow(new IllegalStateException("fixture audit insert failure"));
        var realAudit=new ffdd.opsconsole.shared.audit.AuditLogService(auditMapper,
                new ffdd.opsconsole.shared.audit.AuditLogSanitizer(new ObjectMapper()),
                new ffdd.opsconsole.shared.audit.ApplicationNameProperties(), properties,null,runtime);
        ReflectionTestUtils.setField(writer,"auditLogService",realAudit);
        ReflectionTestUtils.setField(writer,"pauseSnapshots",snapshots);
        cfg.upsertAdminValue("team.ui.F.leaderboard.paused","on".equals(next) ? "off" : "on","TEXT","fixture","fixture");
        A2ReplayContext.enterReplay("independent-audit-failure-fixture");
        try {
            assertThatThrownBy(() -> writer.updateConfig("independent-pause-disabled-audit",new ffdd.opsconsole.team.dto.TeamCommissionConfigUpdateRequest("F.leaderboard.paused",next,"fixture required audit failure","fixture-admin")))
                    .hasMessage(enabled ? "fixture audit insert failure" : "AUDIT_REQUIRED_DISABLED");
            if (enabled) verify(auditMapper).insertAuditLog(any()); else verifyNoInteractions(auditMapper);
            verify(outbox, never()).publish(anyString(), anyString(), anyString(), anyMap());
        } finally { A2ReplayContext.exitReplay(); org.springframework.security.core.context.SecurityContextHolder.clearContext(); }
    }
}
