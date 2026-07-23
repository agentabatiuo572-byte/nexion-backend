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
import ffdd.opsconsole.janus.dto.JanusStrategyUpsertRequest;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.api.PageResult;
import ffdd.opsconsole.shared.audit.AuditLogRecord;
import ffdd.opsconsole.shared.audit.AuditLogSanitizer;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.shared.outbox.EventOutboxService;
import java.time.LocalDateTime;
import java.util.ArrayList;
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
        when(repository.insertEvaluation(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), anyInt(), any()))
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
                new JanusStatusChangeRequest("NEW", "复核环境信号", "证据完整确认需要重置", "immediate", null, null, "strong_single", 3L));

        assertThat(result.getCode()).isEqualTo(409);
        assertThat(result.getMessage()).isEqualTo("ILLEGAL_STATUS_TRANSITION");
        verify(audit, never()).recordRequired(any());
        verify(outbox, never()).publish(any(), any(), any(), any());
    }

    @Test
    void invalidReasonCategoryIsRejectedBeforeReadingOrWritingDevice() {
        ApiResult<JanusDeviceView> result = service.updateStatus("SID-1", "idem-invalid-category",
                new JanusStatusChangeRequest("HIT", "手工输入的未知分类", "证据完整确认需要命中",
                        "immediate", null, null, "standard", 3L));

        assertThat(result.getCode()).isEqualTo(422);
        assertThat(result.getMessage()).isEqualTo("REASON_CATEGORY_INVALID");
        verify(repository, never()).findDevice(any());
        verify(audit, never()).recordRequired(any());
    }

    @Test
    void overlongReasonIsRejectedBeforeReadingOrWritingDevice() {
        ApiResult<JanusDeviceView> result = service.updateStatus("SID-1", "idem-long-reason",
                new JanusStatusChangeRequest("HIT", "复核环境信号", "原因".repeat(251),
                        "immediate", null, null, "standard", 3L));

        assertThat(result.getCode()).isEqualTo(422);
        assertThat(result.getMessage()).isEqualTo("REASON_TOO_LONG");
        verify(repository, never()).findDevice(any());
        verify(audit, never()).recordRequired(any());
    }

    @Test
    void legalTransitionUsesCasAndCommitsRequiredAuditAndOutbox() throws Exception {
        JanusDeviceView before = deviceWithEscapes("RECOMMENDED", 7);
        JanusDeviceView after = deviceWithEscapes("RECOMMENDED", 8);
        String idempotencyKey = "\\\"".repeat(64);
        String reasonCategory = "复核环境信号";
        String reason = "\\\"".repeat(250);
        when(repository.findDevice("SID-1")).thenReturn(Optional.of(before), Optional.of(after));
        when(repository.updateDeviceStatus(eq("SID-1"), eq(7L), eq("HIT"), any(), any(), any(), any(), eq("PUBLISHED")))
                .thenReturn(true);

        ApiResult<JanusDeviceView> result = service.updateStatus("SID-1", idempotencyKey,
                new JanusStatusChangeRequest("HIT", reasonCategory, reason, "immediate", null, null, "standard", 7L));

        assertThat(result.getCode()).isZero();
        verify(repository).completeCommand(eq(idempotencyKey), eq("PUBLISHED"), any());
        verify(outbox).publish(eq("JANUS_DEVICE"), eq("SID-1"), eq("JANUS_DEVICE_STATUS_REQUESTED"), any());
        ArgumentCaptor<AuditLogWriteRequest> auditRequest = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
        verify(audit).recordRequired(auditRequest.capture());
        var detail = objectMapper.valueToTree(auditRequest.getValue().getDetail());
        JsonNodeAssertions.assertDeviceMutation(detail, reason, reasonCategory);
        var safeDetail = objectMapper.readTree(new AuditLogSanitizer(objectMapper)
                .toSafeJson(auditRequest.getValue().getDetail()));
        JsonNodeAssertions.assertDeviceMutation(safeDetail, reason, reasonCategory);
        assertThat(safeDetail.path("truncated").asBoolean(false)).isFalse();
    }

    @Test
    void writeOnlyAuthorityCannotPerformSeniorTransition() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "operator-1", "n/a", List.of(new SimpleGrantedAuthority("risk_k6_write"))));
        when(repository.findDevice("SID-1")).thenReturn(Optional.of(device("OBSERVING", 3)));

        ApiResult<JanusDeviceView> result = service.updateStatus("SID-1", "idem-operator",
                new JanusStatusChangeRequest("BLOCKED", "高风险设备排除", "证据完整确认需要阻断", "immediate",
                        null, null, "standard", 3L));

        assertThat(result.getCode()).isEqualTo(403);
        assertThat(result.getMessage()).isEqualTo("ROLE_FORBIDDEN");
        verify(repository, never()).updateDeviceStatus(any(), anyLong(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void explicitSeniorAuthorityCanPerformSeniorTransition() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "senior-1", "n/a", List.of(new SimpleGrantedAuthority("risk_k6_write"),
                new SimpleGrantedAuthority("risk_k6_senior"))));
        JanusDeviceView before = device("OBSERVING", 3);
        JanusDeviceView after = device("OBSERVING", 4);
        when(repository.findDevice("SID-1")).thenReturn(Optional.of(before), Optional.of(after));
        when(repository.updateDeviceStatus(eq("SID-1"), eq(3L), eq("BLOCKED"), any(), any(), any(), any(),
                eq("PUBLISHED"))).thenReturn(true);

        ApiResult<JanusDeviceView> result = service.updateStatus("SID-1", "idem-senior",
                new JanusStatusChangeRequest("BLOCKED", "高风险设备排除", "证据完整确认需要阻断", "immediate",
                        null, null, "standard", 3L));

        assertThat(result.getCode()).isZero();
    }

    @Test
    void seniorOperatorCannotBypassUiAndPublishStrategyDirectly() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "senior-1", "n/a", List.of(new SimpleGrantedAuthority("risk_k6_write"),
                new SimpleGrantedAuthority("risk_k6_senior"))));
        when(repository.findStrategy("K6-1")).thenReturn(Optional.of(strategy("draft", 2)));

        ApiResult<JanusStrategyView> result = service.lifecycle("K6-1", "publish", "idem-publish",
                new JanusStrategyActionRequest("发布复核通过", "发布复核通过", null, 2L, "DR-1", "hash"));

        assertThat(result.getCode()).isEqualTo(403);
        assertThat(result.getMessage()).isEqualTo("ROLE_FORBIDDEN");
        verify(repository, never()).updateStrategyLifecycle(any(), anyLong(), any(), anyInt(), any());
        verify(outbox, never()).publish(any(), any(), any(), any());
    }

    @Test
    void seniorOperatorCannotBypassUiAndRollbackStrategyDirectly() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "senior-1", "n/a", List.of(new SimpleGrantedAuthority("risk_k6_write"),
                new SimpleGrantedAuthority("risk_k6_senior"))));

        ApiResult<JanusStrategyView> result = service.rollback("K6-1", "idem-rollback",
                new JanusStrategyActionRequest("回滚复核通过", "回滚复核通过", 1, 2L, null, null));

        assertThat(result.getCode()).isEqualTo(403);
        assertThat(result.getMessage()).isEqualTo("ROLE_FORBIDDEN");
        verify(repository, never()).findStrategy(any());
        verify(repository, never()).replaceStrategyFromSnapshot(any(), anyLong(), anyInt(), any(), any());
        verify(outbox, never()).publish(any(), any(), any(), any());
    }

    @Test
    void writeOnlyAuthorityCannotCreateStrategyOrReserveIdempotency() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "operator-1", "n/a", List.of(new SimpleGrantedAuthority("risk_k6_write"))));
        var request = strategyRequest(objectMapper.readTree("""
                        {"mode":"ALL","rules":[{"field":"maturityScore","op":">=","value":60,"label":"成熟度达标"}]}
                        """), objectMapper.createObjectNode(), objectMapper.createObjectNode(),
                objectMapper.createObjectNode());

        ApiResult<JanusStrategyView> result = service.createStrategy("idem-writer-create", request);

        assertThat(result.getCode()).isEqualTo(403);
        assertThat(result.getMessage()).isEqualTo("ROLE_FORBIDDEN");
        verify(repository, never()).reserveCommand(any(), any(), any(), any(), any());
        verify(repository, never()).createStrategy(any(), any());
        verify(audit, never()).recordRequired(any());
        verify(outbox, never()).publish(any(), any(), any(), any());
    }

    @Test
    void writeOnlyAuthorityCannotPauseStrategyOrTouchPersistence() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "operator-1", "n/a", List.of(new SimpleGrantedAuthority("risk_k6_write"))));

        ApiResult<JanusStrategyView> result = service.lifecycle("K6-1", "pause", "idem-writer-pause",
                new JanusStrategyActionRequest("暂停策略复核", "暂停策略复核", null, 2L, null, null));

        assertThat(result.getCode()).isEqualTo(403);
        assertThat(result.getMessage()).isEqualTo("ROLE_FORBIDDEN");
        verify(repository, never()).findStrategy(any());
        verify(repository, never()).reserveCommand(any(), any(), any(), any(), any());
        verify(repository, never()).updateStrategyLifecycle(any(), anyLong(), any(), anyInt(), any());
        verify(audit, never()).recordRequired(any());
        verify(outbox, never()).publish(any(), any(), any(), any());
    }

    @Test
    void viewerCannotCreateStrategyOrReserveIdempotency() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "viewer-1", "n/a", List.of(new SimpleGrantedAuthority("risk_k6_read"))));
        var request = strategyRequest(objectMapper.readTree("""
                        {"mode":"ALL","rules":[{"field":"maturityScore","op":">=","value":60,"label":"成熟度达标"}]}
                        """), objectMapper.createObjectNode(), objectMapper.createObjectNode(),
                objectMapper.createObjectNode());

        ApiResult<JanusStrategyView> result = service.createStrategy("idem-viewer-create", request);

        assertThat(result.getCode()).isEqualTo(403);
        assertThat(result.getMessage()).isEqualTo("ROLE_FORBIDDEN");
        verify(repository, never()).reserveCommand(any(), any(), any(), any(), any());
        verify(repository, never()).createStrategy(any(), any());
    }

    @Test
    void explicitSeniorAuthorityCanCreateAndPauseStrategies() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "senior-1", "n/a", List.of(new SimpleGrantedAuthority("risk_k6_write"),
                new SimpleGrantedAuthority("risk_k6_senior"))));
        var request = strategyRequest(objectMapper.readTree("""
                        {"mode":"ALL","rules":[{"field":"maturityScore","op":">=","value":60,"label":"成熟度达标"}]}
                        """), objectMapper.createObjectNode(), objectMapper.createObjectNode(),
                objectMapper.createObjectNode());
        JanusStrategyView created = strategy("draft", 1);
        JanusStrategyView active = strategy("active", 2);
        JanusStrategyView paused = new JanusStrategyView(active.strategyId(), active.name(), active.description(),
                "paused", active.version(), active.priority(), active.owner(), active.scope(), active.ruleTree(),
                active.action(), active.safeguards(), active.rollout(), active.healthConfig(), active.templateKey(),
                active.versions(), active.createdAt(), active.publishedAt(), 3L);
        when(repository.createStrategy(any(), eq(request))).thenReturn(created);
        when(repository.findStrategy("K6-1")).thenReturn(Optional.of(active), Optional.of(paused));
        when(repository.updateStrategyLifecycle("K6-1", 2L, "paused", active.version(), active.publishedAt()))
                .thenReturn(true);

        ApiResult<JanusStrategyView> createResult = service.createStrategy("idem-senior-create", request);
        ApiResult<JanusStrategyView> pauseResult = service.lifecycle("K6-1", "pause", "idem-senior-pause",
                new JanusStrategyActionRequest("暂停策略复核", "暂停策略复核", null, 2L, null, null));

        assertThat(createResult.getCode()).isZero();
        assertThat(pauseResult.getCode()).isZero();
        assertThat(pauseResult.getData().status()).isEqualTo("paused");
    }

    @Test
    void idempotencyReservationIsBoundToActorAndProcessingIsNotReportedAsSuccess() throws Exception {
        var request = strategyRequest(objectMapper.readTree("""
                        {"mode":"ALL","rules":[{"field":"maturityScore","op":">=","value":60,"label":"成熟度达标"}]}
                        """), objectMapper.createObjectNode(), objectMapper.createObjectNode(), objectMapper.createObjectNode());
        when(repository.reserveCommand(eq("idem-other-actor"), any(), any(), any(), any())).thenReturn(false);
        when(repository.findCommand("idem-other-actor")).thenReturn(Optional.of(java.util.Map.of(
                "commandType", "STRATEGY_CREATE",
                "targetId", "K6-" + sha256(objectMapper.writeValueAsString("idem-other-actor")).substring(0, 12).toUpperCase(),
                "requestHash", sha256(objectMapper.writeValueAsString(request)),
                "actorId", "operator-else", "state", "ACKED", "payloadJson", "{}")));

        ApiResult<JanusStrategyView> otherActor = service.createStrategy("idem-other-actor", request);

        assertThat(otherActor.getCode()).isEqualTo(409);
        assertThat(otherActor.getMessage()).isEqualTo("IDEMPOTENCY_CONFLICT");

        String differentPayloadKey = "idem-different-payload";
        when(repository.reserveCommand(eq(differentPayloadKey), any(), any(), any(), any())).thenReturn(false);
        when(repository.findCommand(differentPayloadKey)).thenReturn(Optional.of(java.util.Map.of(
                "commandType", "STRATEGY_CREATE",
                "targetId", "K6-" + sha256(objectMapper.writeValueAsString(differentPayloadKey)).substring(0, 12).toUpperCase(),
                "requestHash", "different-request-hash",
                "actorId", "superadmin", "state", "ACKED", "payloadJson", "{}")));

        ApiResult<JanusStrategyView> differentPayload = service.createStrategy(differentPayloadKey, request);

        assertThat(differentPayload.getCode()).isEqualTo(409);
        assertThat(differentPayload.getMessage()).isEqualTo("IDEMPOTENCY_CONFLICT");

        String processingKey = "idem-processing";
        when(repository.reserveCommand(eq(processingKey), any(), any(), any(), any())).thenReturn(false);
        when(repository.findCommand(processingKey)).thenAnswer(invocation -> Optional.of(java.util.Map.of(
                "commandType", "STRATEGY_CREATE",
                "targetId", "K6-" + sha256(objectMapper.writeValueAsString(processingKey)).substring(0, 12).toUpperCase(),
                "requestHash", sha256(objectMapper.writeValueAsString(request)),
                "actorId", "superadmin", "state", "PROCESSING", "payloadJson", "{}")));

        ApiResult<JanusStrategyView> processing = service.createStrategy(processingKey, request);

        assertThat(processing.getCode()).isEqualTo(409);
        assertThat(processing.getMessage()).isEqualTo("IDEMPOTENCY_IN_PROGRESS");
    }

    @Test
    void overlongIdempotencyKeyIsRejectedBeforeDatabaseReservation() throws Exception {
        var request = strategyRequest(objectMapper.readTree("""
                        {"mode":"ALL","rules":[{"field":"maturityScore","op":">=","value":60,"label":"成熟度达标"}]}
                        """), objectMapper.createObjectNode(), objectMapper.createObjectNode(), objectMapper.createObjectNode());

        ApiResult<JanusStrategyView> result = service.createStrategy("x".repeat(129), request);

        assertThat(result.getCode()).isEqualTo(422);
        assertThat(result.getMessage()).isEqualTo("IDEMPOTENCY_KEY_INVALID");
        verify(repository, never()).reserveCommand(any(), any(), any(), any(), any());
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
                now - 10_000, null, "official", null, "OBSERVING", false, "ua", "android", "Pixel",
                "Android 15", "Chrome", 10, 20, 30, 40, maturity(0),
                environment(), null, null, null, null, objectMapper.createArrayNode());
        when(repository.findDevice(any())).thenReturn(Optional.of(device("OBSERVING", 1)));

        ApiResult<JanusDeviceView> result = service.reportDevice(42L, report);

        assertThat(result.getCode()).isZero();
        ArgumentCaptor<JanusDeviceReportRequest> saved = ArgumentCaptor.forClass(JanusDeviceReportRequest.class);
        verify(repository).insertEvaluation(any(), eq("R-1"), any(), any(), any(), any(), any(), any(), any(), any(), any(), anyInt(), eq("janus-server-v1"));
        verify(repository).upsertDeviceReport(eq(42L), any(), saved.capture());
        assertThat(saved.getValue().reportedStatus()).isEqualTo("OBSERVING");
        assertThat(saved.getValue().maturityScore()).isZero();
        assertThat(saved.getValue().recommendationScore()).isEqualTo(15);
        assertThat(saved.getValue().environmentRiskScore()).isZero();
        assertThat(saved.getValue().platform()).isEqualTo("Android");
        assertThat(saved.getValue().hitStrategy()).isNull();
        assertThat(saved.getValue().latestDecision().path("reportId").asText()).isEqualTo("R-1");
    }

    @Test
    void unknownDevicePlatformIsRejectedBeforeAnyReadOrWrite() {
        long now = System.currentTimeMillis();
        JanusDeviceReportRequest report = new JanusDeviceReportRequest("R-PLATFORM", "DEVICE-1", now,
                now - 1_000, now - 10_000, null, "official", null, null, false, "ua", "future-os",
                "Device", "Future OS", "Browser", 0, 0, 0, 0, maturity(0), environment(),
                null, null, null, null, objectMapper.createArrayNode());

        ApiResult<JanusDeviceView> result = service.reportDevice(42L, report);

        assertThat(result.getCode()).isEqualTo(422);
        assertThat(result.getMessage()).isEqualTo("PLATFORM_INVALID");
        verify(repository, never()).findDevice(any());
        verify(repository, never()).insertEvaluation(any(), any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), anyInt(), any());
        verify(repository, never()).upsertDeviceReport(anyLong(), any(), any());
    }

    @Test
    void missingRequiredRawSignalsAreRejectedBeforeAnyReadOrWrite() throws Exception {
        long now = System.currentTimeMillis();
        var missingMaturitySignal = new JanusDeviceReportRequest("R-MISSING-M", "DEVICE-1", now, now - 1_000,
                now - 10_000, null, "official", null, null, false, "ua", "Android", "Pixel",
                "Android 15", "Chrome", 0, 0, 0, 0, objectMapper.readTree("""
                {"appOpenCount":1,"sessionCount":1,"foregroundDurationSeconds":2,"repeatStreakDays":1,
                 "benchmarkViewed":false,"optimizeDone":false,"marketViewed":false}
                """), objectMapper.readTree("""
                {"isHeadless":false,"automationSignalCount":0,"fpBlocklistHit":false,"screenAnomaly":false,
                 "timezoneMismatch":false,"languageMismatch":false}
                """), null, null, null, null, objectMapper.createArrayNode());
        var missingEnvironmentSignal = new JanusDeviceReportRequest("R-MISSING-E", "DEVICE-1", now, now - 1_000,
                now - 10_000, null, "official", null, null, false, "ua", "Android", "Pixel",
                "Android 15", "Chrome", 0, 0, 0, 0, objectMapper.readTree("""
                {"appOpenCount":1,"sessionCount":1,"foregroundDurationSeconds":2,"repeatStreakDays":1,
                 "benchmarkViewed":false,"optimizeDone":false,"marketViewed":false,"walletViewed":false}
                """), objectMapper.readTree("""
                {"isHeadless":false,"automationSignalCount":0,"fpBlocklistHit":false,"screenAnomaly":false,
                 "timezoneMismatch":false}
                """), null, null, null, null, objectMapper.createArrayNode());

        assertThat(service.reportDevice(42L, missingMaturitySignal).getCode()).isEqualTo(422);
        assertThat(service.reportDevice(42L, missingEnvironmentSignal).getCode()).isEqualTo(422);
        verify(repository, never()).findDevice(any());
        verify(repository, never()).insertEvaluation(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), anyInt(), any());
        verify(repository, never()).upsertDeviceReport(anyLong(), any(), any());
    }

    @Test
    void duplicateReportIdReturnsStoredDeviceWithoutApplyingTelemetryTwice() throws Exception {
        long now = System.currentTimeMillis();
        JanusDeviceReportRequest report = new JanusDeviceReportRequest("R-1", "DEVICE-1", now, now - 1_000,
                now - 10_000, null, "official", null, "BLOCKED", true, "ua", "Android", "Pixel",
                "Android 15", "Chrome", 100, 100, 100, 100, maturity(0),
                environment(), "forged", 99, null, null, objectMapper.createArrayNode());
        when(repository.findEvaluationRequestHash(any(), eq("R-1")))
                .thenReturn(Optional.of(normalizedReportHash(report)));
        when(repository.findDevice(any())).thenReturn(Optional.of(device("OBSERVING", 1)));

        ApiResult<JanusDeviceView> result = service.reportDevice(42L, report);

        assertThat(result.getCode()).isZero();
        verify(repository, never()).strategies();
        verify(repository, never()).insertEvaluation(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), anyInt(), any());
        verify(repository, never()).upsertDeviceReport(anyLong(), any(), any());
    }

    @Test
    void duplicateReportIdWithDifferentNormalizedPayloadIsAConflict() {
        long now = System.currentTimeMillis();
        JanusDeviceReportRequest report = new JanusDeviceReportRequest("R-CONFLICT", "DEVICE-1", now,
                now - 1_000, now - 10_000, null, "official", null, null, false, "ua", "Android",
                "Pixel", "Android 15", "Chrome", 0, 0, 0, 0, maturity(1), environment(),
                null, null, null, null, objectMapper.createArrayNode());
        when(repository.findEvaluationRequestHash(any(), eq("R-CONFLICT")))
                .thenReturn(Optional.of("0".repeat(64)));
        when(repository.findDevice(any())).thenReturn(Optional.of(device("OBSERVING", 1)));

        ApiResult<JanusDeviceView> result = service.reportDevice(42L, report);

        assertThat(result.getCode()).isEqualTo(409);
        assertThat(result.getMessage()).isEqualTo("JANUS_REPORT_REPLAY_CONFLICT");
        verify(repository, never()).strategies();
        verify(repository, never()).insertEvaluation(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), anyInt(), any());
        verify(repository, never()).upsertDeviceReport(anyLong(), any(), any());
    }

    @Test
    void outOfOrderReportIsRecordedButCannotEvaluateStrategyConsumeQuotaOrPublishCommand() throws Exception {
        long now = System.currentTimeMillis();
        JanusDeviceReportRequest report = new JanusDeviceReportRequest("R-OLD", "DEVICE-1", now - 60_000,
                now - 70_000, now - 80_000, null, "official", null, null, false, "ua", "Android",
                "Pixel", "Android 15", "Chrome", 0, 0, 0, 0,
                maturity(10), environment(),
                null, null, null, null, objectMapper.createArrayNode());
        JanusDeviceView current = device("OBSERVING", 5);
        when(repository.findDevice(any())).thenReturn(Optional.of(current));
        when(repository.strategies()).thenReturn(List.of(cappedActiveStrategy()));

        ApiResult<JanusDeviceView> result = service.reportDevice(42L, report);

        assertThat(result.getCode()).isZero();
        assertThat(result.getData()).isSameAs(current);
        verify(repository).insertEvaluation(any(), eq("R-OLD"), any(), any(), eq(null), eq(null), any(), any(),
                eq("BENIGN"), eq("OBSERVING"), eq("OUT_OF_ORDER_REPORT"), anyInt(), eq("janus-server-v1"));
        verify(repository, never()).reserveDailyEvaluation(any(), any(), anyInt());
        verify(repository, never()).upsertDeviceReport(anyLong(), any(), any());
        verify(repository, never()).publishStrategyCommand(any(), anyLong(), any(), any(), any());
        verify(outbox, never()).publish(any(), any(), any(), any());
    }

    @Test
    void malformedStrategyConfigurationFailsClosedBeforeAnyReservationOrWrite() throws Exception {
        var validRule = objectMapper.readTree("""
                {"mode":"ALL","rules":[{"field":"maturityScore","op":">=","value":60,"label":"成熟度达标"}]}
                """);
        List<JanusStrategyUpsertRequest> invalid = List.of(
                strategyRequest(objectMapper.readTree("""
                        {"mode":"SOMETIMES","rules":[{"field":"maturityScore","op":">=","value":60,"label":"成熟度达标"}]}
                        """), objectMapper.createObjectNode(), objectMapper.createObjectNode(), objectMapper.createObjectNode()),
                strategyRequest(objectMapper.readTree("""
                        {"mode":"ALL","rules":[{"field":"maturityScore","op":"exec","value":60,"label":"成熟度达标"}]}
                        """), objectMapper.createObjectNode(), objectMapper.createObjectNode(), objectMapper.createObjectNode()),
                strategyRequest(objectMapper.readTree("""
                        {"mode":"ALL","rules":[{"field":"status","op":"=","value":"ROOTED","label":"设备状态"}]}
                        """), objectMapper.createObjectNode(), objectMapper.createObjectNode(), objectMapper.createObjectNode()),
                strategyRequest(objectMapper.readTree("""
                        {"mode":"ALL","rules":[{"field":"channel","op":"in","value":["affiliate"],"label":"渠道"}]}
                        """), objectMapper.createObjectNode(), objectMapper.createObjectNode(), objectMapper.createObjectNode()),
                strategyRequest(validRule, objectMapper.readTree("{\"maxDailyHits\":-1}"),
                        objectMapper.createObjectNode(), objectMapper.createObjectNode()),
                strategyRequest(validRule, objectMapper.createObjectNode(),
                        objectMapper.readTree("{\"percent\":\"all\"}"), objectMapper.createObjectNode()),
                strategyRequest(validRule, objectMapper.createObjectNode(), objectMapper.createObjectNode(),
                        objectMapper.readTree("{\"channels\":\"official\"}")),
                strategyRequest(validRule, objectMapper.createObjectNode(), objectMapper.createObjectNode(),
                        objectMapper.readTree("{\"channels\":[\"affiliate\"]}")));

        for (int i = 0; i < invalid.size(); i++) {
            ApiResult<JanusStrategyView> result = service.createStrategy("invalid-strategy-" + i, invalid.get(i));
            assertThat(result.getCode()).as("invalid strategy %s", i).isEqualTo(422);
        }
        verify(repository, never()).createStrategy(any(), any());
        verify(repository, never()).reserveCommand(any(), any(), any(), any(), any());
        verify(audit, never()).recordRequired(any());
    }

    @Test
    void expiredOverrideIsReleasedByServerClockWithoutReapplyingRetriedTelemetry() throws Exception {
        long now = System.currentTimeMillis();
        JanusDeviceReportRequest report = new JanusDeviceReportRequest("R-EXPIRED", "DEVICE-1", now,
                now - 1_000, now - 10_000, null, "official", null, null, false, "ua", "Android",
                "Pixel", "Android 15", "Chrome", 0, 0, 0, 0, maturity(0),
                environment(), null, null, null, null, objectMapper.createArrayNode());
        when(repository.expireDeviceOverride(eq(42L), any(), anyLong())).thenReturn(true);
        when(repository.findEvaluationRequestHash(any(), eq("R-EXPIRED")))
                .thenReturn(Optional.of(normalizedReportHash(report)));
        when(repository.findDevice(any())).thenReturn(Optional.of(device("OBSERVING", 2)));

        ApiResult<JanusDeviceView> result = service.reportDevice(42L, report);

        assertThat(result.getCode()).isZero();
        verify(repository).expireDeviceOverride(eq(42L), any(), anyLong());
        verify(repository, never()).upsertDeviceReport(anyLong(), any(), any());
    }

    @Test
    void serverClockOverrideExpiryRunsIndependentlyButStaleReportStillHasNoTelemetrySideEffects() {
        long now = System.currentTimeMillis();
        JanusDeviceReportRequest report = new JanusDeviceReportRequest("R-STALE-EXPIRE", "DEVICE-1", now - 60_000,
                now - 70_000, now - 80_000, null, "official", null, null, false, "ua", "Android",
                "Pixel", "Android 15", "Chrome", 0, 0, 0, 0, maturity(0),
                environment(), null, null, null, null, objectMapper.createArrayNode());
        when(repository.expireDeviceOverride(eq(42L), any(), anyLong())).thenReturn(true);
        when(repository.findDevice(any())).thenReturn(Optional.of(device("OBSERVING", 2)));

        ApiResult<JanusDeviceView> result = service.reportDevice(42L, report);

        assertThat(result.getCode()).isZero();
        verify(repository).expireDeviceOverride(eq(42L), any(), anyLong());
        verify(repository).insertEvaluation(any(), eq("R-STALE-EXPIRE"), any(), any(), eq(null), eq(null), any(), any(),
                eq("BENIGN"), eq("OBSERVING"), eq("OUT_OF_ORDER_REPORT"), anyInt(), eq("janus-server-v1"));
        verify(repository, never()).strategies();
        verify(repository, never()).reserveDailyEvaluation(any(), any(), anyInt());
        verify(repository, never()).upsertDeviceReport(anyLong(), any(), any());
        verify(repository, never()).publishStrategyCommand(any(), anyLong(), any(), any(), any());
    }

    @Test
    void publishedStrategyEvaluatesServerDerivedSignalsAndIgnoresForgedClientDecision() throws Exception {
        long now = System.currentTimeMillis();
        var maturity = maturity(10);
        JanusDeviceReportRequest report = new JanusDeviceReportRequest("R-2", "DEVICE-1", now, now - 1_000,
                now - 10_000, null, "official", null, "BLOCKED", true, "ua", "Android", "Pixel",
                "Android 15", "Chrome", 100, 100, 100, 100, maturity,
                environment(), "forged", 99, null, null, objectMapper.createArrayNode());
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
    void failedStrategyCommandCasReleasesReservedActionQuota() throws Exception {
        long now = System.currentTimeMillis();
        JanusDeviceReportRequest report = new JanusDeviceReportRequest("R-CAS-LOST", "DEVICE-1", now,
                now - 1_000, now - 10_000, null, "official", null, null, false, "ua", "Android",
                "Pixel", "Android 15", "Chrome", 0, 0, 0, 0, maturity(10), environment(),
                null, null, null, null, objectMapper.createArrayNode());
        when(repository.strategies()).thenReturn(List.of(cappedActiveStrategy()));
        when(repository.findDevice(any())).thenReturn(Optional.of(device("OBSERVING", 1)));
        when(repository.publishStrategyCommand(any(), anyLong(), any(), any(), any())).thenReturn(false);

        ApiResult<JanusDeviceView> result = service.reportDevice(42L, report);

        assertThat(result.getCode()).isZero();
        verify(repository).reserveDailyEvaluation("K6-CAPPED", "REVERSAL_IMMEDIATE", 1);
        verify(repository).releaseDailyEvaluation("K6-CAPPED", "REVERSAL_IMMEDIATE");
        verify(outbox, never()).publish(any(), any(), eq("JANUS_STRATEGY_COMMAND_PUBLISHED"), any());
    }

    @Test
    void duplicateReportReleasesItsAtomicDailyQuotaReservation() throws Exception {
        long now = System.currentTimeMillis();
        JanusDeviceReportRequest report = new JanusDeviceReportRequest("R-DUP", "DEVICE-1", now, now - 1_000,
                now - 10_000, null, "official", null, null, false, "ua", "Android", "Pixel",
                "Android 15", "Chrome", 0, 0, 0, 0, maturity(10),
                environment(), null, null, null, null, objectMapper.createArrayNode());
        JanusStrategyView active = cappedActiveStrategy();
        when(repository.strategies()).thenReturn(List.of(active));
        when(repository.insertEvaluation(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), anyInt(), any()))
                .thenReturn(false);
        when(repository.findEvaluationRequestHash(any(), eq("R-DUP")))
                .thenReturn(Optional.empty(), Optional.of(normalizedReportHash(report)));
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
                "Android 15", "Chrome", 0, 0, 0, 0, maturity(10),
                environment(), null, null, null, null, objectMapper.createArrayNode());
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
        verify(outbox).publish(eq("JANUS_DEVICE"), any(), eq("JANUS_DEVICE_COMMAND_ACKED"), any());
    }

    @Test
    void commandAckRejectsOversizedDeviceMessageBeforeMutation() {
        ApiResult<java.util.Map<String, Object>> result = service.acknowledgeCommand(42L,
                new JanusCommandAckRequest("DEVICE-1", 3L, false, null, "x".repeat(501)));

        assertThat(result.getCode()).isEqualTo(422);
        assertThat(result.getMessage()).isEqualTo("JANUS_ACK_INVALID");
        verify(repository, never()).acknowledgeDeviceCommand(anyLong(), any(), anyLong(), eq(false), any());
    }

    @Test
    void archivedStrategyCannotBePhysicallyDeleted() {
        when(repository.findStrategy("K6-ARCHIVED")).thenReturn(Optional.of(strategy("archived", 4)));
        when(repository.deleteStrategy("K6-ARCHIVED", 4L)).thenReturn(true);

        ApiResult<Void> result = service.deleteStrategy("K6-ARCHIVED", "idem-delete-archived",
                new JanusStrategyActionRequest("保留历史", "保留历史", null, 4L, null, null));

        assertThat(result.getCode()).isEqualTo(409);
        assertThat(result.getMessage()).isEqualTo("ONLY_DRAFT_DELETABLE");
        verify(repository, never()).deleteStrategy(any(), anyLong());
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
                "1", "n/a", List.of(new SimpleGrantedAuthority("risk_k6_write"),
                new SimpleGrantedAuthority("risk_k6_senior")));
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

    @Test
    void multiVersionStrategyAuditKeepsRootSnapshotsReasonAndRealUsernameUnderSanitizerLimit() throws Exception {
        var auth = new UsernamePasswordAuthenticationToken(
                "1", "n/a", List.of(new SimpleGrantedAuthority("risk_k6_write"),
                new SimpleGrantedAuthority("risk_k6_senior")));
        auth.setDetails(java.util.Map.of("subjectType", "ADMIN", "username", "ops-user"));
        SecurityContextHolder.getContext().setAuthentication(auth);
        JanusStrategyView before = strategyWithHistory("active", 8, 400);
        JanusStrategyView after = new JanusStrategyView(before.strategyId(), before.name(), before.description(),
                "paused", before.version(), before.priority(), before.owner(), before.scope(), before.ruleTree(),
                before.action(), before.safeguards(), before.rollout(), before.healthConfig(), before.templateKey(),
                before.versions(), before.createdAt(), before.publishedAt(), 9L);
        when(repository.findStrategy("K6-1")).thenReturn(Optional.of(before), Optional.of(after));
        when(repository.updateStrategyLifecycle("K6-1", 8L, "paused", before.version(), before.publishedAt()))
                .thenReturn(true);
        String reason = "\u0001".repeat(496) + "VALID";
        String idempotencyKey = "\\\"".repeat(64);

        ApiResult<JanusStrategyView> result = service.lifecycle("K6-1", "pause", idempotencyKey,
                new JanusStrategyActionRequest(reason, reason, null, 8L, null, null));

        assertThat(result.getCode()).isZero();
        ArgumentCaptor<AuditLogWriteRequest> captor = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
        verify(audit).recordRequired(captor.capture());
        AuditLogWriteRequest write = captor.getValue();
        String detailJson = objectMapper.writeValueAsString(write.getDetail());
        var detail = objectMapper.readTree(detailJson);
        String safeDetailJson = new AuditLogSanitizer(objectMapper).toSafeJson(write.getDetail());
        var safeDetail = objectMapper.readTree(safeDetailJson);
        String storedReason = detail.path("reason").asText();
        assertThat(write.getActorUsername()).isEqualTo("ops-user");
        assertThat(detailJson.length()).isLessThan(4096);
        assertThat(storedReason).isNotEmpty();
        assertThat(reason).startsWith(storedReason);
        assertThat(safeDetail.path("truncated").asBoolean(false)).isFalse();
        assertThat(safeDetailJson.length()).isLessThanOrEqualTo(4096);
        assertThat(safeDetail.path("reason").asText()).isEqualTo(storedReason);
        assertThat(safeDetail.path("before").path("status").asText()).isEqualTo("active");
        assertThat(safeDetail.path("after").path("status").asText()).isEqualTo("paused");
        assertThat(detail.path("before").path("status").asText()).isEqualTo("active");
        assertThat(detail.path("after").path("status").asText()).isEqualTo("paused");
        assertThat(detail.findValue("versions")).isNull();
        assertThat(detail.findValue("sourceContext")).isNull();
        assertThat(detail.findValue("hash")).isNull();
        assertThat(detail.findValue("configHash")).isNull();

        AuditLogRecord record = new AuditLogRecord();
        record.setId(77L);
        record.setTraceId("trace-77");
        record.setAction(write.getAction());
        record.setResourceType(write.getResourceType());
        record.setResourceId(write.getResourceId());
        record.setActorId(1L);
        record.setActorUsername(write.getActorUsername());
        record.setDetailJson(detailJson);
        record.setCreatedAt(LocalDateTime.now());
        when(audit.list(any())).thenReturn(List.of(record));

        var auditView = service.audit("strategy", null, 10).getData().get(0);
        assertThat(auditView).containsEntry("actorId", "ops-user")
                .containsEntry("reasonText", storedReason);
        assertThat(objectMapper.valueToTree(auditView.get("beforeSnapshot")).path("status").asText())
                .isEqualTo("active");
        assertThat(objectMapper.valueToTree(auditView.get("afterSnapshot")).path("status").asText())
                .isEqualTo("paused");
    }

    @Test
    void createAndDeleteAuditsAlwaysContainBothRootSnapshots() throws Exception {
        JanusStrategyView created = strategyWithHistory("draft", 1, 250);
        when(repository.createStrategy(any(), any())).thenReturn(created);
        var request = strategyRequest(objectMapper.readTree("""
                        {"mode":"ALL","rules":[{"field":"maturityScore","op":">=","value":60,"label":"成熟度达标"}]}
                        """), objectMapper.createObjectNode(), objectMapper.createObjectNode(), objectMapper.createObjectNode());

        assertThat(service.createStrategy("idem-audit-create", request).getCode()).isZero();

        when(repository.findStrategy("K6-1")).thenReturn(Optional.of(created));
        when(repository.deleteStrategy("K6-1", 1L)).thenReturn(true);
        assertThat(service.deleteStrategy("K6-1", "idem-audit-delete",
                new JanusStrategyActionRequest("删除无效草稿", "删除无效草稿", null, 1L, null, null)).getCode()).isZero();

        ArgumentCaptor<AuditLogWriteRequest> captor = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
        verify(audit, org.mockito.Mockito.times(2)).recordRequired(captor.capture());
        var create = objectMapper.valueToTree(captor.getAllValues().get(0).getDetail());
        var delete = objectMapper.valueToTree(captor.getAllValues().get(1).getDetail());
        assertThat(create.has("before")).isTrue();
        assertThat(create.path("before").isObject()).isTrue();
        assertThat(create.path("before").isEmpty()).isTrue();
        assertThat(create.path("after").path("status").asText()).isEqualTo("draft");
        assertThat(delete.path("before").path("status").asText()).isEqualTo("draft");
        assertThat(delete.has("after")).isTrue();
        assertThat(delete.path("after").isObject()).isTrue();
        assertThat(delete.path("after").isEmpty()).isTrue();
        assertThat(objectMapper.writeValueAsString(create).length()).isLessThan(4096);
        assertThat(objectMapper.writeValueAsString(delete).length()).isLessThan(4096);
    }

    private JanusDeviceView device(String status, long version) {
        return new JanusDeviceView("SID-1", "D-1", 1L, System.currentTimeMillis() - 1_000, 1L, 2,
                null, "official", null, status, null, null, "system", false, null,
                60, 65, 10, 80, null, "Android", "Pixel", "Android 15", "Chrome",
                objectMapper.createObjectNode(), objectMapper.createObjectNode(), null, null,
                objectMapper.createObjectNode(), objectMapper.createObjectNode(), objectMapper.createObjectNode(),
                null, null, null, objectMapper.createArrayNode(), version);
    }

    private JanusDeviceView deviceWithEscapes(String status, long version) {
        String text = "\\\"".repeat(64);
        return new JanusDeviceView("SID-1", text, 1L, System.currentTimeMillis() - 1_000, 1L, 2,
                null, "official", text, status, text, text, text, false, text,
                60, 65, 10, 80, null, "Android", "Pixel", "Android 15", "Chrome",
                objectMapper.createObjectNode(), objectMapper.createObjectNode(), text, 9,
                objectMapper.createObjectNode(), objectMapper.createObjectNode(), objectMapper.createObjectNode(),
                text, text, null, objectMapper.createArrayNode(), version);
    }

    private com.fasterxml.jackson.databind.node.ObjectNode maturity(int appOpenCount) {
        return objectMapper.createObjectNode()
                .put("appOpenCount", appOpenCount).put("sessionCount", 1)
                .put("foregroundDurationSeconds", 0).put("repeatStreakDays", 0)
                .put("benchmarkViewed", false).put("optimizeDone", false)
                .put("marketViewed", false).put("walletViewed", false);
    }

    private com.fasterxml.jackson.databind.node.ObjectNode environment() {
        return objectMapper.createObjectNode()
                .put("isHeadless", false).put("automationSignalCount", 0)
                .put("fpBlocklistHit", false).put("screenAnomaly", false)
                .put("timezoneMismatch", false).put("languageMismatch", false);
    }

    private String sha256(String value) throws Exception {
        return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    private String normalizedReportHash(JanusDeviceReportRequest request) throws Exception {
        long installAt = request.installAt() == null ? request.reportedAt() : request.installAt();
        long firstSeenAt = request.firstSeenAt() == null ? installAt : Math.min(request.firstSeenAt(), request.reportedAt());
        JanusDeviceReportRequest normalized = new JanusDeviceReportRequest(request.reportId().trim(),
                request.deviceId().trim(), request.reportedAt(), firstSeenAt, installAt, request.inviteCode(),
                request.channel(), request.cohortId(), null, false, request.ua(), request.platform(), request.model(),
                request.osName(), request.browser(), 0, 0, 0, 0, request.maturity(), request.environment(),
                null, null, null, request.latestSession(), objectMapper.createArrayNode());
        return sha256(objectMapper.writeValueAsString(normalized));
    }

    private JanusStrategyView strategy(String status, long version) {
        return new JanusStrategyView("K6-1", "策略一", "测试策略", status, 1, 10, "owner",
                objectMapper.createObjectNode(), objectMapper.createObjectNode(), objectMapper.createObjectNode(),
                objectMapper.createObjectNode(), objectMapper.createObjectNode(), objectMapper.createObjectNode(),
                null, List.of(), 1L, null, version);
    }

    private JanusStrategyView strategyWithHistory(String status, long lockVersion, int historySize) throws Exception {
        List<JanusStrategyVersionView> versions = new ArrayList<>();
        for (int i = 1; i <= historySize; i++) {
            var versionSnapshot = objectMapper.createObjectNode().put("padding", "x".repeat(200)).put("version", i);
            versions.add(new JanusStrategyVersionView(i, "历史版本" + i, "operator-" + i, (long) i,
                    objectMapper.createObjectNode(), objectMapper.createObjectNode(), versionSnapshot, "hash-" + i));
        }
        var scope = objectMapper.createObjectNode();
        var inviteCodes = scope.putArray("inviteCodes");
        var cohortIds = scope.putArray("cohortIds");
        String escapingText = "\\\"".repeat(48);
        for (int i = 0; i < 100; i++) {
            inviteCodes.add(escapingText);
            cohortIds.add(escapingText);
        }
        scope.putArray("channels").add("official").add("ad").add("invite").add("test").add("internal");
        var rollout = objectMapper.createObjectNode().put("percent", 50);
        var rolloutCohorts = rollout.putArray("cohortIds");
        for (int i = 0; i < 100; i++) rolloutCohorts.add(escapingText);
        return new JanusStrategyView("K6-1", "\\\"".repeat(80), "多版本审计测试", status, 7, 10,
                "\\\"".repeat(48),
                scope,
                objectMapper.readTree("""
                        {"mode":"ALL","rules":[{"field":"maturityScore","op":">=","value":60,"label":"成熟度达标"}]}
                        """),
                objectMapper.readTree("{\"type\":\"RECOMMEND\"}"),
                objectMapper.readTree("{\"maxDailyHits\":100}"),
                rollout,
                objectMapper.createObjectNode(), null, versions, 1L, 2L, lockVersion);
    }

    private static final class JsonNodeAssertions {
        private static void assertDeviceMutation(com.fasterxml.jackson.databind.JsonNode detail, String reason,
                                                 String reasonCategory) {
            assertThat(detail.path("reason").asText()).isEqualTo(reason);
            assertThat(detail.path("before").path("status").asText()).isEqualTo("RECOMMENDED");
            assertThat(detail.path("after").path("version").asLong()).isEqualTo(8L);
            assertThat(detail.path("reasonCategory").asText()).isEqualTo(reasonCategory);
        }
    }

    private JanusStrategyView cappedActiveStrategy() throws Exception {
        return new JanusStrategyView("K6-CAPPED", "限额策略", "并发配额验证", "active", 1, 100, "owner",
                objectMapper.createObjectNode(),
                objectMapper.readTree("{\"mode\":\"ALL\",\"rules\":[{\"field\":\"maturityScore\",\"op\":\">=\",\"value\":20}]}"),
                objectMapper.readTree("{\"type\":\"REVERSAL_IMMEDIATE\",\"remoteUrlKey\":\"promo\"}"),
                objectMapper.readTree("{\"maxDailyHits\":1}"), objectMapper.createObjectNode(),
                objectMapper.createObjectNode(), null, List.of(), 1L, 2L, 3L);
    }

    private JanusStrategyUpsertRequest strategyRequest(com.fasterxml.jackson.databind.JsonNode ruleTree,
                                                       com.fasterxml.jackson.databind.JsonNode safeguards,
                                                       com.fasterxml.jackson.databind.JsonNode rollout,
                                                       com.fasterxml.jackson.databind.JsonNode scope) throws Exception {
        return new JanusStrategyUpsertRequest("严格策略", "服务端失败关闭校验", 100, "risk-team", scope,
                ruleTree, objectMapper.readTree("{\"type\":\"RECOMMEND\"}"), safeguards, rollout,
                objectMapper.createObjectNode(), null, 0L, "策略配置验收");
    }
}
