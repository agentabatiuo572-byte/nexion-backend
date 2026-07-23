package ffdd.opsconsole.bi.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.api.PageResult;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import ffdd.opsconsole.shared.security.AdminPermissionCache;
import ffdd.opsconsole.bi.domain.BiReportDownloadFile;
import ffdd.opsconsole.bi.domain.BiReportRepository;
import ffdd.opsconsole.bi.domain.BiReportView;
import ffdd.opsconsole.bi.domain.BiReportCreateCommand;
import ffdd.opsconsole.bi.dto.BiDashboardValueRequest;
import ffdd.opsconsole.bi.dto.BiReportActionRequest;
import ffdd.opsconsole.bi.dto.BiReportCreateRequest;
import ffdd.opsconsole.bi.dto.BiReportQueryRequest;
import ffdd.opsconsole.common.api.OpsErrorCode;
import ffdd.opsconsole.platform.domain.AuditReplayCommand;
import ffdd.opsconsole.platform.domain.AuditReplayContext;
import ffdd.opsconsole.platform.mapper.AuditObjectLockMapper;
import ffdd.opsconsole.growth.facade.GrowthRhythmFacade;
import ffdd.opsconsole.growth.facade.GrowthRhythmSnapshot;
import ffdd.opsconsole.treasury.domain.TreasuryLedgerBillView;
import ffdd.opsconsole.treasury.domain.TreasuryLedgerRepository;
import ffdd.opsconsole.treasury.facade.TreasuryFinanceAnalyticsFacade;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

class OpsBiServiceTest {
    private final FakeBiReportRepository reportRepository = new FakeBiReportRepository();
    private final FakeTreasuryLedgerRepository ledgerRepository = new FakeTreasuryLedgerRepository();
    private GrowthRhythmSnapshot h1Snapshot = h1Snapshot(12, 7, "P3");
    private final GrowthRhythmFacade growthRhythmFacade = () -> h1Snapshot;
    private final AuditLogService auditLogService = mock(AuditLogService.class);
    private final AdminPermissionCache permissionCache = mock(AdminPermissionCache.class);
    private final AuditObjectLockMapper lockMapper = mock(AuditObjectLockMapper.class);
    private final AdminIdempotencyService idempotencyService = mock(AdminIdempotencyService.class);
    private Map<String, Object> financeSnapshot = Map.of();
    private final TreasuryFinanceAnalyticsFacade financeAnalyticsFacade = () -> financeSnapshot;
    private final OpsBiService service = new OpsBiService(reportRepository, growthRhythmFacade, ledgerRepository, financeAnalyticsFacade, auditLogService, permissionCache, lockMapper, idempotencyService);

