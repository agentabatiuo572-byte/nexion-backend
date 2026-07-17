package ffdd.opsconsole.emergency.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogQueryRequest;
import ffdd.opsconsole.shared.audit.AuditLogRecord;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.common.api.OpsErrorCode;
import ffdd.opsconsole.emergency.domain.EmergencyControlRepository;
import ffdd.opsconsole.emergency.domain.KillSwitchState;
import ffdd.opsconsole.emergency.dto.AutoTriggerConfirmationRequest;
import ffdd.opsconsole.emergency.dto.EmergencyDisableRequest;
import ffdd.opsconsole.emergency.dto.KillSwitchToggleRequest;
import ffdd.opsconsole.shared.seed.OpsReadTimeSeedPolicy;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import ffdd.opsconsole.shared.outbox.EventOutboxService;
import ffdd.opsconsole.treasury.facade.TreasuryCoverageFacade;
import ffdd.opsconsole.treasury.facade.TreasuryCoverageSnapshot;
import ffdd.opsconsole.treasury.facade.TreasuryEmergencySignalFacade;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.HashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

class OpsKillSwitchServiceTest {
    private final FakeEmergencyControlRepository emergencyRepository = new FakeEmergencyControlRepository();
    private final FakeTreasuryCoverageFacade coverageFacade = new FakeTreasuryCoverageFacade();
    private final TreasuryEmergencySignalFacade emergencySignalFacade = mock(TreasuryEmergencySignalFacade.class);
    private final AuditLogService auditLogService = mock(AuditLogService.class);
    private final EventOutboxService outboxService = mock(EventOutboxService.class);
    private final AdminIdempotencyService idempotencyService = fakeIdempotencyService();
    private final OpsKillSwitchService service = new OpsKillSwitchService(
            emergencyRepository, coverageFacade, emergencySignalFacade, auditLogService, outboxService, idempotencyService,
            ffdd.opsconsole.shared.seed.OpsReadTimeSeedPolicy.enabledForDirectConstruction());

    {
        when(emergencySignalFacade.bankRunRedlinePct()).thenReturn(new BigDecimal("40"));
        when(outboxService.publish(anyString(), anyString(), anyString(), any())).thenReturn("event-test");
    }

    @Test
    void matrixHasFiveActiveGatesAndRetiredSunsetGatesWithoutWritingConfig() {
        var result = service.matrix();

        assertThat(result.getCode()).isZero();
        assertThat(result.getData()).containsEntry("activeGateCount", 5);
        assertThat(result.getData().get("retiredGates").toString()).contains("premium", "nexv2", "points");
        assertThat(emergencyRepository.settings).isEmpty();
    }

    @Test
    void disabledReadTimeSeedsExposeJ1EnumMatrixWithoutWritingConfig() {
        OpsKillSwitchService realOnlyService = new OpsKillSwitchService(
                emergencyRepository,
                coverageFacade,
                emergencySignalFacade,
                mock(AuditLogService.class),
                mock(EventOutboxService.class),
                fakeIdempotencyService(),
                OpsReadTimeSeedPolicy.disabledForDirectConstruction());

        var result = realOnlyService.matrix();

        assertThat(result.getCode()).isZero();
        assertThat(result.getData()).containsEntry("activeGateCount", 5);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> activeGates = (List<Map<String, Object>>) result.getData().get("activeGates");
        assertThat(activeGates)
                .extracting(gate -> String.valueOf(gate.get("key")))
                .containsExactly("withdraw", "staking", "genesis", "exchange", "trial");
        assertThat(activeGates)
                .allSatisfy(gate -> assertThat(gate).containsEntry("enabled", true));
        assertThat(detailMap(result.getData().get("stats")))
                .containsEntry("liveGateCount", 5L)
                .containsEntry("killedGateCount", 0L);
        assertThat(emergencyRepository.settings).isEmpty();
    }

    @Test
    @SuppressWarnings("unchecked")
    void allOperatorAlertSnapshotExposesStatusWithoutConfigurationControls() {
        emergencyRepository.settings.put("killswitch.exchange", "disabled");
        emergencyRepository.settings.put("emergency.killswitch.exchange.emergency", "true");
        emergencyRepository.settings.put("emergency.killswitch.exchange.auto-confirm.pending", "true");
        emergencyRepository.settings.put("emergency.killswitch.exchange.auto-confirm.incidentId", "J1-AUTO-secret");
        emergencyRepository.settings.put("emergency.killswitch.exchange.auto-confirm.ruleId", "maturityGap");
        emergencyRepository.settings.put("emergency.killswitch.exchange.auto-confirm.signalValue", "75000");
        emergencyRepository.settings.put("emergency.killswitch.exchange.auto-confirm.threshold", "50000");
        emergencyRepository.settings.put("emergency.killswitch.exchange.auto-confirm.triggeredAt", "2026-07-13T19:23:15");
        emergencyRepository.settings.put("emergency.killswitch.exchange.auto-confirm.dueAt", "2026-07-13T19:53:15");

        var result = service.alerts();
        List<Map<String, Object>> gates = (List<Map<String, Object>>) result.getData().get("activeGates");
        List<Map<String, Object>> confirmations = (List<Map<String, Object>>) result.getData().get("autoConfirmations");

        assertThat(result.getCode()).isZero();
        assertThat(gates).hasSize(5);
        assertThat(gates).allSatisfy(gate -> assertThat(gate).containsOnlyKeys(
                "key", "name", "enabled", "emergency", "lastChange"));
        assertThat(confirmations).singleElement().satisfies(row -> assertThat(row)
                .containsOnlyKeys("key", "name", "overdue")
                .doesNotContainKeys("incidentId", "ruleId", "signalValue", "threshold", "triggeredAt", "dueAt"));
        assertThat(result.getData()).doesNotContainKeys("emergencySla", "autoRules", "coverage");
    }

