package ffdd.opsconsole.janus.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.janus.domain.JanusDeviceView;
import ffdd.opsconsole.janus.domain.JanusRepository;
import ffdd.opsconsole.janus.domain.JanusRuleEvaluator;
import ffdd.opsconsole.janus.domain.JanusStrategyView;
import ffdd.opsconsole.janus.domain.JanusStrategyVersionView;
import ffdd.opsconsole.janus.dto.JanusDeviceQueryRequest;
import ffdd.opsconsole.janus.dto.JanusDeviceReportRequest;
import ffdd.opsconsole.janus.dto.JanusCommandAckRequest;
import ffdd.opsconsole.janus.dto.JanusStatusChangeRequest;
import ffdd.opsconsole.janus.dto.JanusStrategyActionRequest;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.api.PageResult;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.outbox.EventOutboxService;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

class OpsJanusServiceTest {
    private final JanusRepository repository = mock(JanusRepository.class);
    private final AuditLogService audit = mock(AuditLogService.class);
    private final EventOutboxService outbox = mock(EventOutboxService.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final OpsJanusService service = new OpsJanusService(
            repository, new JanusRuleEvaluator(), objectMapper, audit, outbox);

    @BeforeEach
    void commandDoesNotExist() {
        when(repository.findCommand(any())).thenReturn(Optional.empty());
        when(repository.reserveCommand(any(), any(), any(), any(), any())).thenReturn(true);
        when(repository.reserveDailyEvaluation(any(), any(), anyInt())).thenReturn(true);
        when(repository.insertEvaluation(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), anyInt(), any()))
                .thenReturn(true);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "superadmin", "n/a", List.of(new SimpleGrantedAuthority("risk_k6_write"))));
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void emptyBusinessTableStaysEmptyAndDoesNotInventDemoDevices() {
        JanusDeviceQueryRequest query = new JanusDeviceQueryRequest(null, null, null, null, null, 1, 25);
        when(repository.pageDevices(query)).thenReturn(new PageResult<>(0, 1, 25, List.of()));

        ApiResult<PageResult<JanusDeviceView>> result = service.devices(query);

        assertThat(result.getData().getRecords()).isEmpty();
        verify(repository, never()).updateDeviceStatus(any(), anyLong(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void illegalTransitionIsRejectedBeforeCommandOrAuditWrite() {
        JanusDeviceView device = device("ACTIVATED", 3);
        when(repository.findDevice("SID-1")).thenReturn(Optional.of(device));

        ApiResult<JanusDeviceView> result = service.updateStatus("SID-1", "idem-1",
                new JanusStatusChangeRequest("NEW", "现场复核", "证据完整确认需要重置", "immediate", null, null, "strong_single", 3L));

        assertThat(result.getCode()).isEqualTo(409);
        assertThat(result.getMessage()).isEqualTo("ILLEGAL_STATUS_TRANSITION");
        verify(audit, never()).recordRequired(any());
        verify(outbox, never()).publish(any(), any(), any(), any());
    }

    @Test
    void legalTransitionUsesCasAndCommitsRequiredAuditAndOutbox() {
        JanusDeviceView before = device("RECOMMENDED", 7);
        JanusDeviceView after = device("RECOMMENDED", 8);
        when(repository.findDevice("SID-1")).thenReturn(Optional.of(before), Optional.of(after));
        when(repository.updateDeviceStatus(eq("SID-1"), eq(7L), eq("HIT"), any(), any(), any(), any(), eq("PUBLISHED")))
                .thenReturn(true);

        ApiResult<JanusDeviceView> result = service.updateStatus("SID-1", "idem-2",
                new JanusStatusChangeRequest("HIT", "现场复核", "行为证据完整确认命中", "immediate", null, null, "standard", 7L));

        assertThat(result.getCode()).isZero();
        verify(repository).completeCommand(eq("idem-2"), eq("PUBLISHED"), any());
        verify(outbox).publish(eq("JANUS_DEVICE"), eq("SID-1"), eq("JANUS_DEVICE_STATUS_REQUESTED"), any());
        verify(audit).recordRequired(any());
    }

    @Test
    void seniorOperatorCannotBypassUiAndPublishStrategyDirectly() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "operator-1", "n/a", List.of(new SimpleGrantedAuthority("risk_k6_write"))));
        when(repository.findStrategy("K6-1")).thenReturn(Optional.of(strategy("draft", 2)));

        ApiResult<JanusStrategyView> result = service.lifecycle("K6-1", "publish", "idem-publish",
                new JanusStrategyActionRequest("发布复核通过", "发布复核通过", null, 2L, "DR-1", "hash"));

        assertThat(result.getCode()).isEqualTo(403);
        assertThat(result.getMessage()).isEqualTo("ROLE_FORBIDDEN");
        verify(repository, never()).updateStrategyLifecycle(any(), anyLong(), any(), anyInt(), any());
        verify(outbox, never()).publish(any(), any(), any(), any());
    }

    @Test
    void dryRunHonorsZeroPercentRollout() throws Exception {
        JanusStrategyView strategy = new JanusStrategyView("K6-1", "策略一", "测试策略", "draft", 1, 10, "owner",
                objectMapper.createObjectNode(), objectMapper.readTree("""
                        {"mode":"ALL","rules":[{"field":"maturityScore","op":">=","value":0}]}
                        """), objectMapper.readTree("{" + "\"type\":\"RECOMMEND\"}"),
                objectMapper.createObjectNode(), objectMapper.readTree("{" + "\"percent\":0}"),
                objectMapper.createObjectNode(), null, List.of(), 1L, null, 2L);
        when(repository.findStrategy("K6-1")).thenReturn(Optional.of(strategy));
        when(repository.pageDevices(any())).thenReturn(new PageResult<>(1, 1, 200, List.of(device("OBSERVING", 1))));

        ApiResult<java.util.Map<String, Object>> result = service.dryRun("K6-1", "idem-dry",
                new JanusStrategyActionRequest("灰度验证", "灰度验证", null, 2L, null, null));

        assertThat(result.getData()).containsEntry("evaluated", 1).containsEntry("hit", 0);
    }

    @Test
    void authenticatedDeviceReportUpsertsAuthoritativeBusinessRow() {
        long now = System.currentTimeMillis();
        JanusDeviceReportRequest report = new JanusDeviceReportRequest("R-1", "DEVICE-1", now, now - 1_000,
                now - 10_000, null, "official", null, "OBSERVING", false, "ua", "Android", "Pixel",
                "Android 15", "Chrome", 10, 20, 30, 40, objectMapper.createObjectNode(),
                objectMapper.createObjectNode(), null, null, null, null, objectMapper.createArrayNode());
        when(repository.findDevice(any())).thenReturn(Optional.of(device("OBSERVING", 1)));

        ApiResult<JanusDeviceView> result = service.reportDevice(42L, report);

        assertThat(result.getCode()).isZero();
        ArgumentCaptor<JanusDeviceReportRequest> saved = ArgumentCaptor.forClass(JanusDeviceReportRequest.class);
        verify(repository).insertEvaluation(any(), eq("R-1"), any(), any(), any(), any(), any(), any(), any(), any(), anyInt(), eq("janus-server-v1"));
        verify(repository).upsertDeviceReport(eq(42L), any(), saved.capture());
        assertThat(saved.getValue().reportedStatus()).isEqualTo("OBSERVING");
        assertThat(saved.getValue().maturityScore()).isZero();
        assertThat(saved.getValue().recommendationScore()).isEqualTo(15);
        assertThat(saved.getValue().environmentRiskScore()).isZero();
        assertThat(saved.getValue().hitStrategy()).isNull();
        assertThat(saved.getValue().latestDecision().path("reportId").asText()).isEqualTo("R-1");
    }

    @Test
    void duplicateReportIdReturnsStoredDeviceWithoutApplyingTelemetryTwice() {
        long now = System.currentTimeMillis();
        JanusDeviceReportRequest report = new JanusDeviceReportRequest("R-1", "DEVICE-1", now, now - 1_000,
                now - 10_000, null, "official", null, "BLOCKED", true, "ua", "Android", "Pixel",
                "Android 15", "Chrome", 100, 100, 100, 100, objectMapper.createObjectNode(),
                objectMapper.createObjectNode(), "forged", 99, null, null, objectMapper.createArrayNode());
        when(repository.insertEvaluation(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), anyInt(), any()))
                .thenReturn(false);
        when(repository.findDevice(any())).thenReturn(Optional.of(device("OBSERVING", 1)));

        ApiResult<JanusDeviceView> result = service.reportDevice(42L, report);

        assertThat(result.getCode()).isZero();
        verify(repository, never()).upsertDeviceReport(anyLong(), any(), any());
    }

    @Test
    void expiredOverrideIsRestoredEvenWhenTheLastReportResponseWasRetried() {
        long now = System.currentTimeMillis();
        JanusDeviceReportRequest report = new JanusDeviceReportRequest("R-EXPIRED", "DEVICE-1", now,
                now - 1_000, now - 10_000, null, "official", null, null, false, "ua", "Android",
                "Pixel", "Android 15", "Chrome", 0, 0, 0, 0, objectMapper.createObjectNode(),
                objectMapper.createObjectNode(), null, null, null, null, objectMapper.createArrayNode());
        when(repository.expireDeviceOverride(eq(42L), any(), anyLong())).thenReturn(true);
        when(repository.insertEvaluation(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), anyInt(), any()))
                .thenReturn(false);
        when(repository.findDevice(any())).thenReturn(Optional.of(device("OBSERVING", 2)));

        ApiResult<JanusDeviceView> result = service.reportDevice(42L, report);

        assertThat(result.getCode()).isZero();
        verify(repository).upsertDeviceReport(eq(42L), any(), any());
    }

    @Test
    void publishedStrategyEvaluatesServerDerivedSignalsAndIgnoresForgedClientDecision() throws Exception {
        long now = System.currentTimeMillis();
        var maturity = objectMapper.readTree("{\"appOpenCount\":10}");
        JanusDeviceReportRequest report = new JanusDeviceReportRequest("R-2", "DEVICE-1", now, now - 1_000,
                now - 10_000, null, "official", null, "BLOCKED", true, "ua", "Android", "Pixel",
                "Android 15", "Chrome", 100, 100, 100, 100, maturity,
                objectMapper.createObjectNode(), "forged", 99, null, null, objectMapper.createArrayNode());
        JanusStrategyView active = new JanusStrategyView("K6-ACTIVE", "真实在线策略", "服务端判定", "active", 4,
                100, "owner", objectMapper.createObjectNode(),
                objectMapper.readTree("{\"mode\":\"ALL\",\"rules\":[{\"field\":\"maturityScore\",\"op\":\">=\",\"value\":20}]}"),
                objectMapper.readTree("{\"type\":\"REVERSAL_IMMEDIATE\",\"remoteUrlKey\":\"promo\"}"),
                objectMapper.createObjectNode(), objectMapper.createObjectNode(), objectMapper.createObjectNode(),
                null, List.of(), 1L, 2L, 3L);
        when(repository.strategies()).thenReturn(List.of(active));
        when(repository.findDevice(any())).thenReturn(Optional.of(device("HIT", 1)));
        when(repository.publishStrategyCommand(any(), anyLong(), any(), any(), any())).thenReturn(true);

        ApiResult<JanusDeviceView> result = service.reportDevice(42L, report);

        assertThat(result.getCode()).isZero();
        ArgumentCaptor<JanusDeviceReportRequest> saved = ArgumentCaptor.forClass(JanusDeviceReportRequest.class);
        verify(repository).upsertDeviceReport(eq(42L), any(), saved.capture());
        assertThat(saved.getValue().reportedStatus()).isEqualTo("HIT");
        assertThat(saved.getValue().hitStrategy()).isEqualTo("K6-ACTIVE");
        assertThat(saved.getValue().hitStrategyVersion()).isEqualTo(4);
        assertThat(saved.getValue().maturityScore()).isEqualTo(20);
        assertThat(saved.getValue().environmentRiskScore()).isZero();
        verify(repository).publishStrategyCommand(any(), eq(1L), eq("HIT"), eq("promo"), any());
        verify(outbox).publish(any(), any(), eq("JANUS_STRATEGY_COMMAND_PUBLISHED"), any());
    }

    @Test
    void duplicateReportReleasesItsAtomicDailyQuotaReservation() throws Exception {
        long now = System.currentTimeMillis();
        JanusDeviceReportRequest report = new JanusDeviceReportRequest("R-DUP", "DEVICE-1", now, now - 1_000,
                now - 10_000, null, "official", null, null, false, "ua", "Android", "Pixel",
                "Android 15", "Chrome", 0, 0, 0, 0, objectMapper.readTree("{\"appOpenCount\":10}"),
                objectMapper.createObjectNode(), null, null, null, null, objectMapper.createArrayNode());
        JanusStrategyView active = cappedActiveStrategy();
        when(repository.strategies()).thenReturn(List.of(active));
        when(repository.insertEvaluation(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), anyInt(), any()))
                .thenReturn(false);
        when(repository.findDevice(any())).thenReturn(Optional.of(device("OBSERVING", 1)));

        service.reportDevice(42L, report);

        verify(repository).reserveDailyEvaluation("K6-CAPPED", "REVERSAL_IMMEDIATE", 1);
        verify(repository).releaseDailyEvaluation("K6-CAPPED", "REVERSAL_IMMEDIATE");
        verify(repository, never()).upsertDeviceReport(anyLong(), any(), any());
    }

    @Test
    void exhaustedAtomicDailyQuotaFallsThroughWithoutApplyingStrategy() throws Exception {
        long now = System.currentTimeMillis();
        JanusDeviceReportRequest report = new JanusDeviceReportRequest("R-CAP", "DEVICE-1", now, now - 1_000,
                now - 10_000, null, "official", null, null, false, "ua", "Android", "Pixel",
                "Android 15", "Chrome", 0, 0, 0, 0, objectMapper.readTree("{\"appOpenCount\":10}"),
                objectMapper.createObjectNode(), null, null, null, null, objectMapper.createArrayNode());
        when(repository.strategies()).thenReturn(List.of(cappedActiveStrategy()));
        when(repository.reserveDailyEvaluation("K6-CAPPED", "REVERSAL_IMMEDIATE", 1)).thenReturn(false);
        when(repository.findDevice(any())).thenReturn(Optional.of(device("OBSERVING", 1)));

        service.reportDevice(42L, report);

        ArgumentCaptor<JanusDeviceReportRequest> saved = ArgumentCaptor.forClass(JanusDeviceReportRequest.class);
        verify(repository).upsertDeviceReport(eq(42L), any(), saved.capture());
        assertThat(saved.getValue().hitStrategy()).isNull();
        assertThat(saved.getValue().reportedStatus()).isEqualTo("OBSERVING");
    }

    @Test
    void commandAckUsesOwnershipRevisionAndClosesInFlightCommand() {
        when(repository.acknowledgeDeviceCommand(eq(42L), any(), eq(3L), eq(true), eq("HIT"))).thenReturn(true);

        ApiResult<java.util.Map<String, Object>> result = service.acknowledgeCommand(42L,
                new JanusCommandAckRequest("DEVICE-1", 3L, true, "HIT", "applied"));

        assertThat(result.getData()).containsEntry("revision", 3L).containsEntry("state", "ACKED");
        verify(repository).updateDeviceCommandRecord(any(), eq("ACKED"));
    }

    @Test
    void repeatedCommandAckReturnsTheOriginalSuccessAfterAResponseIsLost() {
        when(repository.acknowledgeDeviceCommand(eq(42L), any(), eq(3L), eq(true), eq("HIT"))).thenReturn(false);
        when(repository.isDeviceCommandAckReplay(eq(42L), any(), eq(3L), eq(true), eq("HIT"))).thenReturn(true);

        ApiResult<java.util.Map<String, Object>> result = service.acknowledgeCommand(42L,
                new JanusCommandAckRequest("DEVICE-1", 3L, true, "HIT", "retry"));

        assertThat(result.getCode()).isZero();
        assertThat(result.getData()).containsEntry("revision", 3L).containsEntry("state", "ACKED");
        verify(repository, never()).updateDeviceCommandRecord(any(), any());
    }

    @Test
    void numericJwtPrincipalStillRecognizesSeededSuperAdminUsername() {
        var auth = new UsernamePasswordAuthenticationToken(
                "1", "n/a", List.of(new SimpleGrantedAuthority("risk_k6_write")));
        auth.setDetails(java.util.Map.of("subjectType", "ADMIN", "username", "superadmin"));
        SecurityContextHolder.getContext().setAuthentication(auth);
        JanusStrategyView before = strategy("active", 2);
        JanusStrategyView after = new JanusStrategyView(before.strategyId(), before.name(), before.description(),
                "active", 2, before.priority(), before.owner(), before.scope(), before.ruleTree(), before.action(),
                before.safeguards(), before.rollout(), before.healthConfig(), before.templateKey(), before.versions(),
                before.createdAt(), before.publishedAt(), 3L);
        JanusStrategyVersionView target = new JanusStrategyVersionView(1, "baseline", "superadmin", 1L,
                objectMapper.createObjectNode(), objectMapper.createObjectNode(), objectMapper.createObjectNode(), "hash");
        when(repository.findStrategy("K6-1")).thenReturn(Optional.of(before), Optional.of(after));
        when(repository.findStrategyVersion("K6-1", 1)).thenReturn(Optional.of(target));
        when(repository.replaceStrategyFromSnapshot(eq("K6-1"), eq(2L), anyInt(), eq("active"), any())).thenReturn(true);

        ApiResult<JanusStrategyView> result = service.rollback("K6-1", "idem-superadmin",
                new JanusStrategyActionRequest("回滚到稳定版本", "回滚到稳定版本", 1, 2L, null, null));

        assertThat(result.getCode()).isZero();
        verify(repository).replaceStrategyFromSnapshot(eq("K6-1"), eq(2L), anyInt(), eq("active"), any());
    }

    private JanusDeviceView device(String status, long version) {
        return new JanusDeviceView("SID-1", "D-1", 1L, System.currentTimeMillis(), 1L, 2,
                null, "official", null, status, null, null, "system", false, null,
                60, 65, 10, 80, null, "Android", "Pixel", "Android 15", "Chrome",
                objectMapper.createObjectNode(), objectMapper.createObjectNode(), null, null,
                objectMapper.createObjectNode(), objectMapper.createObjectNode(), objectMapper.createObjectNode(),
                null, null, null, objectMapper.createArrayNode(), version);
    }

    private JanusStrategyView strategy(String status, long version) {
        return new JanusStrategyView("K6-1", "策略一", "测试策略", status, 1, 10, "owner",
                objectMapper.createObjectNode(), objectMapper.createObjectNode(), objectMapper.createObjectNode(),
                objectMapper.createObjectNode(), objectMapper.createObjectNode(), objectMapper.createObjectNode(),
                null, List.of(), 1L, null, version);
    }

    private JanusStrategyView cappedActiveStrategy() throws Exception {
        return new JanusStrategyView("K6-CAPPED", "限额策略", "并发配额验证", "active", 1, 100, "owner",
                objectMapper.createObjectNode(),
                objectMapper.readTree("{\"mode\":\"ALL\",\"rules\":[{\"field\":\"maturityScore\",\"op\":\">=\",\"value\":20}]}"),
                objectMapper.readTree("{\"type\":\"REVERSAL_IMMEDIATE\",\"remoteUrlKey\":\"promo\"}"),
                objectMapper.readTree("{\"maxDailyHits\":1}"), objectMapper.createObjectNode(),
                objectMapper.createObjectNode(), null, List.of(), 1L, 2L, 3L);
    }
}