    @BeforeEach
    void seedPermissionContext() {
        // reportAction service 层二次校验需 admin id + 权限码;默认 stub 全 bi_l5 码让二次校验通过
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(1L, null, List.of()));
        when(permissionCache.getPermissionCodes(anyLong())).thenReturn(
                java.util.Set.of("bi_l1_read", "bi_l1_write", "bi_l2_read", "bi_l2_write", "bi_l3_read", "bi_l3_write", "bi_l3_export_detail",
                        "bi_l4_read", "bi_l4_write", "bi_l4_export_tree", "bi_l5_read", "bi_l5_write", "bi_l5_task_approve", "bi_l6_read"));
        when(idempotencyService.execute(anyString(), anyString(), anyString(), org.mockito.ArgumentMatchers.eq(ApiResult.class), org.mockito.ArgumentMatchers.any()))
                .thenAnswer(invocation -> ((Supplier<?>) invocation.getArgument(4)).get());
        reportRepository.snapshots.put("EXP-1", "\"metric\",\"value\"\n\"frozen\",\"1\"\n");
        // 默认无 A2 对象锁,reportAction 直达;锁场景测试在专用 case 覆盖
        when(lockMapper.countActiveByTarget(anyString(), anyString(), anyString())).thenReturn(0);
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void overviewDeclaresSensitiveExportRules() {
        ApiResult<Map<String, Object>> result = service.overview();

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().get("securityParams").toString())
                .contains("confirm-with-reason")
                .contains("24 hours")
                .contains("decrypted PII export is blocked");
        assertThat(result.getData())
                .containsKeys("currentPhase", "phases", "l1", "l2", "l3", "l4", "l5", "l6");
        assertThat(result.getData().get("currentPhase"))
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("sourceDomain", "H1")
                .containsEntry("month", 7);
        assertThat(result.getData().get("l1")).isEqualTo(Map.of());
        assertThat(result.getData().get("l3")).isEqualTo(Map.of());
        assertThat(result.getData().get("l5").toString()).contains("nx_admin_fourth_batch_report");
        assertThat(result.getData().get("l6").toString()).contains("window=7d");
    }

    @Test
    void overviewReadsCurrentPhaseFromH1RhythmFacade() {
        h1Snapshot = h1Snapshot(12, 11, "P6");

        ApiResult<Map<String, Object>> result = service.overview();

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().get("currentPhase"))
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("code", "P6")
                .containsEntry("month", 11)
                .containsEntry("sourceDomain", "H1");
    }

    @Test
    void overviewOnlyReturnsModulesGrantedToTheAuthenticatedAdministrator() {
        when(permissionCache.getPermissionCodes(1L)).thenReturn(java.util.Set.of("bi_l1_read"));

        ApiResult<Map<String, Object>> result = service.overview();

        assertThat(result.getData()).containsKey("l1");
        assertThat(result.getData()).doesNotContainKeys("l2", "l3", "l4", "l5", "l6");
        assertThat(result.getData()).doesNotContainKeys(
                "totalReports", "sensitiveReports", "maskingRules", "securityParams", "sources");
    }

    @Test
    void moduleOverviewsDoNotFabricateDashboardPayloads() {
        ApiResult<Map<String, Object>> l1 = service.kpiOverview();
        ApiResult<Map<String, Object>> l2 = service.funnelOverview();
        ApiResult<Map<String, Object>> l3 = service.financeOverview();
        ApiResult<Map<String, Object>> l4 = service.operationsOverview();
        ApiResult<Map<String, Object>> l5 = service.exportOverview();
        ApiResult<Map<String, Object>> l6 = service.behaviorHeatmapOverview("7d");

        assertThat(l1.getData()).isEmpty();
        assertThat(l2.getData()).isEmpty();
        assertThat(l3.getData()).containsKey("ledgerLive");
        assertThat(l4.getData()).isEmpty();
        assertThat(l5.getData()).containsKeys("summary", "ledgerLive", "reports", "sources");
        assertThat(l5.getData().get("sources").toString()).doesNotContain("nx_admin_bi_dashboard_payload");
        assertThat(l5.getData())
                .containsEntry("module", "L5")
                .containsKeys("capabilities", "exportParams", "maskRules", "crossModuleBlockers");
        assertThat(l6.getData())
                .containsEntry("window", "7d")
                .containsEntry("available", false)
                .containsEntry("status", "BLOCKED_CROSS_MODULE")
                .containsEntry("pageTree", List.of())
                .containsEntry("clickHeatByRoute", Map.of())
                .containsEntry("sources", List.of());
        assertThat(l6.getData().toString())
                .contains("app.page_viewed", "app.element_clicked")
                .doesNotContain("nx_user", "nx_audit_log");
    }

    @Test
    void exportOverviewOnlyAdvertisesImplementedL5Capabilities() {
        ApiResult<Map<String, Object>> result = service.exportOverview();

        assertThat(result.getData().get("capabilities"))
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("aggregateExport", true)
                .containsEntry("ledgerBillExport", true)
                .containsEntry("download", true)
                .containsEntry("decryptedExport", false)
                .containsEntry("regulatoryReport", true)
                .containsEntry("networkTreeExport", true)
                .containsEntry("configMutation", false);
        assertThat(result.getData().get("exportParams").toString())
                .contains("24 小时", "100 万", "不可关闭");
        assertThat(result.getData().get("maskRules").toString())
                .contains("手机号", "卡 Token", "服务端阻断");
        assertThat(result.getData().get("crossModuleBlockers").toString())
                .doesNotContain("D4_BILL_EXPORT")
                .doesNotContain("I5_REGULATORY")
                .contains("PII_DECRYPTION");
    }

    @Test
    void chineseNoPrivacyLabelDoesNotCreateASensitiveReport() {
        ApiResult<Map<String, Object>> result = service.createReport(
                "idem-no-privacy",
                new BiReportCreateRequest(
                        "发起聚合快照导出用于运营分析", "superadmin", "KPI 序列", "近 7 天",
                        "聚合指标", "无隐私信息", "NONE", "BI 管理员", "L5-AGG"));

        assertThat(result.getCode()).isZero();
        BiReportView created = (BiReportView) result.getData().get("created");
        assertThat(created.containsPii()).isFalse();
        assertThat(created.status()).isEqualTo("READY");
        assertThat(created.maskingPolicy()).isEqualTo("NONE");
    }

    @Test
    void financeOverviewReadsLiveD4LedgerCounts() {
        ledgerRepository.counts.put(null, 12L);
        ledgerRepository.counts.put("REFUND", 2L);
        ledgerRepository.counts.put("TEAM_COMMISSION", 3L);
        ledgerRepository.counts.put("GENESIS_DIVIDEND", 1L);
        ledgerRepository.counts.put("EARNING", 4L);

        ApiResult<Map<String, Object>> result = service.financeOverview();

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().get("ledgerLive"))
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("totalBills", 12L)
                .containsEntry("refundBills", 2L)
                .containsEntry("teamCommissionBills", 3L)
                .containsEntry("genesisDividendBills", 1L)
                .containsEntry("earningBills", 4L);
        assertThat(result.getData().get("sources").toString()).contains("nx_wallet_ledger");
    }

    @Test
    void financeOverviewAlsoReadsTheCanonicalTreasurySnapshot() {
        financeSnapshot = Map.of(
                "generatedAt", "2026-07-17T16:20:00",
                "snapshot", Map.of(
                        "reserveUsd", new BigDecimal("700000.00"),
                        "liabilitiesUsd", new BigDecimal("604385.77"),
                        "coverageRatio", new BigDecimal("115.82"),
                        "redlinePct", new BigDecimal("100.00"),
                        "valuationReliable", true),
                "accounts", List.of(Map.of("key", "balance", "amount", new BigDecimal("500000.00"))),
                "maturity7d", List.of(Map.of("day", "2026-07-18", "withdrawUsd", new BigDecimal("120.00"), "interestUsd", new BigDecimal("30.00"))));

        ApiResult<Map<String, Object>> result = service.financeOverview();

        assertThat(result.getData().get("financeLive"))
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsKeys("generatedAt", "snapshot", "accounts", "maturity7d");
    }

    @Test
    void reportsUsePageResultAndStatusBuckets() {
        ApiResult<PageResult<BiReportView>> result = service.reports(new BiReportQueryRequest(
                null,
                "PENDING_CONFIRM,PENDING_SPLIT_CONFIRM",
                2,
                5,
                null));

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().getPageNum()).isEqualTo(2);
        assertThat(result.getData().getPageSize()).isEqualTo(5);
        assertThat(result.getData().getTotal()).isEqualTo(1);
        assertThat(result.getData().getRecords()).containsExactly(reportRepository.report);
        assertThat(reportRepository.lastStatuses).containsExactly("PENDING_CONFIRM", "PENDING_SPLIT_CONFIRM");
    }

    @Test
    void decryptedExportIsBlockedWith422() {
        ApiResult<Map<String, Object>> result = service.reportAction(
                "EXP-1",
                "download",
                "idem-l",
                new BiReportActionRequest("need raw data", "superadmin", true, true));

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.RETIRED_FEATURE.httpStatus());
        assertThat(result.getMessage()).isEqualTo("DECRYPTED_PII_EXPORT_BLOCKED");
    }

    @Test
    void pendingSensitiveReportCanBeApprovedAndAudited() {
        ApiResult<Map<String, Object>> result = service.reportAction(
                "EXP-1",
                "approve",
                "idem-l",
                new BiReportActionRequest("approve masked export", "superadmin", true, false));

        assertThat(result.getCode()).isZero();
        assertThat(result.getData()).containsEntry("status", "READY");
        ArgumentCaptor<AuditLogWriteRequest> captor = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
        verify(auditLogService).recordRequired(captor.capture());
        assertThat(captor.getValue().getAction()).isEqualTo("L_BI_REPORT_APPROVE");
    }

    @Test
    void reportActionUsesDurableIdempotencyAndRejectsAConcurrentStateChange() {
        reportRepository.forceActionConflict = true;

        ApiResult<Map<String, Object>> result = service.reportAction(
                "EXP-1", "approve", "idem-action-l",
                new BiReportActionRequest("approve masked export", "superadmin", true, false));

        assertThat(result.getCode()).isEqualTo(409);
        assertThat(result.getMessage()).isEqualTo("BI_REPORT_STATE_CHANGED");
        verify(idempotencyService).execute(
                org.mockito.ArgumentMatchers.eq("L_BI_REPORT_ACTION_APPROVE"),
                org.mockito.ArgumentMatchers.eq("idem-action-l"),
                org.mockito.ArgumentMatchers.matches("[0-9a-f]{64}"),
                org.mockito.ArgumentMatchers.eq(ApiResult.class),
                org.mockito.ArgumentMatchers.any());
        verify(auditLogService, never()).recordRequired(org.mockito.ArgumentMatchers.any(AuditLogWriteRequest.class));
    }

    @Test
    void replayL5TaskApproveRoutesToApproveActionAndAudits() {
        // 命令模式回放:l5_task_approve → reportAction(APPROVE) 分支,状态 PENDING_CONFIRM→READY
        ApiResult<?> result = service.replay(
                new AuditReplayCommand("L", "l5_task_approve", Map.of("reportId", "EXP-1")),
                new AuditReplayContext("approver", "approve masked export via a2", "idem-replay-l5"));

        assertThat(result.getCode()).isZero();
        assertThat(result.getData())
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("status", "READY");
        ArgumentCaptor<AuditLogWriteRequest> captor = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
        verify(auditLogService).recordRequired(captor.capture());
        assertThat(captor.getValue().getAction()).isEqualTo("L_BI_REPORT_APPROVE");
        assertThat(captor.getValue().getResourceId()).isEqualTo("EXP-1");
    }

    @Test
    void replayUnknownOpReturns422Marker() {
        ApiResult<?> result = service.replay(
                new AuditReplayCommand("L", "bi_l5_unknown", Map.of()),
                new AuditReplayContext("approver", "unknown op replay", "idem-replay-unknown"));

        assertThat(result.getCode()).isEqualTo(422);
        assertThat(result.getMessage()).contains("UNKNOWN_REPLAY_OP:bi_l5_unknown");
        verify(auditLogService, never()).recordRequired(org.mockito.ArgumentMatchers.any(AuditLogWriteRequest.class));
    }

    @Test
    void supportedAggregateReportPersistsAndUsesTheCanonicalExportAuditEvent() {
        ApiResult<Map<String, Object>> result = service.createReport(
                "idem-create-l",
                new BiReportCreateRequest(
                        "create aggregate report",
                        "superadmin",
                        "KPI 序列",
                        "2026-06",
                        "用户与订单聚合指标",
                        "NONE",
                        "NONE",
                        "BI 管理员",
                        "T-1001"));

        assertThat(result.getCode()).isZero();
        assertThat(reportRepository.report.reportId()).startsWith("EXP-");
        assertThat(reportRepository.report.status()).isEqualTo("READY");
        ArgumentCaptor<AuditLogWriteRequest> captor = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
        verify(auditLogService).recordRequired(captor.capture());
        assertThat(captor.getValue().getAction()).isEqualTo("admin.report_exported");
        assertThat(captor.getValue().getDetail())
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsKeys("scope", "fields", "rowCount");
        assertThat(captor.getValue().getActorUsername()).isEqualTo("admin:1");
    }

    @Test
    void sensitiveFinanceReportIsRejectedUntilTheDetailSnapshotSourceExists() {
        when(permissionCache.getPermissionCodes(1L)).thenReturn(java.util.Set.of("bi_l3_write", "bi_l5_write"));

        ApiResult<Map<String, Object>> denied = service.createReport(
                "idem-sensitive-finance-denied",
                new BiReportCreateRequest(
                        "export sensitive finance details", "forged-client", "财务资金明细", "当前快照",
                        "收入/兑付/用户级资金明细", "高(含手机 / 地址)", "默认脱敏", "财务管理员", "L3-FINANCE-DETAIL"));

        assertThat(denied.getCode()).isEqualTo(OpsErrorCode.RETIRED_FEATURE.httpStatus());
        assertThat(denied.getMessage()).isEqualTo("AGGREGATE_EXPORT_MUST_BE_NON_SENSITIVE");
        assertThat(reportRepository.report.reportId()).isEqualTo("EXP-1");

        when(permissionCache.getPermissionCodes(1L)).thenReturn(java.util.Set.of("bi_l3_export_detail"));
        ApiResult<Map<String, Object>> allowed = service.createReport(
                "idem-sensitive-finance-allowed",
                new BiReportCreateRequest(
                        "export sensitive finance details", "forged-client", "财务资金明细", "当前快照",
                        "收入/兑付/用户级资金明细", "高(含手机 / 地址)", "默认脱敏", "财务管理员", "L3-FINANCE-DETAIL"));

        assertThat(allowed.getCode()).isEqualTo(OpsErrorCode.RETIRED_FEATURE.httpStatus());
        assertThat(allowed.getMessage()).isEqualTo("AGGREGATE_EXPORT_MUST_BE_NON_SENSITIVE");
        assertThat(reportRepository.report.reportId()).isEqualTo("EXP-1");
    }

    @Test
    void aggregateFinanceReportRequiresMatchingDomainPermissionAndStaysDownloadable() {
        when(permissionCache.getPermissionCodes(1L)).thenReturn(java.util.Set.of("bi_l5_read"));

        ApiResult<Map<String, Object>> deniedReader = service.createReport(
                "idem-finance-l5-reader",
                new BiReportCreateRequest(
                        "export aggregate finance facts", "superadmin", "财务当前汇总", "当前快照",
                        "钱包账单分类计数", "NONE", "NONE", "财务管理员", "L3-FINANCE"));

        assertThat(deniedReader.getCode()).isEqualTo(403);
        assertThat(deniedReader.getMessage()).isEqualTo("PERMISSION_DENIED");

        when(permissionCache.getPermissionCodes(1L)).thenReturn(java.util.Set.of("bi_l3_write"));
        ApiResult<Map<String, Object>> allowed = service.createReport(
                "idem-finance-domain-writer",
                new BiReportCreateRequest(
                        "export aggregate finance facts", "superadmin", "财务当前汇总", "当前快照",
                        "钱包账单分类计数", "NONE", "NONE", "财务管理员", "L3-FINANCE"));

        assertThat(allowed.getCode()).isZero();
        String reportId = reportRepository.report.reportId();
        String token = String.valueOf(service.downloadToken(reportId).getData().get("downloadToken"));
        assertThat(service.downloadFile(reportId, token).getCode()).isZero();

        when(permissionCache.getPermissionCodes(1L)).thenReturn(java.util.Set.of("user_read"));
        ApiResult<Map<String, Object>> denied = service.createReport(
                "idem-finance-no-bi-read",
                new BiReportCreateRequest(
                        "export aggregate finance facts", "superadmin", "财务当前汇总", "当前快照",
                        "钱包账单分类计数", "NONE", "NONE", "财务管理员", "L3-FINANCE"));
        assertThat(denied.getCode()).isEqualTo(403);
        assertThat(denied.getMessage()).isEqualTo("PERMISSION_DENIED");
    }

    @Test
    void unsupportedBillAndRegulatoryCreationAreBlockedBeforePersistence() {
        ApiResult<Map<String, Object>> bill = service.createReport("idem-bill-blocked", new BiReportCreateRequest(
                "request bill details", "superadmin", "账单 CSV", "近 7 天", "七类账单",
                "NONE", "NONE", "财务管理员", "D4-BILL"));
        ApiResult<Map<String, Object>> regulatory = service.createReport("idem-reg-blocked", new BiReportCreateRequest(
                "request regulatory report", "superadmin", "监管报告", "本季", "披露版本与辖区",
                "NONE", "NONE", "风控管理员", "I5-REG"));

        assertThat(bill.getCode()).isEqualTo(OpsErrorCode.RETIRED_FEATURE.httpStatus());
        assertThat(regulatory.getCode()).isEqualTo(OpsErrorCode.RETIRED_FEATURE.httpStatus());
        assertThat(bill.getMessage()).isEqualTo("L5_EXPORT_TYPE_NOT_AVAILABLE");
        assertThat(regulatory.getMessage()).isEqualTo("L5_EXPORT_TYPE_NOT_AVAILABLE");
    }

    @Test
    void networkTreeSnapshotCanBeDownloadedButCannotUseTheGenericRerunLane() {
        reportRepository.report = new BiReportView(
                "EXP-NETWORK", "历史团队树", "NETWORK_TREE", "历史", "CSV", "全网", "团队边",
                10L, true, "PARTIAL", "READY", "L4", null, null, null);
        reportRepository.snapshots.put("EXP-NETWORK", "\"team\",\"count\"\n\"all\",\"10\"\n");

        ApiResult<Map<String, Object>> token = service.downloadToken("EXP-NETWORK");
        ApiResult<Map<String, Object>> rerun = service.reportAction(
                "EXP-NETWORK", "rerun", "idem-network-rerun",
                new BiReportActionRequest("rerun historical network export", "superadmin", false, false));

        assertThat(token.getCode()).isZero();
        assertThat(rerun.getCode()).isEqualTo(OpsErrorCode.RETIRED_FEATURE.httpStatus());
        assertThat(rerun.getMessage()).isEqualTo("L5_EXPORT_TYPE_NOT_AVAILABLE");
        assertThat(reportRepository.downloadTokenHashes).containsKey("EXP-NETWORK");

        reportRepository.report = new BiReportView(
                "EXP-REG", "历史监管报告", "REGULATORY", "历史", "PDF", "辖区", "披露",
                1L, true, "MASKED", "PENDING_CONFIRM", "I5", null, null, null);
        reportRepository.snapshots.put("EXP-REG", "historical regulatory artifact");
        ApiResult<Map<String, Object>> approve = service.reportAction(
                "EXP-REG", "approve", "idem-reg-approve",
                new BiReportActionRequest("approve historical regulatory report", "superadmin", true, false));

        assertThat(approve.getCode()).isZero();
    }

    @Test
    void commandReasonAndPersistedFieldsAreLengthBounded() {
        ApiResult<Map<String, Object>> shortReason = service.createReport("idem-short-reason", new BiReportCreateRequest(
                "1234567", "superadmin", "KPI 序列", "近 7 天", "聚合指标", "NONE", "NONE", "BI", "L5"));
        ApiResult<Map<String, Object>> longReason = service.createReport("idem-long-reason", new BiReportCreateRequest(
                "x".repeat(201), "superadmin", "KPI 序列", "近 7 天", "聚合指标", "NONE", "NONE", "BI", "L5"));
        ApiResult<Map<String, Object>> oversizedName = service.createReport("idem-long-name", new BiReportCreateRequest(
                "valid export reason", "superadmin", "KPI" + "x".repeat(126), "近 7 天", "聚合指标", "NONE", "NONE", "BI", "L5"));
        ApiResult<Map<String, Object>> missingRange = service.createReport("idem-missing-range", new BiReportCreateRequest(
                "valid export reason", "superadmin", "KPI 序列", " ", "聚合指标", "NONE", "NONE", "BI", "L5"));
        ApiResult<Map<String, Object>> missingTicket = service.createReport("idem-missing-ticket", new BiReportCreateRequest(
                "valid export reason", "superadmin", "KPI 序列", "近 7 天", "聚合指标", "NONE", "NONE", "BI", null));

        assertThat(shortReason.getCode()).isEqualTo(OpsErrorCode.REASON_REQUIRED.httpStatus());
        assertThat(shortReason.getMessage()).isEqualTo("REASON_LENGTH_INVALID");
        assertThat(longReason.getCode()).isEqualTo(OpsErrorCode.REASON_REQUIRED.httpStatus());
        assertThat(longReason.getMessage()).isEqualTo("REASON_LENGTH_INVALID");
        assertThat(oversizedName.getCode()).isEqualTo(OpsErrorCode.VALIDATION_FAILED.httpStatus());
        assertThat(missingRange.getMessage()).isEqualTo("BI_REPORT_REQUIRED_FIELD_MISSING");
        assertThat(missingTicket.getMessage()).isEqualTo("BI_REPORT_REQUIRED_FIELD_MISSING");
    }

    @Test
    void unsupportedReportActionReturnsAValidationResultInsteadOfThrowing() {
        ApiResult<Map<String, Object>> result = service.reportAction(
                "EXP-1", "destroy", "idem-unsupported-action",
                new BiReportActionRequest("unsupported action reason", "superadmin", false, false));

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.VALIDATION_FAILED.httpStatus());
        assertThat(result.getMessage()).isEqualTo("UNSUPPORTED_L_REPORT_ACTION");
    }

    @Test
    void createReportDelegatesToDurableIdempotencyWithAStablePayloadHash() {
        BiReportCreateRequest request = new BiReportCreateRequest(
                "create aggregate report", "forged-client", "KPI 当前汇总", "当前快照",
                "用户/订单聚合计数", "NONE", "NONE", "BI 管理员", "L1-KPI");

        service.createReport("idem-stable-l1", request);
        service.createReport("idem-stable-l1", request);

        ArgumentCaptor<String> hash = ArgumentCaptor.forClass(String.class);
        verify(idempotencyService, times(2)).execute(
                org.mockito.ArgumentMatchers.eq("L_BI_REPORT_CREATE"),
                org.mockito.ArgumentMatchers.eq("idem-stable-l1"),
                hash.capture(),
                org.mockito.ArgumentMatchers.eq(ApiResult.class),
                org.mockito.ArgumentMatchers.any());
        assertThat(hash.getAllValues()).hasSize(2).allMatch(hash.getAllValues().get(0)::equals);
    }

    @Test
    void createReportUsesAuthenticatedActorInsteadOfClientOperator() {
        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken("1", "N/A", List.of());
        authentication.setDetails(Map.of("username", "real-admin"));
        SecurityContextHolder.getContext().setAuthentication(authentication);

        service.createReport("idem-actor-l1", new BiReportCreateRequest(
                "create aggregate report", "forged-client", "KPI 当前汇总", "当前快照",
                "用户/订单聚合计数", "NONE", "NONE", "BI 管理员", "L1-KPI"));

        ArgumentCaptor<AuditLogWriteRequest> captor = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
        verify(auditLogService).recordRequired(captor.capture());
        assertThat(captor.getValue().getActorUsername()).isEqualTo("real-admin");
    }

    @Test
    void nonSensitiveReportIsReadyForDownloadImmediately() {
        ApiResult<Map<String, Object>> created = service.createReport(
                "idem-create-l-non-pii",
                new BiReportCreateRequest(
                        "create aggregate report",
                        "auditor",
                        "运营汇总 CSV",
                        "2026-06",
                        "订单数/金额/状态",
                        "NONE",
                        "无敏感字段",
                        "audit",
                        "T-1002"));

        assertThat(created.getCode()).isZero();
        assertThat(reportRepository.report.status()).isEqualTo("READY");

        ApiResult<Map<String, Object>> token = service.downloadToken(reportRepository.report.reportId());

        assertThat(token.getCode()).isZero();
        assertThat(token.getData()).containsEntry("reportId", reportRepository.report.reportId());
    }

    @Test
    void downloadRequiresThePersistedUnexpiredToken() {
        reportRepository.report = new BiReportView(
                "EXP-TOKEN", "财务当前汇总", "FINANCE_AGG", "当前快照", "CSV", "当前快照", "聚合事实",
                5L, false, "NONE", "READY", "L3", null, null, null);
        reportRepository.snapshots.put("EXP-TOKEN", "\"指标\",\"当前值\"\n\"钱包账单\",\"5\"\n");

        assertThat(service.downloadFile("EXP-TOKEN", null).getMessage()).isEqualTo("DOWNLOAD_TOKEN_REQUIRED");
        assertThat(service.downloadFile("EXP-TOKEN", "forged").getMessage()).isEqualTo("DOWNLOAD_TOKEN_INVALID_OR_EXPIRED");

        String token = String.valueOf(service.downloadToken("EXP-TOKEN").getData().get("downloadToken"));
        assertThat(service.downloadFile("EXP-TOKEN", token).getCode()).isZero();

        reportRepository.downloadTokenExpiries.put("EXP-TOKEN", LocalDateTime.now().minusSeconds(1));
        assertThat(service.downloadFile("EXP-TOKEN", token).getMessage()).isEqualTo("DOWNLOAD_TOKEN_INVALID_OR_EXPIRED");
    }

    @Test
    void historicalReadyReportWithoutSnapshotCannotIssueTokenOrRegenerateLiveData() {
        reportRepository.report = new BiReportView(
                "EXP-LEGACY", "历史 KPI 导出", "KPI_SERIES", "当前快照", "CSV", "当前快照", "聚合事实",
                5L, false, "NONE", "READY", "L1", null, null, null);

        ApiResult<Map<String, Object>> token = service.downloadToken("EXP-LEGACY");

        assertThat(token.getCode()).isEqualTo(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus());
        assertThat(token.getMessage()).isEqualTo("REPORT_SNAPSHOT_NOT_AVAILABLE");
        assertThat(reportRepository.downloadTokenHashes).doesNotContainKey("EXP-LEGACY");
    }

    @Test
    void genericExportPermissionCannotDownloadAFinanceReport() {
        reportRepository.report = new BiReportView(
                "EXP-L3", "财务当前汇总", "FINANCE_AGG", "当前快照", "CSV", "当前快照", "聚合事实",
                5L, false, "NONE", "READY", "L3", null, null, null);
        when(permissionCache.getPermissionCodes(1L)).thenReturn(java.util.Set.of("bi_l5_write"));

        ApiResult<Map<String, Object>> token = service.downloadToken("EXP-L3");

        assertThat(token.getCode()).isEqualTo(403);
        assertThat(token.getMessage()).isEqualTo("PERMISSION_DENIED");
    }

    @Test
    void chineseNoPiiDeclarationDoesNotBecomeSensitiveBecauseItContainsTheLettersPii() {
        reportRepository.dashboards.put("L1", Map.of("totals", Map.of(
                "users", 1L,
                "orders", 2L,
                "withdrawals", 0L,
                "exchanges", 0L,
                "stakingPositions", 0L,
                "walletLedgerRows", 1L,
                "supportTickets", 0L,
                "auditLogs", 10L)));
        ApiResult<Map<String, Object>> created = service.createReport(
                "idem-create-l-chinese-no-pii",
                new BiReportCreateRequest(
                        "export aggregate KPI facts",
                        "superadmin",
                        "KPI 当前汇总",
                        "当前快照",
                        "用户/订单/提现/兑换聚合计数",
                        "无 PII",
                        "NONE",
                        "BI 管理员",
                        "L1-KPI"));

        assertThat(created.getCode()).isZero();
        assertThat(reportRepository.report.containsPii()).isFalse();
        assertThat(reportRepository.report.maskingPolicy()).isEqualTo("NONE");
        assertThat(reportRepository.report.status()).isEqualTo("READY");
        assertThat(reportRepository.report.rowCount()).isEqualTo(8L);
    }

    @Test
    void lifecycleFactExportUsesTheNumberOfRealBackendStages() {
        reportRepository.dashboards.put("L2", Map.of("stages", List.of(
                Map.of("key", "registered", "count", 1L),
                Map.of("key", "profileCompleted", "count", 1L),
                Map.of("key", "kycSubmitted", "count", 0L),
                Map.of("key", "kycApproved", "count", 0L),
                Map.of("key", "ordered", "count", 2L),
                Map.of("key", "walletActivity", "count", 1L))));

        ApiResult<Map<String, Object>> created = service.createReport(
                "idem-create-l-lifecycle-facts",
                new BiReportCreateRequest(
                        "export lifecycle facts",
                        "superadmin",
                        "漏斗生命周期事实",
                        "当前快照",
                        "注册/资料/KYC/订单/钱包活动聚合计数",
                        "NONE",
                        "NONE",
                        "BI 管理员",
                        "L2-FUNNEL"));

        assertThat(created.getCode()).isZero();
        assertThat(reportRepository.report.containsPii()).isFalse();
        assertThat(reportRepository.report.status()).isEqualTo("READY");
        assertThat(reportRepository.report.rowCount()).isEqualTo(6L);
    }

    @Test
    void financeFactExportUsesTheFiveRealLedgerFactRowsAndAReadableSnapshot() {
        ledgerRepository.counts.put(null, 12L);
        ledgerRepository.counts.put("EARNING", 4L);
        ledgerRepository.counts.put("TEAM_COMMISSION", 3L);
        ledgerRepository.counts.put("GENESIS_DIVIDEND", 1L);
        ledgerRepository.counts.put("REFUND", 2L);

        ApiResult<Map<String, Object>> created = service.createReport(
                "idem-create-l3-finance-facts",
                new BiReportCreateRequest(
                        "export current finance facts",
                        "superadmin",
                        "财务当前汇总",
                        "当前快照",
                        "钱包账单分类计数",
                        "NONE",
                        "NONE",
                        "财务管理员",
                        "L3-FINANCE"));

        assertThat(created.getCode()).isZero();
        assertThat(reportRepository.report.type()).isEqualTo("FINANCE_AGG");
        assertThat(reportRepository.report.containsPii()).isFalse();
        assertThat(reportRepository.report.status()).isEqualTo("READY");
        assertThat(reportRepository.report.rowCount()).isEqualTo(5L);

        String reportId = reportRepository.report.reportId();
        String csv = reportRepository.snapshots.get(reportId);
        assertThat(csv)
                .contains("\"分类\",\"财务事实\",\"当前值\",\"单位\",\"数据来源\"")
                .contains("\"钱包账单\",\"钱包账单总数\",\"12\",\"笔\",\"钱包流水\"")
                .contains("\"钱包账单\",\"收益账单\",\"4\",\"笔\",\"钱包流水\"")
                .contains("\"钱包账单\",\"团队佣金账单\",\"3\",\"笔\",\"钱包流水\"")
                .contains("\"钱包账单\",\"Genesis 排放账单\",\"1\",\"笔\",\"钱包流水\"")
                .contains("\"钱包账单\",\"退款账单\",\"2\",\"笔\",\"钱包流水\"")
                .doesNotContain("report_id")
                .doesNotContain("nx_");
        ArgumentCaptor<AuditLogWriteRequest> audit = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
        verify(auditLogService).recordRequired(audit.capture());
        assertThat(audit.getValue().getAction()).isEqualTo("admin.report_exported");
        assertThat(audit.getValue().getActorUsername()).isEqualTo("admin:1");
    }

    @Test
    void financeFactExportIncludesCanonicalSnapshotLiabilityAndMaturityRows() {
        financeSnapshot = Map.of(
                "snapshot", Map.of(
                        "reserveUsd", new BigDecimal("700000.00"),
                        "liabilitiesUsd", new BigDecimal("604385.77"),
                        "coverageRatio", new BigDecimal("115.82"),
                        "redlinePct", new BigDecimal("100.00"),
                        "netFlow24hUsd", new BigDecimal("2500.00"),
                        "queueBacklogCount", 2,
                        "queueBacklogUsd", new BigDecimal("150.00"),
                        "avgRiskScore", 22,
                        "valuationReliable", true),
                "accounts", List.of(Map.of("key", "balance", "amount", new BigDecimal("500000.00"))),
                "maturity7d", List.of(Map.of("day", "2026-07-18", "withdrawUsd", new BigDecimal("120.00"), "interestUsd", new BigDecimal("30.00"))));

        service.createReport(
                "idem-create-l3-canonical-finance",
                new BiReportCreateRequest(
                        "export canonical finance snapshot", "superadmin", "财务当前汇总", "当前快照",
                        "资金池概览/负债科目/七日到期/钱包账单计数", "NONE", "NONE", "财务管理员", "L3-FINANCE"));

        assertThat(reportRepository.report.rowCount()).isEqualTo(16L);
        assertThat(reportRepository.snapshots.get(reportRepository.report.reportId()))
                .contains("\"资金池概览\",\"真实储备\",\"700000.00\",\"USD\",\"资金池总账\"")
                .contains("\"负债科目\",\"可提现余额\",\"500000.00\",\"USD\",\"负债账本\"")
                .contains("\"七日到期\",\"2026-07-18 提现到期\",\"120.00\",\"USD\",\"到期排程\"")
                .contains("\"七日到期\",\"2026-07-18 利息到期\",\"30.00\",\"USD\",\"到期排程\"");
    }

    @Test
    void operationsFactExportUsesOnlyTheCurrentServerFactsAndL4Permission() {
        reportRepository.dashboards.put("L4", Map.of(
                "period", Map.of("label", "当前窗口"),
                "phaseFilter", "ALL",
                "device", Map.of("summary", Map.of(
                        "activeDevices", 8L,
                        "periodPurchasedDevices", 0L,
                        "periodRetiredDevices", 2L,
                        "periodLockedDevices", 3L,
                        "periodFirstYieldDevices", 1L)),
                "tasks", Map.of("summary", Map.of(
                        "dispatched", 4L,
                        "completed", 2L,
                        "acceptanceRate", 50.0D)),
                "network", Map.of("summary", Map.of(
                        "directRefs", 5L,
                        "commissionEvents", 7L,
                        "commissionPaidUsdt", new BigDecimal("4.00"))),
                "phaseEffect", List.of()));

        when(permissionCache.getPermissionCodes(1L)).thenReturn(java.util.Set.of("bi_l5_write"));
        ApiResult<Map<String, Object>> denied = service.createReport(
                "idem-l4-wrong-permission",
                new BiReportCreateRequest(
                        "export L4 facts", "forged-client", "运营当前汇总", "当前快照",
                        "设备/任务/团队/阶段配置事实", "NONE", "NONE", "运营管理员", "L4-OPS"));
        assertThat(denied.getCode()).isEqualTo(403);

        when(permissionCache.getPermissionCodes(1L)).thenReturn(java.util.Set.of("bi_l4_write"));
        ApiResult<Map<String, Object>> created = service.createReport(
                "idem-l4-current-facts",
                new BiReportCreateRequest(
                        "export L4 facts", "forged-client", "运营当前汇总", "当前快照",
                        "设备/任务/团队/阶段配置事实", "NONE", "NONE", "运营管理员", "L4-OPS"));

        assertThat(created.getCode()).isZero();
        assertThat(reportRepository.report.type()).isEqualTo("OPERATIONS_AGG");
        assertThat(reportRepository.report.rowCount()).isEqualTo(11L);
        assertThat(reportRepository.report.status()).isEqualTo("READY");
        assertThat(reportRepository.snapshots.get(reportRepository.report.reportId()))
                .contains("\"分类\",\"周期/阶段\",\"运营指标\",\"值\",\"单位\",\"数据来源\"")
                .contains("\"设备\",\"当前窗口 / ALL\",\"期间设备购置数\",\"0\",\"条\",\"A4 设备/产出事件\"")
                .contains("\"网络\",\"当前窗口 / ALL\",\"佣金事件数\",\"7\",\"条\",\"A4/F 团队事件\"")
                .doesNotContain("nx_");
    }

    @Test
    void genericNetworkTreeCreationIsBlockedInFavorOfTheDedicatedL4Md1Endpoint() {
        when(permissionCache.getPermissionCodes(1L)).thenReturn(java.util.Set.of("bi_l4_write"));
        ApiResult<Map<String, Object>> forgedNonSensitive = service.createReport(
                "idem-l4-team-tree-forged-none",
                new BiReportCreateRequest(
                        "export team tree", "forged-client", "团队树明细", "全网",
                        "直推关系/团队边", "NONE", "NONE", "增长管理员", "L4-NETWORK-TREE"));
        assertThat(forgedNonSensitive.getCode()).isEqualTo(OpsErrorCode.RETIRED_FEATURE.httpStatus());
        assertThat(forgedNonSensitive.getMessage()).isEqualTo("L5_EXPORT_TYPE_NOT_AVAILABLE");

        when(permissionCache.getPermissionCodes(1L)).thenReturn(java.util.Set.of("bi_l4_export_tree"));

        ApiResult<Map<String, Object>> result = service.createReport(
                "idem-l4-team-tree",
                new BiReportCreateRequest(
                        "export team tree", "forged-client", "团队树明细", "全网",
                        "用户编码维度直推/团队边", "低(脱敏 ID)", "部分脱敏", "增长管理员", "L4-NETWORK-TREE"));

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.RETIRED_FEATURE.httpStatus());
        assertThat(result.getMessage()).isEqualTo("L5_EXPORT_TYPE_NOT_AVAILABLE");
        assertThat(reportRepository.report.reportId()).isEqualTo("EXP-1");
    }

    @Test
    void dedicatedNetworkTreeExportEnforcesReasonPermissionMaskingAndDownloadSnapshot() {
        reportRepository.networkTreeRows = List.of(Map.of(
                "memberUserIdPartial", "12***89",
                "sponsorUserIdPartial", "98***21",
                "treeDepth", 2,
                "vRank", "V3",
                "teamVolumeUsdt", new BigDecimal("1234.56"),
                "joinedAt", "2026-07-22 10:00:00"));

        ApiResult<Map<String, Object>> missingReason = service.exportNetworkTree(
                "week", "tree", 3, null, "idem-l4-tree-missing-reason");
        assertThat(missingReason.getCode()).isEqualTo(400);
        assertThat(missingReason.getMessage()).isEqualTo("REASON_REQUIRED");

        when(permissionCache.getPermissionCodes(1L)).thenReturn(java.util.Set.of("bi_l4_read"));
        ApiResult<Map<String, Object>> unauthorized = service.exportNetworkTree(
                "week", "tree", 3, "导出团队结构用于周度运营复盘", "idem-l4-tree-denied");
        assertThat(unauthorized.getCode()).isEqualTo(403);
        assertThat(unauthorized.getMessage()).isEqualTo("PERMISSION_DENIED");

        when(permissionCache.getPermissionCodes(1L)).thenReturn(java.util.Set.of("bi_l4_export_tree"));
        ApiResult<Map<String, Object>> created = service.exportNetworkTree(
                "week", "tree", 3, "导出团队结构用于周度运营复盘", "idem-l4-tree-success");
        assertThat(created.getCode()).isZero();
        assertThat(created.getData())
                .containsEntry("status", "READY")
                .containsEntry("rowCount", 1L)
                .containsEntry("maskingPolicy", "PARTIAL")
                .containsEntry("containsPii", true);
        assertThat(reportRepository.report.type()).isEqualTo("NETWORK_TREE");
        assertThat(reportRepository.report.containsPii()).isTrue();
        assertThat(reportRepository.snapshots.get(reportRepository.report.reportId()))
                .contains("12***89", "98***21", "V3", "1234.56")
                .doesNotContain("member_user_id", "sponsor_user_id");

        String reportId = reportRepository.report.reportId();
        String token = String.valueOf(service.downloadToken(reportId).getData().get("downloadToken"));
        ApiResult<BiReportDownloadFile> downloaded = service.downloadFile(reportId, token);
        assertThat(downloaded.getCode()).isZero();
        assertThat(new String(downloaded.getData().body(), StandardCharsets.UTF_8))
                .contains("成员用户编码（部分脱敏）", "12***89")
                .doesNotContain("member_user_id");

        ArgumentCaptor<AuditLogWriteRequest> audits = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
        verify(auditLogService, org.mockito.Mockito.atLeast(3)).recordRequired(audits.capture());
        assertThat(audits.getAllValues()).extracting(AuditLogWriteRequest::getAction)
                .contains("admin.report_exported", "admin.sensitive_export_alerted", "L_BI_REPORT_DOWNLOAD_FILE");
    }

    @Test
    void financeDownloadKeepsTheCreationSnapshotWhenLedgerCountsChange() {
        ledgerRepository.counts.put(null, 2L);
        ledgerRepository.counts.put("REFUND", 1L);
        ApiResult<Map<String, Object>> created = service.createReport(
                "idem-snapshot-l3",
                new BiReportCreateRequest(
                        "create finance snapshot",
                        "superadmin",
                        "财务当前汇总",
                        "当前快照",
                        "钱包账单分类计数",
                        "NONE",
                        "NONE",
                        "财务管理员",
                        "L3-FINANCE"));
        String reportId = ((BiReportView) created.getData().get("created")).reportId();
        ledgerRepository.counts.put(null, 99L);
        ledgerRepository.counts.put("REFUND", 88L);

        String token = String.valueOf(service.downloadToken(reportId).getData().get("downloadToken"));
        String csv = new String(service.downloadFile(reportId, token).getData().body(), StandardCharsets.UTF_8);

        assertThat(csv).contains("\"钱包账单总数\",\"2\",\"笔\"").contains("\"退款账单\",\"1\",\"笔\"");
        assertThat(csv).doesNotContain("\"钱包账单总数\",\"99\",\"笔\"").doesNotContain("\"退款账单\",\"88\",\"笔\"");
    }

    @Test
    void regulatorySchedulePayloadMutationIsDisabled() {
        ApiResult<Map<String, Object>> result = service.updateRegulatorySchedule(
                "idem-schedule-l",
                new BiDashboardValueRequest("每月 10 日", "adjust monthly schedule", "superadmin"));

        assertThat(result.getCode()).isEqualTo(501);
        assertThat(result.getMessage()).isEqualTo("BI_DASHBOARD_PAYLOAD_DISABLED");
    }

    @Test
    void downloadRequiresReadyStatus() {
        ApiResult<Map<String, Object>> result = service.reportAction(
                "EXP-1",
                "download",
                "idem-l",
                new BiReportActionRequest("download export", "superadmin", true, false));

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus());
    }

    @Test
    void downloadFileUsesPersistedSnapshotAndAudits() {
        reportRepository.report = new BiReportView(
                "EXP-READY",
                "KPI aggregate CSV",
                "KPI_SERIES",
                "OnDemand",
                "CSV",
                "aggregate snapshot",
                "masked aggregate fields",
                100L,
                true,
                "masked",
                "READY",
                "confirm",
                null,
                null,
                null);
        ledgerRepository.counts.put(null, 12L);
        reportRepository.snapshots.put("EXP-READY", "\"metric\",\"value\"\n\"frozen_ledger_bill_count\",\"7\"\n");
        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken("131", "N/A", List.of());
        authentication.setDetails(Map.of("username", "e2e_goal_audit_1"));
        SecurityContextHolder.getContext().setAuthentication(authentication);

        String token = String.valueOf(service.downloadToken("EXP-READY").getData().get("downloadToken"));
        ApiResult<BiReportDownloadFile> result = service.downloadFile("EXP-READY", token);

        assertThat(result.getCode()).isZero();
        assertThat(new String(result.getData().body(), StandardCharsets.UTF_8))
                .contains("frozen_ledger_bill_count")
                .contains("7")
                .doesNotContain("12");
        ArgumentCaptor<AuditLogWriteRequest> captor = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
        verify(auditLogService).recordRequired(captor.capture());
        assertThat(captor.getValue().getAction()).isEqualTo("L_BI_REPORT_DOWNLOAD_FILE");
        assertThat(captor.getValue().getActorUsername()).isEqualTo("e2e_goal_audit_1");
    }

    @Test
    void downloadKpiSummaryReturnsThePersistedCreationSnapshot() {
        reportRepository.report = new BiReportView(
                "EXP-KPI",
                "KPI 当前汇总",
                "KPI_SERIES",
                "当前快照",
                "CSV",
                "当前快照",
                "聚合计数",
                3L,
                false,
                "NONE",
                "READY",
                "L1",
                null,
                null,
                null);
        Map<String, Object> totals = new LinkedHashMap<>();
        totals.put("users", 1L);
        totals.put("orders", 2L);
        totals.put("auditLogs", 10L);
        reportRepository.dashboards.put("L1", Map.of("totals", totals));
        reportRepository.snapshots.put("EXP-KPI", "\"指标\",\"当前值\"\n"
                + "\"注册用户\",\"1\"\n\"订单记录\",\"2\"\n\"审计日志\",\"10\"\n");

        String token = String.valueOf(service.downloadToken("EXP-KPI").getData().get("downloadToken"));
        ApiResult<BiReportDownloadFile> result = service.downloadFile("EXP-KPI", token);

        assertThat(result.getCode()).isZero();
        String csv = new String(result.getData().body(), StandardCharsets.UTF_8);
        assertThat(csv).contains("\"指标\",\"当前值\"")
                .contains("\"注册用户\",\"1\"")
                .contains("\"订单记录\",\"2\"")
                .contains("\"审计日志\",\"10\"")
                .doesNotContain("\"users\"")
                .doesNotContain("nx_");
        assertThat(csv).doesNotContain("ledger_bill_count");
    }

    @Test
    void downloadLifecycleFactsReturnsThePersistedCreationSnapshot() {
        reportRepository.report = new BiReportView(
                "EXP-FUNNEL",
                "漏斗生命周期事实",
                "FUNNEL_COHORT",
                "当前快照",
                "CSV",
                "当前快照",
                "聚合计数",
                2L,
                false,
                "NONE",
                "READY",
                "L2",
                null,
                null,
                null);
        reportRepository.dashboards.put("L2", Map.of("stages", List.of(
                Map.of("key", "registered", "count", 1L, "source", "nx_user"),
                Map.of("key", "ordered", "count", 2L, "source", "nx_order"))));
        reportRepository.snapshots.put("EXP-FUNNEL", "\"生命周期阶段\",\"数量\",\"数据来源\"\n"
                + "\"已注册\",\"1\",\"用户主数据\"\n\"订单记录\",\"2\",\"订单主数据\"\n");

        String token = String.valueOf(service.downloadToken("EXP-FUNNEL").getData().get("downloadToken"));
        ApiResult<BiReportDownloadFile> result = service.downloadFile("EXP-FUNNEL", token);

        assertThat(result.getCode()).isZero();
        String csv = new String(result.getData().body(), StandardCharsets.UTF_8);
        assertThat(csv).contains("\"生命周期阶段\",\"数量\",\"数据来源\"")
                .contains("\"已注册\",\"1\",\"用户主数据\"")
                .contains("\"订单记录\",\"2\",\"订单主数据\"")
                .doesNotContain("nx_");
        assertThat(csv).doesNotContain("ledger_bill_count");
    }

    @Test
    void emptyPrimarySeriesFallsBackToTheAvailableFactRowsForRowCount() {
        reportRepository.dashboards.put("L1", Map.of(
                "kpis", List.of(),
                "totals", Map.of("users", 1L, "orders", 2L)));

        service.createReport("idem-empty-primary", new BiReportCreateRequest(
                "export aggregate facts", "superadmin", "KPI 当前汇总", "当前快照",
                "用户/订单聚合计数", "NONE", "NONE", "BI 管理员", "L1-KPI"));

        assertThat(reportRepository.report.rowCount()).isEqualTo(2L);
    }

    @Test
    void reportDownloadUsesTheCreationSnapshotEvenWhenLiveTotalsChangeLater() {
        reportRepository.dashboards.put("L1", Map.of("totals", Map.of("users", 1L, "orders", 2L)));
        ApiResult<Map<String, Object>> created = service.createReport(
                "idem-snapshot-l1",
                new BiReportCreateRequest(
                        "create snapshot report", "superadmin", "KPI 当前汇总", "当前快照",
                        "用户/订单聚合计数", "NONE", "NONE", "BI 管理员", "L1-KPI"));
        String reportId = String.valueOf(((BiReportView) created.getData().get("created")).reportId());
        reportRepository.dashboards.put("L1", Map.of("totals", Map.of("users", 99L, "orders", 88L)));

        String token = String.valueOf(service.downloadToken(reportId).getData().get("downloadToken"));
        ApiResult<BiReportDownloadFile> downloaded = service.downloadFile(reportId, token);

        String csv = new String(downloaded.getData().body(), StandardCharsets.UTF_8);
        assertThat(csv).contains("\"注册用户\",\"1\"").contains("\"订单记录\",\"2\"");
        assertThat(csv).doesNotContain("\"注册用户\",\"99\"").doesNotContain("\"订单记录\",\"88\"");
    }

    private GrowthRhythmSnapshot h1Snapshot(int totalMonths, int currentMonth, String currentPhase) {
        return new GrowthRhythmSnapshot(
                totalMonths,
                currentMonth,
                currentPhase,
                58,
                BigDecimal.ONE,
                BigDecimal.ONE,
                BigDecimal.ONE,
                new BigDecimal("20"),
                30,
                new BigDecimal("100"),
                BigDecimal.ONE,
                false,
                List.of("H1.rhythm.currentMonth"));
    }

    private static final class FakeTreasuryLedgerRepository implements TreasuryLedgerRepository {
        private final Map<String, Long> counts = new LinkedHashMap<>();
        private final List<TreasuryLedgerBillView> bills = new ArrayList<>();

        @Override
        public long countDeposits(LocalDateTime since, String status) {
            return counts.getOrDefault("DEPOSIT", 0L);
        }

        @Override
        public long countWithdrawals(LocalDateTime since, String status) {
            return counts.getOrDefault("WITHDRAWAL", 0L);
        }

        @Override
        public long countExchanges(LocalDateTime since, String status) {
            return counts.getOrDefault("EXCHANGE", 0L);
        }

        @Override
        public long countLedgers(LocalDateTime since, String direction) {
            return counts.getOrDefault(direction, 0L);
        }

        @Override
        public BigDecimal sumUsdtAvailable() {
            return BigDecimal.ZERO;
        }

        @Override
        public BigDecimal sumPendingWithdraw() {
            return BigDecimal.ZERO;
        }

        @Override
        public BigDecimal sumNexAvailable() {
            return BigDecimal.ZERO;
        }

        @Override
        public BigDecimal sumActiveStakingPrincipalUsdt() {
            return BigDecimal.ZERO;
        }

        @Override
        public BigDecimal sumActiveStakingInterestUsdt() {
            return BigDecimal.ZERO;
        }

        @Override
        public BigDecimal sumActiveNexLocked() {
            return BigDecimal.ZERO;
        }

        @Override
        public BigDecimal sumActiveNexReward() {
            return BigDecimal.ZERO;
        }

        @Override
        public BigDecimal sumActiveWithdrawalQueueUsdt() {
            return BigDecimal.ZERO;
        }

        @Override
        public long countActiveWithdrawalQueue() {
            return 0;
        }

        @Override
        public BigDecimal avgActiveWithdrawalQueueRiskScore() {
            return BigDecimal.ZERO;
        }

        @Override
        public BigDecimal sumPendingCommissionUsdt() {
            return BigDecimal.ZERO;
        }

        @Override
        public BigDecimal sumNetUsdtFlowBetween(LocalDateTime startAt, LocalDateTime endAt) {
            return BigDecimal.ZERO;
        }

        @Override
        public List<Map<String, Object>> maturityBuckets(LocalDateTime startAt, LocalDateTime endAt) {
            return List.of();
        }

        @Override
        public List<BigDecimal> riskPressureSeries(LocalDateTime since) {
            return List.of();
        }

        @Override
        public List<Map<String, Object>> riskRuleBuckets(LocalDateTime since) {
            return List.of();
        }

        @Override
        public List<Map<String, Object>> riskSeverityBuckets(LocalDateTime since) {
            return List.of();
        }

        @Override
        public List<Map<String, Object>> riskVolumeBuckets(LocalDateTime since) {
            return List.of();
        }

        @Override
        public BigDecimal currentReserveUsd() {
            return BigDecimal.ZERO;
        }

        @Override
        public Optional<BigDecimal> latestNexUsdtPrice() {
            return Optional.empty();
        }

        @Override
        public void recordReserveInjection(String voucherNo, BigDecimal amountUsd, String reason, String operator, String idempotencyKey) {
        }

        @Override
        public long countLedgerBills(String type, Long userId, String keyword) {
            return counts.getOrDefault(type, counts.getOrDefault(null, 0L));
        }

        @Override
        public List<TreasuryLedgerBillView> pageLedgerBills(String type, Long userId, String keyword, int pageSize, int offset) {
            return bills.stream().skip(offset).limit(pageSize).toList();
        }

        @Override
        public List<TreasuryLedgerBillView> userLedgerRows(Long userId, int limit) {
            return bills.stream().filter(row -> row.userId().equals(userId)).limit(limit).toList();
        }

        @Override
        public Optional<BigDecimal> currentUserBalance(Long userId, String asset) {
            return Optional.empty();
        }

        @Override
        public void postLedgerEntry(String bizNo, Long userId, String bizType, String asset, String direction,
                                    BigDecimal amount, String status, String remark) {
        }

    }

    private static final class FakeBiReportRepository implements BiReportRepository {
        private BiReportView report = new BiReportView(
                "EXP-1",
                "KPI aggregate CSV",
                "KPI_SERIES",
                "OnDemand",
                "CSV",
                "aggregate snapshot",
                "masked aggregate fields",
                100L,
                true,
                "masked",
                "PENDING_CONFIRM",
                "confirm",
                null,
                null,
                null);
        private List<String> lastStatuses = List.of();
        private final Map<String, Map<String, Object>> dashboards = new LinkedHashMap<>();
        private final Map<String, String> snapshots = new LinkedHashMap<>();
        private final Map<String, String> downloadTokenHashes = new LinkedHashMap<>();
        private final Map<String, LocalDateTime> downloadTokenExpiries = new LinkedHashMap<>();
        private List<Map<String, Object>> networkTreeRows = List.of();
        private boolean forceActionConflict;

        @Override
        public Map<String, Object> overview() {
            return new LinkedHashMap<>(Map.of("totalReports", 1L, "sensitiveReports", 1L));
        }

        @Override
        public Map<String, Object> dashboard(String moduleCode) {
            if (dashboards.containsKey(moduleCode)) {
                return dashboards.get(moduleCode);
            }
            if ("L6".equals(moduleCode)) {
                return new LinkedHashMap<>(Map.of(
                        "activityByWindow", Map.of("7d", List.of(Map.of("bucket", "account", "count", 1L, "source", "nx_user"))),
                        "sources", List.of("nx_user")));
            }
            return Map.of();
        }

        @Override
        public Map<String, Object> kpiDashboard(
                String window, String cohort, String phase, String locale, String ref) {
            return dashboard("L1");
        }

        @Override
        public Map<String, Object> kpiDrilldown(
                int kpiId, String window, String cohort, String phase, String locale, String ref) {
            return Map.of("kpiId", kpiId, "selected", dashboard("L1"));
        }

        @Override
        public Map<String, Object> kpiTrend(
                int kpiId, String window, String cohort, String phase, String locale, String ref) {
            return Map.of("kpiId", kpiId, "values", List.of());
        }

        @Override
        public Map<String, Object> operationsDashboard(String period, String phase, String from, String to) {
            return dashboard("L4");
        }

        @Override
        public List<Map<String, Object>> networkTreeRows(String period, int depth, int limit) {
            return networkTreeRows.stream().limit(limit).toList();
        }

        @Override
        public PageResult<BiReportView> reports(String type, List<String> statuses, int pageNum, int pageSize) {
            lastStatuses = statuses;
            return new PageResult<>(1, pageNum, pageSize, List.of(report));
        }

        @Override
        public Optional<BiReportView> findReport(String reportId) {
            return report.reportId().equals(reportId) ? Optional.of(report) : Optional.empty();
        }

        @Override
        public BiReportView createReport(BiReportCreateCommand command) {
            report = new BiReportView(
                    command.reportId(),
                    command.name(),
                    command.type(),
                    command.cycle(),
                    command.format(),
                    command.scope(),
                    command.fields(),
                    command.rowCount(),
                    command.containsPii(),
                    command.maskingPolicy(),
                    command.status(),
                    command.note(),
                    null,
                    null,
                    null);
            return report;
        }

        @Override
        public void saveSnapshotCsv(String reportId, String snapshotCsv) {
            snapshots.put(reportId, snapshotCsv);
        }

        @Override
        public Optional<String> findSnapshotCsv(String reportId) {
            return Optional.ofNullable(snapshots.get(reportId));
        }

        @Override
        public void saveDownloadToken(String reportId, String tokenHash, LocalDateTime expiresAt) {
            downloadTokenHashes.put(reportId, tokenHash);
            downloadTokenExpiries.put(reportId, expiresAt);
        }

        @Override
        public boolean isDownloadTokenValid(String reportId, String tokenHash, LocalDateTime now) {
            return tokenHash.equals(downloadTokenHashes.get(reportId))
                    && downloadTokenExpiries.containsKey(reportId)
                    && downloadTokenExpiries.get(reportId).isAfter(now);
        }

        @Override
        public boolean updateActionIfStatus(String reportId, String action, String expectedStatus, String nextStatus, String reason) {
            if (forceActionConflict) return false;
            if (!report.status().equals(expectedStatus)) return false;
            report = new BiReportView(
                    report.reportId(), report.name(), report.type(), report.cycle(), report.format(), report.scope(), report.fields(),
                    report.rowCount(), report.containsPii(), report.maskingPolicy(), nextStatus, report.note(), action, LocalDateTime.now(), reason);
            return true;
        }
    }
}