    @Test
    @SuppressWarnings("unchecked")
    void matrixDisplaysTheSameCurrentB5RedlineUsedByR1Evaluation() {
        when(emergencySignalFacade.bankRunRedlinePct()).thenReturn(new BigDecimal("55"));

        var result = service.matrix();
        List<Map<String, Object>> rules = (List<Map<String, Object>>) result.getData().get("autoRules");

        assertThat(rules).filteredOn(row -> "withdrawSurge".equals(row.get("id")))
                .singleElement()
                .satisfies(row -> assertThat(row)
                        .containsEntry("thr", "55")
                        .containsEntry("configKey", "risk.bankrun-red-pct")
                        .satisfies(value -> assertThat(value.get("cond").toString()).contains("55%")));
    }

    @Test
    void missingGateSettingsMatchTheSharedDownstreamDefaultForEveryJ1Gate() {
        var result = service.matrix();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> gates = (List<Map<String, Object>>) result.getData().get("activeGates");
        boolean downstreamDefault = ffdd.opsconsole.emergency.domain.KillSwitchState.enabled(
                Optional.empty(), Optional.empty());

        assertThat(downstreamDefault).isTrue();
        assertThat(gates)
                .extracting(gate -> gate.get("enabled"))
                .containsOnly(downstreamDefault);
    }

