package ffdd.opsconsole.emergency.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.common.api.OpsErrorCode;
import ffdd.opsconsole.content.facade.ContentNotificationDispatchFacade;
import ffdd.opsconsole.content.facade.NotificationEmergencyDispatchResult;
import ffdd.opsconsole.emergency.domain.EmergencyControlRepository;
import ffdd.opsconsole.emergency.dto.GeoCountryStatusRequest;
import ffdd.opsconsole.emergency.dto.GeoEdgeJudgeRequest;
import ffdd.opsconsole.emergency.dto.KillSwitchToggleRequest;
import ffdd.opsconsole.emergency.dto.SopPlaybookCreateRequest;
import ffdd.opsconsole.emergency.dto.SopPlaybookRunRequest;
import ffdd.opsconsole.emergency.dto.TamperAlertConfigRequest;
import ffdd.opsconsole.platform.domain.AuditReplayCommand;
import ffdd.opsconsole.platform.domain.AuditReplayContext;
import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class OpsEmergencyControlServiceTest {
    private final FakePlatformConfigFacade configFacade = new FakePlatformConfigFacade();
    private final FakeNotificationDispatchFacade notificationDispatchFacade = new FakeNotificationDispatchFacade();
    private final FakeEmergencyControlRepository emergencyRepository = new FakeEmergencyControlRepository();
    private final AuditLogService auditLogService = mock(AuditLogService.class);
    private final ffdd.opsconsole.platform.mapper.AuditObjectLockMapper lockMapper =
            mock(ffdd.opsconsole.platform.mapper.AuditObjectLockMapper.class);
    private final OpsKillSwitchService killSwitchService = mock(OpsKillSwitchService.class);
    private final OpsEmergencyControlService service = new OpsEmergencyControlService(
            configFacade,
            notificationDispatchFacade,
            auditLogService,
            new ObjectMapper(),
            ffdd.opsconsole.shared.seed.OpsReadTimeSeedPolicy.enabledForDirectConstruction(),
            emergencyRepository,
            lockMapper,
            killSwitchService);

    @org.junit.jupiter.api.BeforeEach
    void stubLocksNoActive() {
        org.mockito.Mockito.when(lockMapper.countActiveByTarget(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString())).thenReturn(0);
    }

    @Test
    void geoCountryChangePersistsConfigAndAudits() {
        var result = service.updateGeoCountry(
                "VE",
                "idem-j2",
                new GeoCountryStatusRequest("blocked", "regulatory request", "risk-lead"));

        assertThat(result.getCode()).isZero();
        assertThat(emergencyRepository.geoCountries)
                .anySatisfy(row -> assertThat(row)
                        .containsEntry("cc", "VE")
                        .containsEntry("status", "blocked")
                        .containsEntry("reason", "regulatory request"));
        assertThat(configFacade.values).isEmpty();

        ArgumentCaptor<AuditLogWriteRequest> captor = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
        verify(auditLogService).record(captor.capture());
        assertThat(captor.getValue().getAction()).isEqualTo("J2_GEO_COUNTRY_STATUS_CHANGED");
    }

    @Test
    void tamperThresholdRejectsOutOfRangeValue() {
        var result = service.updateTamperAlertConfig(
                "idem-j3",
                new TamperAlertConfigRequest(101, true, "too sensitive", "risk-lead"));

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.VALIDATION_FAILED.httpStatus());
    }

    @Test
    @SuppressWarnings("unchecked")
    void geoEdgeSourceWritesEmergencySettingTableNotConfigItem() {
        var result = service.updateGeoEdgeJudge(
                "idem-j2-edge",
                new GeoEdgeJudgeRequest("edge-router", "switch edge source", "risk-lead"));

        assertThat(result.getCode()).isZero();
        assertThat(emergencyRepository.settings)
                .containsEntry("emergency.geo.edgeJudgeSource", "edge-router");
        assertThat(configFacade.values).isEmpty();
        Map<String, Object> edge = (Map<String, Object>) result.getData().get("edge");
        assertThat(edge).containsEntry("source", "edge-router");
    }

    @Test
    @SuppressWarnings("unchecked")
    void tamperAlertConfigWritesEmergencySettingTableNotConfigItem() {
        var result = service.updateTamperAlertConfig(
                "idem-j3-alert",
                new TamperAlertConfigRequest(12, false, "raise alert threshold", "risk-lead"));

        assertThat(result.getCode()).isZero();
        assertThat(emergencyRepository.settings)
                .containsEntry("emergency.tamper.alert.threshold", "12")
                .containsEntry("emergency.tamper.alert.feedK4", "false");
        assertThat(configFacade.values).isEmpty();
        Map<String, Object> alert = (Map<String, Object>) result.getData().get("alertConfig");
        assertThat(alert)
                .containsEntry("threshold", 12)
                .containsEntry("feedK4", false);
    }

    @Test
    void tamperReportPersistsBusinessReportFromTamperTables() {
        emergencyRepository.tamperPaths.add(new LinkedHashMap<>(Map.of(
                "id", "withdraw-path",
                "name", "提现链路",
                "count", 3,
                "accounts", 2)));
        emergencyRepository.tamperAccounts.add(new LinkedHashMap<>(Map.of(
                "userCode", "U00000001",
                "count", 3)));

        var result = service.createTamperReport(
                "idem-j3-report",
                new ffdd.opsconsole.emergency.dto.TamperReportRequest("24h", "export tamper report", "risk-lead"));

        assertThat(result.getCode()).isZero();
        assertThat(result.getData()).containsEntry("source", "nx_emergency_tamper_report");
        assertThat(emergencyRepository.tamperReports).hasSize(1);
        assertThat(emergencyRepository.tamperReports.get(0))
                .containsEntry("window", "24h")
                .containsEntry("status", "READY");
        assertThat(String.valueOf(emergencyRepository.tamperReports.get(0).get("payload")))
                .contains("nx_emergency_tamper_event")
                .contains("withdraw-path");
        assertThat(configFacade.values).isEmpty();
    }

    @Test
    void nonEmergencyPlaybookRejectsEmergencyExecution() {
        service.createPlaybook(
                "idem-j4-create-non-emergency",
                new SopPlaybookCreateRequest(
                        "全站技术演练",
                        "技术故障",
                        "技术值班",
                        "20 分钟",
                        false,
                        "J1·进入维护模式",
                        "",
                        "",
                        "演练结束恢复",
                        false,
                        "create non emergency drill",
                        "superadmin"));

        var result = service.executePlaybook(
                "SOP-CUSTOM-1",
                "idem-j4",
                new SopPlaybookRunRequest(true, "incident", "tech-on-call"));

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus());
    }

    @Test
    void sopOverviewReadsH1RhythmSnapshot() {
        configFacade.values.put("H1.rhythm.currentMonth", "9");
        configFacade.values.put("growth.phase.current", "P5");

        var result = service.sopOverview();

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().get("h1Rhythm"))
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("currentMonth", 9)
                .containsEntry("currentPhase", "P5");
    }

    @Test
    void sopOverviewDoesNotSeedPlaybooksOrExecutionsWhenDatabaseIsEmpty() {
        var result = service.sopOverview();

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().get("playbooks").toString()).doesNotContain("SOP-01");
        assertThat(result.getData().get("executions").toString()).doesNotContain("SOP-06");
        assertThat(result.getData().get("actionOptions").toString())
                .contains("熔断提现通道")
                .contains("发送通知模板")
                .contains("I4");
        assertThat(result.getData().get("rollbackOptions").toString())
                .contains("常规根因恢复")
                .contains("提现限流恢复");
        assertThat(configFacade.values).isEmpty();
    }

    @Test
    @SuppressWarnings("unchecked")
    void createPlaybookPersistsI3NotificationReference() {
        var result = service.createPlaybook(
                "idem-j4-create",
                new SopPlaybookCreateRequest(
                        "监管点名快速止血",
                        "监管点名",
                        "合规审计",
                        "15 分钟",
                        true,
                        "J1·熔断提现\nI3·通知法务",
                        "NC-CRITICAL-001",
                        "监管点名通知 · critical · 全体超管",
                        "根因消除后常规轨恢复",
                        true,
                        "wire I3 campaign",
                        "superadmin"));

        assertThat(result.getCode()).isZero();
        assertThat(emergencyRepository.playbooks.toString())
                .contains("NC-CRITICAL-001")
                .contains("监管点名通知");
        List<Map<String, Object>> playbooks = (List<Map<String, Object>>) result.getData().get("playbooks");
        Map<String, Object> draft = playbooks.stream()
                .filter(row -> "监管点名快速止血".equals(row.get("name")))
                .findFirst()
                .orElseThrow();
        assertThat(draft)
                .containsEntry("notifyCampaignNo", "NC-CRITICAL-001")
                .containsEntry("notifyTemplate", "监管点名通知 · critical · 全体超管")
                .containsEntry("draft", true);
    }

    @Test
    @SuppressWarnings("unchecked")
    void executePlaybookDispatchesRealI3CampaignAndRecordsRollbackHistory() {
        service.createPlaybook(
                "idem-j4-create",
                new SopPlaybookCreateRequest(
                        "监管点名快速止血",
                        "监管点名",
                        "合规审计",
                        "15 分钟",
                        true,
                        "J1·熔断提现\nI3·通知法务",
                        "CMP-2617",
                        "SFC 风险披露重新确认",
                        "根因消除后常规轨恢复",
                        false,
                        "wire I3 campaign",
                        "superadmin"));

        var result = service.executePlaybook(
                "SOP-CUSTOM-1",
                "idem-j4-execute",
                new SopPlaybookRunRequest(true, "regulator escalation", "superadmin"));

        assertThat(result.getCode()).isZero();
        assertThat(notificationDispatchFacade.calls).containsExactly("CMP-2617:SOP-CUSTOM-1");
        Map<String, Object> updated = (Map<String, Object>) result.getData().get("updated");
        Map<String, Object> dispatch = (Map<String, Object>) updated.get("notificationDispatch");
        assertThat(dispatch)
                .containsEntry("status", "DISPATCHED")
                .containsEntry("campaignNo", "CMP-2617")
                .containsEntry("notificationCount", 1);
        assertThat(updated).containsEntry("rollback", "根因消除后常规轨恢复");
        assertThat(emergencyRepository.executions.toString())
                .contains("CMP-2617")
                .contains("notificationDispatch")
                .contains("根因消除后常规轨恢复");
        verify(auditLogService).record(org.mockito.ArgumentMatchers.argThat(request ->
                "J4_SOP_PLAYBOOK_EXECUTED".equals(request.getAction())
                        && String.valueOf(request.getDetail()).contains("CMP-2617")));
    }

    @Test
    @SuppressWarnings("unchecked")
    void executePlaybookRunsTargetDomainActionsAndPersistsSharedControlSettings() {
        service.createPlaybook(
                "idem-j4-create-domain-actions",
                new SopPlaybookCreateRequest(
                        "资金对账缺口",
                        "资金异常",
                        "财务主管",
                        "15 分钟",
                        true,
                        "J1·熔断 withdraw 提现\nB1·核验备付金覆盖率\nD2·按 B1 容量分批放行",
                        "",
                        "",
                        "B1 覆盖率恢复后回滚",
                        false,
                        "wire real domain actions",
                        "superadmin"));

        var result = service.executePlaybook(
                "SOP-CUSTOM-1",
                "idem-j4-domain-actions",
                new SopPlaybookRunRequest(true, "ledger gap containment", "superadmin"));

        assertThat(result.getCode()).isZero();
        assertThat(emergencyRepository.settings)
                .containsEntry("killswitch.withdraw", "disabled")
                .doesNotContainKeys("treasury.j4.coverage_check.required", "withdrawal.j4.batch_release.mode");
        assertThat(configFacade.values).isEmpty();
        Map<String, Object> updated = (Map<String, Object>) result.getData().get("updated");
        assertThat((List<Map<String, Object>>) updated.get("domainActions"))
                .extracting("domain")
                .contains("J1", "B1", "D2");
        assertThat(emergencyRepository.executions.toString())
                .contains("domainActions")
                .contains("killswitch.withdraw")
                .contains("withdrawal.j4.batch_release.mode");
    }

    @Test
    void executePlaybookRejectsI3StepWithoutNotificationCampaignBeforeDomainWrites() {
        service.createPlaybook(
                "idem-j4-create-missing-notify",
                new SopPlaybookCreateRequest(
                        "监管点名缺通知",
                        "监管点名",
                        "合规审计",
                        "15 分钟",
                        true,
                        "J1·熔断 withdraw\nI3·通知法务",
                        "",
                        "",
                        "根因消除后常规轨恢复",
                        false,
                        "wire I3 campaign",
                        "superadmin"));

        var result = service.executePlaybook(
                "SOP-CUSTOM-1",
                "idem-j4-missing-notify-execute",
                new SopPlaybookRunRequest(true, "regulator escalation", "superadmin"));

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.VALIDATION_FAILED.httpStatus());
        assertThat(result.getMessage()).isEqualTo("J4_NOTIFY_CAMPAIGN_NO_REQUIRED");
        assertThat(configFacade.values)
                .doesNotContainKey("killswitch.withdraw")
                .doesNotContainKey("emergency.sop.executions");
        assertThat(emergencyRepository.executions).isEmpty();
    }

    @Test
    void executePlaybookRejectsOversizedActionTextBeforeDomainWrites() {
        service.createPlaybook(
                "idem-j4-create-long-action",
                new SopPlaybookCreateRequest(
                        "监管点名动作过长",
                        "监管点名",
                        "合规审计",
                        "15 分钟",
                        true,
                        "J1·" + "withdraw ".repeat(80),
                        "",
                        "",
                        "根因消除后常规轨恢复",
                        false,
                        "wire long action",
                        "superadmin"));

        var result = service.executePlaybook(
                "SOP-CUSTOM-1",
                "idem-j4-long-action-execute",
                new SopPlaybookRunRequest(true, "regulator escalation", "superadmin"));

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.VALIDATION_FAILED.httpStatus());
        assertThat(result.getMessage()).isEqualTo("J4_ACTION_TEXT_TOO_LONG");
        assertThat(configFacade.values)
                .doesNotContainKey("killswitch.withdraw")
                .doesNotContainKey("emergency.sop.executions");
        assertThat(emergencyRepository.executions).isEmpty();
    }

    @Test
    @SuppressWarnings("unchecked")
    void executePlaybookPersistsCurrentExecutionToBusinessRepository() {
        service.createPlaybook(
                "idem-j4-create-trim-history",
                new SopPlaybookCreateRequest(
                        "资金对账缺口",
                        "资金异常",
                        "财务主管",
                        "15 分钟",
                        true,
                        "J1·熔断 withdraw 提现\nB1·核验备付金覆盖率",
                        "",
                        "",
                        "B1 覆盖率恢复后回滚",
                        false,
                        "wire real domain actions",
                        "superadmin"));

        var result = service.executePlaybook(
                "SOP-CUSTOM-1",
                "idem-j4-trim-history",
                new SopPlaybookRunRequest(true, "ledger gap containment", "superadmin"));

        assertThat(result.getCode()).isZero();
        String execId = String.valueOf(((Map<String, Object>) result.getData().get("updated")).get("executionId"));
        assertThat(emergencyRepository.executions)
                .singleElement()
                .satisfies(row -> assertThat(row)
                        .containsEntry("executionId", execId)
                        .containsEntry("code", "SOP-CUSTOM-1"));
        assertThat(emergencyRepository.executions.toString()).contains("killswitch.withdraw");
    }

    @Test
    @SuppressWarnings("unchecked")
    void executePlaybookReplaysSameIdempotencyKeyWithoutDuplicateSideEffects() {
        service.createPlaybook(
                "idem-j4-create",
                new SopPlaybookCreateRequest(
                        "监管点名快速止血",
                        "监管点名",
                        "合规审计",
                        "15 分钟",
                        true,
                        "J1·熔断提现\nI3·通知法务",
                        "CMP-2617",
                        "SFC 风险披露重新确认",
                        "根因消除后常规轨恢复",
                        false,
                        "wire I3 campaign",
                        "superadmin"));

        var first = service.executePlaybook(
                "SOP-CUSTOM-1",
                "idem-j4-replay",
                new SopPlaybookRunRequest(true, "regulator escalation", "superadmin"));
        String executionsAfterFirst = emergencyRepository.executions.toString();
        var second = service.executePlaybook(
                "SOP-CUSTOM-1",
                "idem-j4-replay",
                new SopPlaybookRunRequest(true, "regulator escalation", "superadmin"));

        assertThat(first.getCode()).isZero();
        assertThat(second.getCode()).isZero();
        assertThat(notificationDispatchFacade.calls).containsExactly("CMP-2617:SOP-CUSTOM-1");
        assertThat(emergencyRepository.executions.toString()).isEqualTo(executionsAfterFirst);
        assertThat(emergencyRepository.executions).hasSize(1);
        Map<String, Object> updated = (Map<String, Object>) second.getData().get("updated");
        assertThat(updated)
                .containsEntry("idempotentReplay", true)
                .containsEntry("code", "SOP-CUSTOM-1");
        verify(auditLogService, times(2)).record(org.mockito.ArgumentMatchers.argThat(request ->
                "J4_SOP_PLAYBOOK_EXECUTED".equals(request.getAction())));
        verify(auditLogService).record(org.mockito.ArgumentMatchers.argThat(request ->
                "J4_SOP_PLAYBOOK_EXECUTED".equals(request.getAction())
                        && String.valueOf(request.getDetail()).contains("idempotentReplay=true")));
    }

    @Test
    @SuppressWarnings("unchecked")
    void rollbackUsesExecutionSnapshotWhenDraftPlaybookConfigWasReplaced() {
        service.createPlaybook(
                "idem-j4-create",
                new SopPlaybookCreateRequest(
                        "监管点名快速止血",
                        "监管点名",
                        "合规审计",
                        "15 分钟",
                        true,
                        "J1·熔断提现\nI3·通知法务",
                        "CMP-2617",
                        "SFC 风险披露重新确认",
                        "根因消除后常规轨恢复",
                        false,
                        "wire I3 campaign",
                        "superadmin"));
        var executed = service.executePlaybook(
                "SOP-CUSTOM-1",
                "idem-j4-run-before-replace",
                new SopPlaybookRunRequest(true, "regulator escalation", "superadmin"));
        String execId = String.valueOf(((Map<String, Object>) executed.getData().get("updated")).get("executionId"));
        emergencyRepository.playbooks.clear();

        var rolledBack = service.rollbackPlaybookExecution(
                "SOP-CUSTOM-1",
                execId,
                "idem-j4-rollback-after-replace",
                new SopPlaybookRunRequest(false, "restore production controls", "superadmin"));

        assertThat(rolledBack.getCode()).isZero();
        Map<String, Object> updated = (Map<String, Object>) rolledBack.getData().get("updated");
        assertThat(updated)
                .containsEntry("code", "SOP-CUSTOM-1")
                .containsEntry("rollbackStatus", "ROLLED_BACK")
                .containsEntry("playbookSnapshotMissing", true);
        assertThat(emergencyRepository.executions)
                .singleElement()
                .satisfies(row -> assertThat(row)
                        .containsEntry("rollbackStatus", "ROLLED_BACK")
                        .containsKey("rollbackActions"));
        assertThat(configFacade.values).doesNotContainKey("emergency.sop.executions");
    }

    @Test
    void geoAndTamperOverviewsDoNotSeedConfigWhenDatabaseIsEmpty() {
        var geo = service.geoBlockOverview();
        var tamper = service.tamperOverview();

        assertThat(geo.getCode()).isZero();
        assertThat(tamper.getCode()).isZero();
        assertThat(configFacade.values).isEmpty();
    }

    @Test
    @SuppressWarnings("unchecked")
    void tamperAccountsArePagedAndDoNotExposeUid() {
        for (int i = 1; i <= 8; i++) {
            emergencyRepository.tamperAccounts.add(Map.of(
                    "userCode", "u-1000" + i,
                    "count", 9 + i,
                    "k4", "+" + (9 + i),
                    "last", "14:00:0" + i,
                    "paths", List.of("p" + i),
                    "cluster", "CL-1"));
        }

        var result = service.tamperOverview(2, 3);

        assertThat(result.getCode()).isZero();
        List<Map<String, Object>> accounts = (List<Map<String, Object>>) result.getData().get("accounts");
        Map<String, Object> accountPage = (Map<String, Object>) result.getData().get("accountPage");
        Map<String, Object> firstAccount = accounts.get(0);
        assertThat(accounts).hasSize(3);
        assertThat(accountPage)
                .containsEntry("page", 2)
                .containsEntry("pageSize", 3)
                .containsEntry("total", 8)
                .containsEntry("pages", 3);
        assertThat(firstAccount)
                .containsKey("userCode")
                .containsKey("userNo")
                .doesNotContainKey("uid");
        assertThat(String.valueOf(firstAccount.get("userCode"))).startsWith("U");
    }

    @Test
    @SuppressWarnings("unchecked")
    void storedTamperUidIsConvertedToUserCode() {
        emergencyRepository.tamperAccounts.add(Map.of(
                "uid", "u-83271",
                "count", 24,
                "k4", "+42",
                "last", "14:18:32",
                "paths", List.of("local-balance"),
                "cluster", "CL-318"));

        var result = service.tamperOverview(1, 5);

        assertThat(result.getCode()).isZero();
        List<Map<String, Object>> accounts = (List<Map<String, Object>>) result.getData().get("accounts");
        assertThat(accounts.get(0))
                .containsEntry("userCode", "U00083271")
                .doesNotContainKey("uid");
    }

    @Test
    void replayJ1GateKillDelegatesToKillSwitchServiceToggle() {
        doReturn(ApiResult.ok(new java.util.LinkedHashMap<>(Map.of("domain", "J1"))))
                .when(killSwitchService).toggle(eq("withdraw"), eq("idem-replay-j1-kill"), any(KillSwitchToggleRequest.class));

        ApiResult<?> result = service.replay(
                new AuditReplayCommand("J", "j1_gate_kill", Map.of("gateKey", "withdraw")),
                new AuditReplayContext("superadmin", "j1 replay kill", "idem-replay-j1-kill"));

        assertThat(result.getCode()).isZero();
        ArgumentCaptor<KillSwitchToggleRequest> captor = ArgumentCaptor.forClass(KillSwitchToggleRequest.class);
        verify(killSwitchService).toggle(eq("withdraw"), eq("idem-replay-j1-kill"), captor.capture());
        assertThat(captor.getValue().enabled()).isEqualTo("disabled");
        assertThat(captor.getValue().reason()).isEqualTo("j1 replay kill");
    }

    @Test
    void replayJ2CountryManageUpdatesGeoCountryAndAudit() {
        ApiResult<?> result = service.replay(
                new AuditReplayCommand("J", "j2_country_manage", Map.of("countryCode", "VE", "status", "blocked")),
                new AuditReplayContext("risk-lead", "j2 replay country block", "idem-replay-j2-country"));

        assertThat(result.getCode()).isZero();
        assertThat(emergencyRepository.geoCountries)
                .anySatisfy(row -> assertThat(row)
                        .containsEntry("cc", "VE")
                        .containsEntry("status", "blocked"));
        verify(auditLogService).record(org.mockito.ArgumentMatchers.argThat(request ->
                "J2_GEO_COUNTRY_STATUS_CHANGED".equals(request.getAction())
                        && String.valueOf(request.getDetail()).contains("idem-replay-j2-country")));
    }

    @Test
    void replayUnknownOpReturns422WithUnknownReplayOpMarker() {
        ApiResult<?> result = service.replay(
                new AuditReplayCommand("J", "j_unknown_op", Map.of()),
                new AuditReplayContext("superadmin", "replay unknown op", "idem-replay-unknown"));

        assertThat(result.getCode()).isEqualTo(422);
        assertThat(result.getMessage()).isEqualTo("UNKNOWN_REPLAY_OP:j_unknown_op");
    }

    private static final class FakePlatformConfigFacade implements PlatformConfigFacade {
        private final Map<String, String> values = new LinkedHashMap<>();

        @Override
        public Optional<String> activeValue(String configKey) {
            return Optional.ofNullable(values.get(configKey));
        }

        @Override
        public void upsertAdminValue(String configKey, String configValue, String valueType, String configGroup, String remark) {
            values.put(configKey, configValue);
        }
    }

    private static final class FakeEmergencyControlRepository implements EmergencyControlRepository {
        private final List<Map<String, Object>> geoCountries = new ArrayList<>();
        private final List<Map<String, Object>> geoEndpointCatalogs = new ArrayList<>();
        private final List<Map<String, Object>> geoEndpoints = new ArrayList<>();
        private final List<Map<String, Object>> geoHits = new ArrayList<>();
        private final Map<String, Integer> geoEndpointHits = new LinkedHashMap<>();
        private final List<Map<String, Object>> geoEdgeMetrics = new ArrayList<>();
        private final Map<String, String> settings = new LinkedHashMap<>();
        private final List<Map<String, Object>> tamperPaths = new ArrayList<>();
        private final List<Map<String, Object>> tamperAccounts = new ArrayList<>();
        private final List<Map<String, Object>> tamperReports = new ArrayList<>();
        private final List<Map<String, Object>> playbooks = new ArrayList<>();
        private final List<Map<String, Object>> executions = new ArrayList<>();

        @Override
        public void ensureTables() {
        }

        @Override
        public List<Map<String, Object>> geoCountryPolicies() {
            return geoCountries;
        }

        @Override
        public void upsertGeoCountryPolicy(String countryCode, String countryName, String status, String reason, String operator) {
            geoCountries.removeIf(row -> countryCode.equals(row.get("cc")));
            geoCountries.add(new LinkedHashMap<>(Map.of(
                    "cc", countryCode,
                    "name", countryName,
                    "status", status,
                    "reason", reason,
                    "operator", operator == null ? "" : operator)));
        }

        @Override
        public List<Map<String, Object>> geoEndpointCatalogs() {
            return geoEndpointCatalogs;
        }

        @Override
        public Optional<Map<String, Object>> geoEndpointCatalog(String endpointKey) {
            return geoEndpointCatalogs.stream()
                    .filter(row -> endpointKey.equals(row.get("endpointKey")))
                    .findFirst();
        }

        @Override
        public List<Map<String, Object>> geoEndpointPolicies() {
            return geoEndpoints;
        }

        @Override
        public void replaceGeoEndpointPolicies(String endpointKey, String endpointPath, String label, String biz, String domain,
                                               List<String> countryCodes, String source, String reason, String operator) {
            geoEndpoints.removeIf(row -> endpointKey.equals(row.get("endpointKey")));
            for (String countryCode : countryCodes) {
                geoEndpoints.add(new LinkedHashMap<>(Map.of(
                        "endpointKey", endpointKey,
                        "endpointPath", endpointPath,
                        "label", label,
                        "biz", biz,
                        "domain", domain,
                        "countryCode", countryCode,
                        "source", source,
                        "reason", reason)));
            }
        }

        @Override
        public List<Map<String, Object>> geoHits() {
            return geoHits;
        }

        @Override
        public Map<String, Integer> geoEndpointHits() {
            return geoEndpointHits;
        }

        @Override
        public List<Map<String, Object>> geoEdgeMetrics() {
            return geoEdgeMetrics;
        }

        @Override
        public Optional<String> settingValue(String settingKey) {
            return Optional.ofNullable(settings.get(settingKey));
        }

        @Override
        public void upsertSetting(
                String settingKey,
                String settingValue,
                String valueType,
                String groupCode,
                String remark,
                String operator) {
            settings.put(settingKey, settingValue);
        }

        @Override
        public Map<String, Object> tamperTrend(LocalDateTime now) {
            return Map.of(
                    "24h", Map.of("points", List.of(), "max", 0, "labels", List.of()),
                    "7d", Map.of("points", List.of(), "max", 0, "labels", List.of()),
                    "30d", Map.of("points", List.of(), "max", 0, "labels", List.of()));
        }

        @Override
        public List<Map<String, Object>> tamperPaths() {
            return tamperPaths;
        }

        @Override
        public List<Map<String, Object>> tamperAccounts() {
            return tamperAccounts;
        }

        @Override
        public void createTamperReport(String reportId, String window, boolean masked, String status,
                                       Map<String, Object> payload, String operator, String reason) {
            tamperReports.add(new LinkedHashMap<>(Map.of(
                    "reportId", reportId,
                    "window", window,
                    "masked", masked,
                    "status", status,
                    "payload", payload,
                    "operator", operator,
                    "reason", reason)));
        }

        @Override
        public List<Map<String, Object>> playbooks() {
            return playbooks;
        }

        @Override
        public Optional<Map<String, Object>> playbook(String code) {
            return playbooks.stream()
                    .filter(row -> code.equals(row.get("code")))
                    .findFirst();
        }

        @Override
        public void createPlaybook(String code, String name, String scene, boolean emergency, String sla, String state,
                                   String owner, String notifyCampaignNo, String notifyTemplate, String rollback,
                                   boolean drillRequired, boolean draft, List<Map<String, Object>> sequence,
                                   String operator) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("code", code);
            row.put("name", name);
            row.put("scene", scene);
            row.put("emergency", emergency);
            row.put("sla", sla);
            row.put("state", state);
            row.put("owner", owner);
            row.put("lastDrill", "未演练");
            row.put("sequence", sequence);
            row.put("notifyCampaignNo", notifyCampaignNo);
            row.put("notifyTemplate", notifyTemplate);
            row.put("rollback", rollback);
            row.put("drillRequired", drillRequired);
            row.put("draft", draft);
            row.put("customSummary", "");
            row.put("lastExecution", "");
            playbooks.add(row);
        }

        @Override
        public void updatePlaybook(String code, String name, String scene, Boolean emergency, String sla, String state,
                                   String owner, String notifyCampaignNo, String notifyTemplate, String rollback,
                                   Boolean drillRequired, String summary, List<Map<String, Object>> sequence,
                                   String operator) {
            Map<String, Object> row = playbook(code).orElseThrow();
            if (name != null && !name.isBlank()) row.put("name", name.trim());
            if (scene != null && !scene.isBlank()) row.put("scene", scene.trim());
            if (emergency != null) row.put("emergency", emergency);
            if (sla != null && !sla.isBlank()) row.put("sla", sla.trim());
            if (state != null && !state.isBlank()) row.put("state", state.trim());
            if (owner != null && !owner.isBlank()) row.put("owner", owner.trim());
            if (notifyCampaignNo != null && !notifyCampaignNo.isBlank()) row.put("notifyCampaignNo", notifyCampaignNo.trim());
            if (notifyTemplate != null && !notifyTemplate.isBlank()) row.put("notifyTemplate", notifyTemplate.trim());
            if (rollback != null && !rollback.isBlank()) row.put("rollback", rollback.trim());
            if (drillRequired != null) row.put("drillRequired", drillRequired);
            if (summary != null && !summary.isBlank()) row.put("customSummary", summary.trim());
            if (sequence != null) row.put("sequence", sequence);
        }

        @Override
        public void markPlaybookDrilled(String code, LocalDateTime drillAt, String operator) {
            playbook(code).ifPresent(row -> {
                row.put("state", "active");
                row.put("lastDrill", drillAt.toString());
            });
        }

        @Override
        public Optional<Map<String, Object>> executionByIdempotencyKey(String code, String idempotencyKey) {
            return executions.stream()
                    .filter(row -> code.equals(row.get("code")) && idempotencyKey.equals(row.get("idempotencyKey")))
                    .findFirst();
        }

        @Override
        public Optional<Map<String, Object>> execution(String executionId) {
            return executions.stream()
                    .filter(row -> executionId.equals(row.get("executionId")))
                    .findFirst();
        }

        @Override
        public List<Map<String, Object>> executions(int limit) {
            return executions.stream().limit(limit).toList();
        }

        @Override
        public void createExecution(Map<String, Object> row) {
            String code = String.valueOf(row.get("code"));
            playbook(code).ifPresent(playbook -> playbook.put("lastExecution", row.get("executionId")));
            executions.add(0, new LinkedHashMap<>(row));
        }

        @Override
        public void markExecutionRolledBack(String executionId, LocalDateTime rollbackAt, String reason,
                                            List<Map<String, Object>> rollbackActions) {
            execution(executionId).ifPresent(row -> {
                row.put("rollbackStatus", "ROLLED_BACK");
                row.put("rollbackAt", rollbackAt.toString());
                row.put("rollbackReason", reason);
                row.put("rollbackActions", rollbackActions);
            });
        }
    }

    private static final class FakeNotificationDispatchFacade implements ContentNotificationDispatchFacade {
        private final List<String> calls = new ArrayList<>();

        @Override
        public Optional<NotificationEmergencyDispatchResult> dispatchEmergencyCampaign(
                String campaignNo,
                String playbookCode,
                String executionId,
                String operator,
                String reason) {
            if (!"CMP-2617".equals(campaignNo)) {
                return Optional.empty();
            }
            calls.add(campaignNo + ":" + playbookCode);
            return Optional.of(new NotificationEmergencyDispatchResult(
                    campaignNo,
                    "SFC 风险披露重新确认",
                    "critical",
                    "全量",
                    "sending",
                    1));
        }
    }
}