    @Test
    void matrixExposesOnlyTheCurrentAutoConfirmationWindowAndB1RecoveryGate() {
        var result = service.matrix();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) result.getData().get("emergencySla");
        assertThat(rows).extracting(row -> row.get("id"))
                .containsExactly("autoConfirmMins", "recoverGate");
        assertThat(rows).extracting(row -> row.get("v"))
                .containsExactly("30", "85");
    }

    @Test
    void maturityGapRuleRejectsFreeTextAndPersistsCanonicalUsdtNumber() {
        var invalid = service.updateAutoRule(
                "maturityGap",
                "idem-j1-rule-invalid",
                new ffdd.opsconsole.emergency.dto.EmergencyConfigUpdateRequest("abc", "invalid threshold", "superadmin"));
        var valid = service.updateAutoRule(
                "maturityGap",
                "idem-j1-rule-valid",
                new ffdd.opsconsole.emergency.dto.EmergencyConfigUpdateRequest("50000.00", "valid threshold", "superadmin"));

        assertThat(invalid.getCode()).isEqualTo(OpsErrorCode.VALIDATION_FAILED.httpStatus());
        assertThat(invalid.getMessage()).isEqualTo("AUTO_RULE_THRESHOLD_MUST_BE_POSITIVE_USDT");
        assertThat(valid.getCode()).isZero();
        assertThat(emergencyRepository.settings).containsEntry("emergency.autorule.maturityGap", "50000");
    }

    @Test
    void matrixUsesAuditBackedCoverageBlockCount() {
        when(auditLogService.countByActionAndResourceType(
                "J1_COVERAGE_RESTORE_BLOCKED", "KILL_SWITCH")).thenReturn(3L);

        var result = service.matrix();

        assertThat(detailMap(result.getData().get("stats")))
                .containsEntry("coverageBlockedCount", 3L);
    }

    @Test
    void automaticTriggerDisablesAnEnabledGateOnceAndMarksEmergency() {
        boolean first = service.autoDisable(
                "exchange", "maturityGap", new BigDecimal("75000"), new BigDecimal("50000"));
        boolean second = service.autoDisable(
                "exchange", "maturityGap", new BigDecimal("75000"), new BigDecimal("50000"));

        assertThat(first).isTrue();
        assertThat(second).isFalse();
        assertThat(emergencyRepository.settings)
                .containsEntry("killswitch.exchange", "disabled")
                .containsEntry("emergency.killswitch.exchange.emergency", "true");
        assertThat(emergencyRepository.settings.get("emergency.killswitch.exchange.lastChange"))
                .endsWith("自动触发 对账缺口");
        ArgumentCaptor<AuditLogWriteRequest> captor = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
        verify(auditLogService).recordRequired(captor.capture());
        verify(outboxService).publish(
                org.mockito.ArgumentMatchers.eq("KILL_SWITCH"),
                org.mockito.ArgumentMatchers.eq("exchange"),
                org.mockito.ArgumentMatchers.eq("J1_KILLSWITCH_CHANGED"),
                any());
        assertThat(detailMap(captor.getValue().getDetail()))
                .containsEntry("pendingAutoConfirmation", true)
                .containsEntry("autoConfirmMins", 30)
                .containsKey("confirmationDueAt");
        assertThat(pendingAutoConfirmations(service.matrix().getData()))
                .singleElement()
                .satisfies(row -> assertThat(row)
                        .containsEntry("key", "exchange")
                        .containsEntry("ruleId", "maturityGap")
                        .containsKey("incidentId")
                        .containsEntry("overdue", false));
    }

    @Test
    void manualDisableCannotEraseAnAutomaticPendingIncident() {
        service.autoDisable("exchange", "maturityGap", new BigDecimal("75000"), new BigDecimal("50000"));

        var result = service.toggle(
                "exchange",
                "idem-manual-over-auto",
                new KillSwitchToggleRequest(
                        "disabled", "仍需保持自动关停并完成补录", "risk-lead", "安全事件", null));

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus());
        assertThat(result.getMessage()).isEqualTo("AUTO_CONFIRMATION_REQUIRED");
        assertThat(emergencyRepository.settings)
                .containsEntry("killswitch.exchange", "disabled")
                .containsEntry("emergency.killswitch.exchange.emergency", "true")
                .containsEntry("emergency.killswitch.exchange.auto-confirm.pending", "true");
    }

    @Test
    void sameStateToggleIsRejectedInsteadOfRewritingTheAuditClock() {
        var result = service.toggle(
                "trial",
                "idem-same-state",
                new KillSwitchToggleRequest("enabled", "重复恢复请求不应制造虚假变更", null, null, "superadmin"));

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus());
        assertThat(result.getMessage()).isEqualTo("KILL_SWITCH_STATE_UNCHANGED");
        assertThat(emergencyRepository.settings).doesNotContainKey("emergency.killswitch.trial.lastChange");
    }

    @Test
    void automaticTriggerReassertsDisabledAfterARestoreSlipsInBeforePendingMarker() {
        emergencyRepository.restoreOnceAfterDisable = true;

        boolean changed = service.autoDisable(
                "withdraw", "withdrawSurge", new BigDecimal("40"), new BigDecimal("40"));

        assertThat(changed).isTrue();
        assertThat(emergencyRepository.settings)
                .containsEntry("killswitch.withdraw", "disabled")
                .containsEntry("emergency.killswitch.withdraw.auto-confirm.pending", "true");
    }

    @Test
    void orphanedAutomaticDisableBlocksRecoveryEvenWhenPendingRowIsMissing() {
        emergencyRepository.settings.put("killswitch.exchange", "disabled");
        emergencyRepository.settings.put("emergency.killswitch.exchange.emergency", "true");
        emergencyRepository.settings.put(
                "emergency.killswitch.exchange.lastChange",
                "刚刚 · system:j1-auto-trigger / 执行门槛");

        var result = service.toggle(
                "exchange",
                "idem-orphan-recovery",
                new KillSwitchToggleRequest("enabled", "完成对账后尝试恢复兑换业务", null, null, "superadmin"));

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus());
        assertThat(result.getMessage()).isEqualTo("AUTO_CONFIRMATION_REQUIRED");
        assertThat(emergencyRepository.settings).containsEntry("killswitch.exchange", "disabled");
    }

    @Test
    void repairsOrphanedAutomaticDisableFromTheOriginalAuditEvidenceOnlyOnce() {
        emergencyRepository.settings.put("killswitch.exchange", "disabled");
        emergencyRepository.settings.put("emergency.killswitch.exchange.emergency", "true");
        emergencyRepository.settings.put(
                "emergency.killswitch.exchange.lastChange",
                "刚刚 · system:j1-auto-trigger / 执行门槛");
        AuditLogRecord trigger = new AuditLogRecord();
        trigger.setId(904L);
        trigger.setAction("J1_AUTO_KILLSWITCH_TRIGGERED");
        trigger.setResourceType("KILL_SWITCH");
        trigger.setResourceId("exchange");
        trigger.setCreatedAt(LocalDateTime.parse("2026-07-13T19:23:15"));
        trigger.setDetailJson("""
                {"switchKey":"exchange","incidentId":"J1-AUTO-original","ruleId":"maturityGap",\
                 "signalValue":75000,"threshold":50000,"confirmationDueAt":"2026-07-13T19:53:15"}
                """);
        when(auditLogService.list(any(AuditLogQueryRequest.class))).thenReturn(List.of(trigger));

        int first = service.repairAutoConfirmationOrphans();
        int second = service.repairAutoConfirmationOrphans();

        assertThat(first).isEqualTo(1);
        assertThat(second).isZero();
        assertThat(emergencyRepository.settings)
                .containsEntry("emergency.killswitch.exchange.auto-confirm.pending", "true")
                .containsEntry("emergency.killswitch.exchange.auto-confirm.incidentId", "J1-AUTO-original")
                .containsEntry("emergency.killswitch.exchange.auto-confirm.ruleId", "maturityGap")
                .containsEntry("emergency.killswitch.exchange.auto-confirm.signalValue", "75000")
                .containsEntry("emergency.killswitch.exchange.auto-confirm.threshold", "50000")
                .containsEntry("emergency.killswitch.exchange.auto-confirm.dueAt", "2026-07-13T19:53:15");
        assertThat(pendingAutoConfirmations(service.matrix().getData()))
                .singleElement()
                .satisfies(row -> assertThat(row)
                        .containsEntry("key", "exchange")
                        .containsEntry("incidentId", "J1-AUTO-original")
                        .containsEntry("overdue", true));
        verify(auditLogService, times(1)).recordRequired(any(AuditLogWriteRequest.class));
    }

    @Test
    void orphanRepairStillFailsClosedWhenSignalSnapshotIsUnavailable() {
        emergencyRepository.settings.put("killswitch.exchange", "disabled");
        emergencyRepository.settings.put("emergency.killswitch.exchange.emergency", "true");
        emergencyRepository.settings.put(
                "emergency.killswitch.exchange.lastChange",
                "07-13 19:53 · system:j1-auto-trigger · 自动触发 对账缺口");
        when(emergencySignalFacade.snapshot()).thenThrow(new IllegalStateException("signal unavailable"));

        int repaired = service.repairAutoConfirmationOrphans();

        assertThat(repaired).isOne();
        assertThat(emergencyRepository.settings)
                .containsEntry("emergency.killswitch.exchange.auto-confirm.pending", "true")
                .containsEntry("emergency.killswitch.exchange.auto-confirm.signalValue", "0")
                .containsEntry("emergency.killswitch.exchange.auto-confirm.threshold", "50000");
    }

    @Test
    void startupRepairReplacesPermanentJustNowLabelsWithoutTouchingCurrentRows() {
        emergencyRepository.settings.put(
                "emergency.killswitch.exchange.lastChange",
                "刚刚 · system:j1-auto-trigger / 执行门槛");
        emergencyRepository.settings.put(
                "emergency.killswitch.trial.lastChange",
                "07-13 20:05 · superadmin · 人工切换");

        int repaired = service.repairLegacyLastChangeLabels();

        assertThat(repaired).isOne();
        assertThat(emergencyRepository.settings.get("emergency.killswitch.exchange.lastChange"))
                .doesNotStartWith("刚刚")
                .contains("system:j1-auto-trigger", "历史状态切换");
        assertThat(emergencyRepository.settings.get("emergency.killswitch.trial.lastChange"))
                .isEqualTo("07-13 20:05 · superadmin · 人工切换");
    }

    @Test
    void concurrentAutomaticTriggersCreateOnlyOneIncidentAndOneAudit() throws Exception {
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> {
                ready.countDown();
                start.await();
                return service.autoDisable(
                        "withdraw", "withdrawSurge", new BigDecimal("45"), new BigDecimal("40"));
            });
            var second = executor.submit(() -> {
                ready.countDown();
                start.await();
                return service.autoDisable(
                        "withdraw", "withdrawSurge", new BigDecimal("45"), new BigDecimal("40"));
            });
            ready.await();
            start.countDown();

            assertThat(List.of(first.get(), second.get())).containsExactlyInAnyOrder(true, false);
            verify(auditLogService, times(1)).recordRequired(any(AuditLogWriteRequest.class));
            assertThat(emergencyRepository.settings)
                    .containsEntry("killswitch.withdraw", "disabled")
                    .containsEntry("emergency.killswitch.withdraw.auto-confirm.pending", "true");
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void autoTriggerConfirmationIsAtomicAndClearsThePendingWorkItem() {
        service.autoDisable("exchange", "maturityGap", new BigDecimal("75000"), new BigDecimal("50000"));
        String incidentId = emergencyRepository.settings.get(
                "emergency.killswitch.exchange.auto-confirm.incidentId");

        var confirmed = service.confirmAutoTrigger(
                "exchange",
                "idem-j1-auto-confirm",
                new AutoTriggerConfirmationRequest(
                        incidentId, "keep_disabled", "已完成账本和钱包余额复核，继续保持关停", "risk-lead"));
        var duplicate = service.confirmAutoTrigger(
                "exchange",
                "idem-j1-auto-confirm-duplicate",
                new AutoTriggerConfirmationRequest(
                        incidentId, "recommend_restore", "重复补录不应覆盖第一次确认结果", "risk-lead"));

        assertThat(confirmed.getCode()).isZero();
        assertThat(pendingAutoConfirmations(confirmed.getData())).isEmpty();
        assertThat(emergencyRepository.settings)
                .containsEntry("emergency.killswitch.exchange.auto-confirm.pending", "false")
                .containsEntry("emergency.killswitch.exchange.auto-confirm.decision", "keep_disabled");
        assertThat(duplicate.getCode()).isEqualTo(409);
        assertThat(duplicate.getMessage()).isEqualTo("AUTO_CONFIRMATION_ALREADY_COMPLETED");
        ArgumentCaptor<AuditLogWriteRequest> audits = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
        verify(auditLogService, times(2)).recordRequired(audits.capture());
        assertThat(audits.getAllValues())
                .extracting(value -> detailMap(value.getDetail()).get("incidentId"))
                .containsOnly(emergencyRepository.settings.get("emergency.killswitch.exchange.auto-confirm.incidentId"));
    }

    @Test
    void staleIncidentCannotConfirmANewerAutoTriggerForTheSameGate() {
        service.autoDisable("exchange", "maturityGap", new BigDecimal("75000"), new BigDecimal("50000"));
        String incidentA = emergencyRepository.settings.get(
                "emergency.killswitch.exchange.auto-confirm.incidentId");
        var firstConfirmation = service.confirmAutoTrigger(
                "exchange",
                "idem-j1-auto-confirm-a",
                new AutoTriggerConfirmationRequest(
                        incidentA, "keep_disabled", "事件A已经完成复核并保持关停", "risk-lead"));
        assertThat(firstConfirmation.getCode()).isZero();

        emergencyRepository.settings.put("killswitch.exchange", "enabled");
        emergencyRepository.settings.put("emergency.killswitch.exchange.emergency", "false");
        service.autoDisable("exchange", "maturityGap", new BigDecimal("80000"), new BigDecimal("50000"));
        String incidentB = emergencyRepository.settings.get(
                "emergency.killswitch.exchange.auto-confirm.incidentId");
        assertThat(incidentB).isNotEqualTo(incidentA);

        var staleConfirmation = service.confirmAutoTrigger(
                "exchange",
                "idem-j1-auto-confirm-stale-a",
                new AutoTriggerConfirmationRequest(
                        incidentA, "recommend_restore", "旧页面不能把事件A的结论写到事件B", "risk-lead"));

        assertThat(staleConfirmation.getCode()).isEqualTo(409);
        assertThat(staleConfirmation.getMessage()).isEqualTo("AUTO_CONFIRMATION_INCIDENT_MISMATCH");
        assertThat(emergencyRepository.settings)
                .containsEntry("emergency.killswitch.exchange.auto-confirm.incidentId", incidentB)
                .containsEntry("emergency.killswitch.exchange.auto-confirm.pending", "true");
    }

    @Test
    void pendingAutoConfirmationMustBeCompletedBeforeAResumeCanRun() {
        service.autoDisable("exchange", "maturityGap", new BigDecimal("75000"), new BigDecimal("50000"));

        var resume = service.toggle(
                "exchange", "idem-j1-resume-before-confirm",
                new KillSwitchToggleRequest("enabled", "attempt restore before completing incident review", "superadmin"));

        assertThat(resume.getCode()).isEqualTo(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus());
        assertThat(resume.getMessage()).isEqualTo("AUTO_CONFIRMATION_REQUIRED");
        assertThat(emergencyRepository.settings).containsEntry("killswitch.exchange", "disabled");
    }

    @Test
    void linkedDomainCannotBypassPendingAutoConfirmationBeforeRecovery() {
        service.autoDisable("exchange", "maturityGap", new BigDecimal("75000"), new BigDecimal("50000"));

        var blocked = service.restoreFromLinkedDomain(
                "exchange", "market-operator", "G2 请求恢复兑换", "G2");

        assertThat(blocked.getCode()).isEqualTo(409);
        assertThat(blocked.getMessage()).isEqualTo("AUTO_CONFIRMATION_REQUIRED");
        assertThat(emergencyRepository.settings).containsEntry("killswitch.exchange", "disabled");
    }

    @Test
    void linkedDomainCoverageRejectionIsAuditedAsBlocked() {
        emergencyRepository.settings.put("killswitch.exchange", "disabled");
        coverageFacade.snapshot = new TreasuryCoverageSnapshot(new BigDecimal("80.00"), new BigDecimal("85.00"));

        var result = service.restoreFromLinkedDomain(
                "exchange", "market-operator", "G2 请求恢复兑换", "G2");

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.COVERAGE_BELOW_REDLINE.httpStatus());
        ArgumentCaptor<AuditLogWriteRequest> audits = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
        verify(auditLogService).recordRequired(audits.capture());
        assertThat(audits.getValue().getAction()).isEqualTo("J1_COVERAGE_RESTORE_BLOCKED");
        assertThat(audits.getValue().getResult()).isEqualTo("BLOCKED");
    }

    @Test
    void linkedDomainGateChangeIsDeduplicatedAtTheJ1WriteBoundary() {
        var first = service.changeFromLinkedDomain(
                "exchange", false, "market-operator", "G2 发现风险后关闭兑换出口", "G2", "idem-g2-linked-gate");
        var replay = service.changeFromLinkedDomain(
                "exchange", false, "market-operator", "G2 发现风险后关闭兑换出口", "G2", "idem-g2-linked-gate");

        assertThat(first.getCode()).isZero();
        assertThat(replay.getCode()).isZero();
        assertThat(emergencyRepository.settings).containsEntry("killswitch.exchange", "disabled");
        verify(auditLogService, times(1)).recordRequired(any(AuditLogWriteRequest.class));
    }

    @Test
    void linkedDomainRejectsASecondDisableWithAnotherIdempotencyKey() {
        var first = service.changeFromLinkedDomain(
                "exchange", false, "market-operator", "G2 风险事件关闭兑换", "G2", "idem-g2-linked-first");
        var second = service.changeFromLinkedDomain(
                "exchange", false, "market-operator", "G2 重复关闭不应产生虚假成功", "G2", "idem-g2-linked-second");

        assertThat(first.getCode()).isZero();
        assertThat(second.getCode()).isEqualTo(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus());
        assertThat(second.getMessage()).isEqualTo("KILL_SWITCH_STATE_UNCHANGED");
        verify(outboxService, times(1)).publish(
                org.mockito.ArgumentMatchers.eq("KILL_SWITCH"),
                org.mockito.ArgumentMatchers.eq("exchange"),
                org.mockito.ArgumentMatchers.eq("J1_KILLSWITCH_CHANGED"),
                any());
    }

    @Test
    void manualDisableRejectsAnAtomicCasLossWithoutBroadcastingSuccess() {
        emergencyRepository.rejectNextDisableAsConcurrentWinner = true;

        var result = service.toggle(
                "exchange",
                "idem-j1-cas-loss",
                new KillSwitchToggleRequest(
                        "disabled", "并发请求已先一步关闭兑换闸", "risk-lead", "安全事件", null));

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus());
        assertThat(result.getMessage()).isEqualTo("KILL_SWITCH_STATE_UNCHANGED");
        assertThat(emergencyRepository.settings).containsEntry("killswitch.exchange", "disabled");
        verify(outboxService, never()).publish(
                org.mockito.ArgumentMatchers.eq("KILL_SWITCH"),
                org.mockito.ArgumentMatchers.eq("exchange"),
                org.mockito.ArgumentMatchers.eq("J1_KILLSWITCH_CHANGED"),
                any());
    }

    @Test
    void linkedRecoveryRejectsAnAtomicCasLossWithoutBroadcastingSuccess() {
        emergencyRepository.settings.put("killswitch.exchange", "disabled");
        emergencyRepository.rejectNextRestoreAsConcurrentWinner = true;

        var result = service.changeFromLinkedDomain(
                "exchange", true, "market-operator", "G2 对账完成后恢复兑换", "G2", "idem-g2-linked-restore-cas-loss");

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus());
        assertThat(result.getMessage()).isEqualTo("KILL_SWITCH_STATE_UNCHANGED");
        assertThat(emergencyRepository.settings).containsEntry("killswitch.exchange", "enabled");
        verify(outboxService, never()).publish(
                org.mockito.ArgumentMatchers.eq("KILL_SWITCH"),
                org.mockito.ArgumentMatchers.eq("exchange"),
                org.mockito.ArgumentMatchers.eq("J1_KILLSWITCH_CHANGED"),
                any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void j4RollbackRequiresTheExecutionToStillOwnTheGateState() {
        var disabled = service.changeFromJ4Execution(
                "withdraw", "j4-operator", "监管事件立即止血", "EXEC-1");
        Map<String, Object> updated = (Map<String, Object>) disabled.getData().get("updated");
        String ownershipToken = String.valueOf(updated.get("ownershipToken"));
        emergencyRepository.settings.put(
                "emergency.killswitch.withdraw.lastChange", "其他事故重新关停");

        var rollback = service.restoreFromJ4Execution(
                "withdraw", "j4-operator", "监管事件解除后回滚", "EXEC-1", ownershipToken);

        assertThat(rollback.getCode()).isEqualTo(409);
        assertThat(rollback.getMessage()).isEqualTo("J4_GATE_OWNERSHIP_LOST");
        assertThat(emergencyRepository.settings).containsEntry("killswitch.withdraw", "disabled");
    }

    @Test
    @SuppressWarnings("unchecked")
    void j4RollbackRestoresTheGateWhenOwnershipStillMatches() {
        var disabled = service.changeFromJ4Execution(
                "withdraw", "j4-operator", "监管事件立即止血", "EXEC-2");
        Map<String, Object> updated = (Map<String, Object>) disabled.getData().get("updated");

        var rollback = service.restoreFromJ4Execution(
                "withdraw", "j4-operator", "监管事件解除后回滚", "EXEC-2",
                String.valueOf(updated.get("ownershipToken")));

        assertThat(rollback.getCode()).isZero();
        assertThat(emergencyRepository.settings).containsEntry("killswitch.withdraw", "enabled");
    }

    @Test
    void replayingTheSameToggleIdempotencyKeyDoesNotWriteOrAuditTwice() {
        var request = new KillSwitchToggleRequest(
                "disabled", "withdraw incident requires immediate shutdown", "risk-lead", "安全事件", null);

        var first = service.toggle("withdraw", "idem-j1-replay", request);
        var replay = service.toggle("withdraw", "idem-j1-replay", request);

        assertThat(first.getCode()).isZero();
        assertThat(replay.getCode()).isZero();
        verify(auditLogService, times(1)).recordRequired(any(AuditLogWriteRequest.class));
    }

    @Test
    void auditActorUsesTheAuthenticatedIdentityInsteadOfTheRequestBody() {
        var authentication = new UsernamePasswordAuthenticationToken("42", "n/a", List.of());
        authentication.setDetails(Map.of("username", "authenticated-risk-lead"));
        SecurityContextHolder.getContext().setAuthentication(authentication);
        try {
            var result = service.toggle(
                    "withdraw", "idem-j1-actor",
                    new KillSwitchToggleRequest(
                            "disabled", "withdraw incident requires immediate shutdown",
                            "spoofed-superadmin", "安全事件", null));

            assertThat(result.getCode()).isZero();
            ArgumentCaptor<AuditLogWriteRequest> captor = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
            verify(auditLogService).recordRequired(captor.capture());
            assertThat(captor.getValue().getActorUsername()).isEqualTo("authenticated-risk-lead");
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    @Test
    void overdueAutoTriggerCreatesAThrottledOperationalReminder() {
        service.autoDisable("exchange", "maturityGap", new BigDecimal("75000"), new BigDecimal("50000"));
        emergencyRepository.settings.put(
                "emergency.killswitch.exchange.auto-confirm.dueAt",
                LocalDateTime.now().minusMinutes(1).toString());

        int first = service.remindOverdueAutoConfirmations();
        int second = service.remindOverdueAutoConfirmations();

        assertThat(first).isEqualTo(1);
        assertThat(second).isZero();
        assertThat(pendingAutoConfirmations(service.matrix().getData()))
                .singleElement()
                .satisfies(row -> assertThat(row).containsEntry("overdue", true));
    }

    @Test
    void restoreBelowB1RedlineReturns422() {
        emergencyRepository.settings.put("killswitch.withdraw", "disabled");
        coverageFacade.snapshot = new TreasuryCoverageSnapshot(new BigDecimal("80.00"), new BigDecimal("85.00"));

        var result = service.toggle(
                "withdraw",
                "idem-j1",
                new KillSwitchToggleRequest("enabled", "restore withdrawals", "superadmin"));

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.COVERAGE_BELOW_REDLINE.httpStatus());
    }

    @Test
    void restoreTrialDoesNotRequireB1Redline() {
        emergencyRepository.settings.put("killswitch.trial", "disabled");
        coverageFacade.snapshot = new TreasuryCoverageSnapshot(new BigDecimal("80.00"), new BigDecimal("85.00"));

        var result = service.toggle(
                "trial",
                "idem-j1-trial-restore",
                new KillSwitchToggleRequest("enabled", "restore trial benefit gate", "superadmin"));

        assertThat(result.getCode()).isZero();
        assertThat(emergencyRepository.settings).containsEntry("killswitch.trial", "enabled");
    }

    @Test
    void successfulFinancialGateRestoreAuditsTheExactCoverageSnapshotUsedForAdmission() {
        emergencyRepository.settings.put("killswitch.withdraw", "disabled");
        coverageFacade.snapshot = new TreasuryCoverageSnapshot(new BigDecimal("110.00"), new BigDecimal("85.00"));

        var result = service.toggle(
                "withdraw", "idem-j1-restore-audit",
                new KillSwitchToggleRequest("enabled", "restore withdrawals", "superadmin"));

        assertThat(result.getCode()).isZero();
        ArgumentCaptor<AuditLogWriteRequest> captor = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
        verify(auditLogService).recordRequired(captor.capture());
        assertThat(detailMap(captor.getValue().getDetail()))
                .containsEntry("coverageRatio", new BigDecimal("110.00"))
                .containsEntry("redlinePct", new BigDecimal("85.00"));
    }

    @Test
    void disableActiveGateWritesControlSettingAndAudit() {
        var result = service.toggle(
                "exchange",
                "idem-j1",
                new KillSwitchToggleRequest("disabled", "market incident", "risk-lead", "安全事件", null));

        assertThat(result.getCode()).isZero();
        assertThat(emergencyRepository.settings).containsEntry("killswitch.exchange", "disabled");

        ArgumentCaptor<AuditLogWriteRequest> captor = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
        verify(auditLogService).recordRequired(captor.capture());
        assertThat(captor.getValue().getAction()).isEqualTo("J1_KILLSWITCH_TOGGLED");
        assertThat(detailMap(captor.getValue().getDetail())).containsEntry("idempotencyKey", "idem-j1");
    }

    @Test
    void retiredGateCannotBeToggled() {
        var result = service.toggle(
                "premium",
                "idem-j1",
                new KillSwitchToggleRequest("disabled", "old gate", "superadmin"));

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.RETIRED_FEATURE.httpStatus());
    }

    @Test
    void emergencyDisableOnlyAppliesActiveGates() {
        var result = service.emergencyDisable(
                "idem-j1-batch",
                new EmergencyDisableRequest(List.of("withdraw", "trial"), "incident response", "risk-lead",
                        "监管点名", "REG-2026-0713", null));

        assertThat(result.getCode()).isZero();
        assertThat(emergencyRepository.settings)
                .containsEntry("killswitch.withdraw", "disabled")
                .containsEntry("killswitch.trial", "disabled");
        verify(outboxService, times(2)).publish(
                org.mockito.ArgumentMatchers.eq("KILL_SWITCH"),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.eq("J1_KILLSWITCH_CHANGED"),
                org.mockito.ArgumentMatchers.argThat(payload ->
                        payload instanceof Map<?, ?> event
                                && "manual".equals(event.get("trigger"))));
        ArgumentCaptor<AuditLogWriteRequest> audits = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
        verify(auditLogService, times(2)).recordRequired(audits.capture());
        assertThat(audits.getAllValues())
                .extracting(AuditLogWriteRequest::getResourceId)
                .containsExactlyInAnyOrder("withdraw", "trial");
        assertThat(audits.getAllValues())
                .extracting(AuditLogWriteRequest::getAction)
                .containsOnly("J1_EMERGENCY_KILLSWITCH_TRIGGERED");
        assertThat(audits.getAllValues())
                .allSatisfy(audit -> assertThat(detailMap(audit.getDetail()))
                        .containsEntry("trigger", "manual")
                        .containsEntry("operationMode", "batch"));
    }

    @Test
    void killRequiresTriggerBasisAndAssetGatesRequireDispositionPlan() {
        var missingBasis = service.toggle(
                "withdraw", "idem-j1-no-basis",
                new KillSwitchToggleRequest("disabled", "withdraw incident", "risk-lead", null, null));
        var missingPlan = service.toggle(
                "staking", "idem-j1-no-plan",
                new KillSwitchToggleRequest("disabled", "staking incident", "risk-lead", "挤兑风险", null));

        assertThat(missingBasis.getCode()).isEqualTo(OpsErrorCode.VALIDATION_FAILED.httpStatus());
        assertThat(missingBasis.getMessage()).isEqualTo("TRIGGER_BASIS_REQUIRED");
        assertThat(missingPlan.getCode()).isEqualTo(OpsErrorCode.VALIDATION_FAILED.httpStatus());
        assertThat(missingPlan.getMessage()).isEqualTo("DISPOSITION_PLAN_REQUIRED");
        assertThat(emergencyRepository.settings).doesNotContainKeys("killswitch.withdraw", "killswitch.staking");
    }

    @Test
    void writeCommandsEnforceReasonLengthAndTheTriggerBasisEnumeration() {
        var shortReason = service.toggle(
                "withdraw", "idem-j1-short-reason",
                new KillSwitchToggleRequest("disabled", "太短", "risk-lead", "挤兑风险", null));
        var invalidBasis = service.toggle(
                "withdraw", "idem-j1-invalid-basis",
                new KillSwitchToggleRequest("disabled", "valid incident reason", "risk-lead", "自由文本", null));

        assertThat(shortReason.getCode()).isEqualTo(OpsErrorCode.REASON_REQUIRED.httpStatus());
        assertThat(shortReason.getMessage()).isEqualTo("REASON_LENGTH_INVALID");
        assertThat(invalidBasis.getCode()).isEqualTo(OpsErrorCode.VALIDATION_FAILED.httpStatus());
        assertThat(invalidBasis.getMessage()).isEqualTo("TRIGGER_BASIS_INVALID");
    }

    @Test
    void batchKillRequiresRegulatoryContextAndDispositionForAssetGates() {
        var missingContext = service.emergencyDisable(
                "idem-j1-no-context",
                new EmergencyDisableRequest(List.of("withdraw"), "batch incident", "risk-lead",
                        "监管点名", null, null));
        var missingPlan = service.emergencyDisable(
                "idem-j1-batch-no-plan",
                new EmergencyDisableRequest(List.of("genesis"), "batch incident", "risk-lead",
                        "监管点名", "REG-2026-0713", null));

        assertThat(missingContext.getMessage()).isEqualTo("REGULATORY_CONTEXT_REQUIRED");
        assertThat(missingPlan.getMessage()).isEqualTo("DISPOSITION_PLAN_REQUIRED");
    }

    @Test
    void emergencyDisableValidatesTheWholeBatchBeforeWritingAnyGate() {
        assertThatThrownBy(() -> service.emergencyDisable(
                "idem-j1-batch-invalid",
                new EmergencyDisableRequest(List.of("withdraw", "unknown"), "incident response", "risk-lead",
                        "安全事件", "SEC-2026-0713", null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported J1 kill switch key");

        assertThat(emergencyRepository.settings).doesNotContainKey("killswitch.withdraw");
    }

    @Test
    void emergencyDisableRejectsTheWholeBatchWhenAnyGateIsAlreadyDisabled() {
        emergencyRepository.settings.put("killswitch.trial", "disabled");

        var result = service.emergencyDisable(
                "idem-j1-batch-conflict",
                new EmergencyDisableRequest(List.of("withdraw", "trial"), "incident response", "risk-lead",
                        "安全事件", "SEC-2026-0713", null));

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus());
        assertThat(result.getMessage()).isEqualTo("KILL_SWITCH_BATCH_CONFLICT");
        assertThat(emergencyRepository.settings).doesNotContainKey("killswitch.withdraw");
        verify(auditLogService).recordRequired(argThat(request ->
                "J1_EMERGENCY_KILLSWITCH_BLOCKED".equals(request.getAction())
                        && "BLOCKED".equals(request.getResult())));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> detailMap(Object detail) {
        return (Map<String, Object>) detail;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> pendingAutoConfirmations(Map<String, Object> data) {
        return (List<Map<String, Object>>) data.get("autoConfirmations");
    }

    private static AdminIdempotencyService fakeIdempotencyService() {
        AdminIdempotencyService service = mock(AdminIdempotencyService.class);
        Map<String, Object> responses = new HashMap<>();
        when(service.execute(anyString(), anyString(), anyString(), any(), any())).thenAnswer(invocation -> {
            String cacheKey = invocation.getArgument(0) + "|" + invocation.getArgument(1);
            Supplier<?> action = invocation.getArgument(4);
            return responses.computeIfAbsent(cacheKey, ignored -> action.get());
        });
        return service;
    }

    private static final class FakeEmergencyControlRepository implements EmergencyControlRepository {
        private final Map<String, String> settings = new LinkedHashMap<>();
        private boolean restoreOnceAfterDisable;
        private boolean rejectNextDisableAsConcurrentWinner;
        private boolean rejectNextRestoreAsConcurrentWinner;

        @Override
        public void ensureTables() {
        }

        @Override
        public List<Map<String, Object>> geoCountryPolicies() {
            return List.of();
        }

        @Override
        public void upsertGeoCountryPolicy(String countryCode, String countryName, String status, String reason, String operator) {
        }

        @Override
        public List<Map<String, Object>> geoEndpointCatalogs() {
            return List.of();
        }

        @Override
        public Optional<Map<String, Object>> geoEndpointCatalog(String endpointKey) {
            return Optional.empty();
        }

        @Override
        public List<Map<String, Object>> geoEndpointPolicies() {
            return List.of();
        }

        @Override
        public void replaceGeoEndpointPolicies(String endpointKey, String endpointPath, String label, String biz, String domain,
                                               List<String> countryCodes, String source, String reason, String operator) {
        }

        @Override
        public List<Map<String, Object>> geoHits() {
            return List.of();
        }

        @Override
        public Map<String, Integer> geoEndpointHits() {
            return Map.of();
        }

        @Override
        public List<Map<String, Object>> geoEdgeMetrics() {
            return List.of();
        }

        @Override
        public Optional<String> settingValue(String settingKey) {
            return Optional.ofNullable(settings.get(settingKey));
        }

        @Override
        public void upsertSetting(String settingKey, String settingValue, String valueType, String groupCode, String remark, String operator) {
            settings.put(settingKey, settingValue);
        }

        @Override
        public boolean compareAndSetSetting(String settingKey, String expectedValue, String newValue, String operator) {
            if (!settings.containsKey(settingKey) || !java.util.Objects.equals(settings.get(settingKey), expectedValue)) {
                return false;
            }
            settings.put(settingKey, newValue);
            return true;
        }

        @Override
        public synchronized boolean disableKillSwitchIfEnabled(
                String settingKey, String legacySettingKey, String operator) {
            if (rejectNextDisableAsConcurrentWinner) {
                rejectNextDisableAsConcurrentWinner = false;
                settings.put(settingKey, "disabled");
                return false;
            }
            if (!KillSwitchState.enabled(
                    Optional.ofNullable(settings.get(settingKey)),
                    Optional.ofNullable(settings.get(legacySettingKey)))) {
                return false;
            }
            settings.put(settingKey, "disabled");
            if (restoreOnceAfterDisable) {
                restoreOnceAfterDisable = false;
                settings.put(settingKey, "enabled");
            }
            return true;
        }

        @Override
        public synchronized boolean claimMissingAutoConfirmation(
                String pendingSettingKey,
                String gateSettingKey,
                String emergencySettingKey,
                String lastChangeSettingKey,
                String operator) {
            if (settings.containsKey(pendingSettingKey)
                    || KillSwitchState.enabled(Optional.ofNullable(settings.get(gateSettingKey)), Optional.empty())
                    || !"true".equalsIgnoreCase(settings.get(emergencySettingKey))
                    || !String.valueOf(settings.get(lastChangeSettingKey)).contains("system:j1-auto-trigger")) {
                return false;
            }
            settings.put(pendingSettingKey, "true");
            return true;
        }

        @Override
        public synchronized boolean repairLegacyLastChange(String lastChangeSettingKey, String operator) {
            String value = settings.get(lastChangeSettingKey);
            if (value == null || !value.startsWith("刚刚")) {
                return false;
            }
            String actor = value.contains("system:j1-auto-trigger") ? "system:j1-auto-trigger" : "system";
            settings.put(lastChangeSettingKey, "07-13 19:23 · " + actor + " · 历史状态切换");
            return true;
        }

        @Override
        public synchronized boolean completeAutoConfirmation(
                String pendingSettingKey,
                String incidentSettingKey,
                String expectedIncidentId,
                String operator) {
            if (!"true".equals(settings.get(pendingSettingKey))
                    || !java.util.Objects.equals(expectedIncidentId, settings.get(incidentSettingKey))) {
                return false;
            }
            settings.put(pendingSettingKey, "false");
            return true;
        }

        @Override
        public synchronized boolean restoreKillSwitchIfNoPending(
                String settingKey, String pendingSettingKey, String operator) {
            if (rejectNextRestoreAsConcurrentWinner) {
                rejectNextRestoreAsConcurrentWinner = false;
                settings.put(settingKey, "enabled");
                return false;
            }
            if ("true".equals(settings.get(pendingSettingKey))) {
                return false;
            }
            String current = settings.get(settingKey);
            if (current != null && KillSwitchState.parse(current)) {
                return false;
            }
            settings.put(settingKey, "enabled");
            return true;
        }

        @Override
        public Map<String, Object> tamperTrend(LocalDateTime now) {
            return Map.of();
        }

        @Override
        public List<Map<String, Object>> tamperPaths() {
            return List.of();
        }

        @Override
        public List<Map<String, Object>> tamperAccounts() {
            return List.of();
        }

        @Override
        public void createTamperReport(String reportId, String window, boolean masked, String status,
                                       Map<String, Object> payload, String operator, String reason) {
        }

        @Override
        public List<Map<String, Object>> playbooks() {
            return List.of();
        }

        @Override
        public Optional<Map<String, Object>> playbook(String code) {
            return Optional.empty();
        }

        @Override
        public void createPlaybook(String code, String name, String scene, boolean emergency, String sla, String state,
                                   String owner, String notifyCampaignNo, String notifyTemplate, String rollback,
                                   boolean drillRequired, boolean draft, List<Map<String, Object>> sequence,
                                   String operator) {
        }

        @Override
        public void updatePlaybook(String code, String name, String scene, Boolean emergency, String sla, String state,
                                   String owner, String notifyCampaignNo, String notifyTemplate, String rollback,
                                   Boolean drillRequired, String summary, List<Map<String, Object>> sequence,
                                   String operator) {
        }

        @Override
        public void markPlaybookDrilled(String code, LocalDateTime drillAt, String operator) {
        }

        @Override
        public Optional<Map<String, Object>> executionByIdempotencyKey(String code, String idempotencyKey) {
            return Optional.empty();
        }

        @Override
        public Optional<Map<String, Object>> execution(String executionId) {
            return Optional.empty();
        }

        @Override
        public List<Map<String, Object>> executions(int limit) {
            return List.of();
        }

        @Override
        public void createExecution(Map<String, Object> row) {
        }

        @Override
        public boolean claimExecutionRollback(String executionId) {
            return false;
        }

        @Override
        public boolean completeExecutionRollback(String executionId, LocalDateTime rollbackAt, String reason,
                                                 List<Map<String, Object>> rollbackActions) {
            return false;
        }
    }

    private static final class FakeTreasuryCoverageFacade implements TreasuryCoverageFacade {
        private TreasuryCoverageSnapshot snapshot = new TreasuryCoverageSnapshot(new BigDecimal("110.00"), new BigDecimal("85.00"));

        @Override
        public TreasuryCoverageSnapshot snapshot() {
            return snapshot;
        }
    }
}
