package ffdd.opsconsole.bi.infrastructure;


import lombok.RequiredArgsConstructor;
import ffdd.opsconsole.bi.domain.BiReportCreateCommand;
import ffdd.opsconsole.bi.domain.BiReportRepository;
import ffdd.opsconsole.bi.domain.BiReportSnapshot;
import ffdd.opsconsole.bi.domain.BiReportView;
import ffdd.opsconsole.bi.domain.L1KpiAnalytics;
import ffdd.opsconsole.bi.domain.L2FunnelAnalytics;
import ffdd.opsconsole.bi.domain.L4OperationsAnalytics;
import ffdd.opsconsole.bi.mapper.BiReportMapper;
import ffdd.opsconsole.shared.api.PageResult;
import ffdd.opsconsole.shared.outbox.EventOutboxService;
import ffdd.opsconsole.platform.application.A4RuntimePolicyService;
import jakarta.annotation.PostConstruct;
import java.time.LocalDateTime;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;

@Repository
@RequiredArgsConstructor
public class MybatisBiReportRepository implements BiReportRepository {
    private final BiReportMapper mapper;
    private final BiReportArtifactStore artifactStore;
    private final EventOutboxService eventOutboxService;
    private final A4RuntimePolicyService a4RuntimePolicyService;

    @PostConstruct
    void ensureSchema() {
        mapper.createReportTable();
        if (mapper.countSnapshotCsvColumn() == 0) {
            mapper.addSnapshotCsvColumn();
        }
        if (mapper.countDownloadTokenHashColumn() == 0) {
            mapper.addDownloadTokenHashColumn();
        }
        if (mapper.countDownloadTokenExpiresAtColumn() == 0) {
            mapper.addDownloadTokenExpiresAtColumn();
        }
    }

    @Override
    public Map<String, Object> overview() {
        Map<String, Object> overview = new LinkedHashMap<>();
        overview.put("totalReports", mapper.countTotalReports());
        overview.put("sensitiveReports", mapper.countSensitiveReports());
        overview.put("pendingConfirm", mapper.countPendingConfirm());
        overview.put("readyReports", mapper.countReadyReports());
        overview.put("legacyReadyWithoutSnapshot", mapper.countReadyReportsWithoutSnapshot());
        return overview;
    }

    @Override
    public Map<String, Object> dashboard(String moduleCode) {
        return switch (normalizeModule(moduleCode)) {
            case "L1" -> kpiDashboard();
            case "L2" -> funnelDashboard();
            case "L4" -> operationsDashboard();
            case "L6" -> behaviorDashboard();
            case "L5" -> exportDashboard();
            default -> Map.of();
        };
    }

    @Override
    public PageResult<BiReportView> reports(String type, List<String> statuses, int pageNum, int pageSize) {
        String normalizedType = StringUtils.hasText(type) ? type.trim() : null;
        List<String> normalizedStatuses = statuses == null ? List.of() : statuses;
        long total = mapper.countReports(normalizedType, normalizedStatuses);
        int offset = Math.max(0, (pageNum - 1) * pageSize);
        List<BiReportView> records = mapper.reports(normalizedType, normalizedStatuses, pageSize, offset);
        return new PageResult<>(total, pageNum, pageSize, records);
    }

    @Override
    public Optional<BiReportView> findReport(String reportId) {
        return Optional.ofNullable(mapper.findReport(reportId));
    }

    @Override
    public BiReportView createReport(BiReportCreateCommand command) {
        mapper.upsertReportSeed(new BiReportMapper.ReportSeed(
                "L5",
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
                command.note()));
        return mapper.findReport(command.reportId());
    }

    @Override
    public void saveSnapshotCsv(String reportId, String snapshotCsv) {
        artifactStore.storeCsv(reportId, snapshotCsv);
        if (mapper.updateSnapshotCsv(reportId, "@MINIO:" + reportId) != 1) {
            throw new IllegalStateException("BI_REPORT_SNAPSHOT_WRITE_FAILED");
        }
    }

    @Override
    public Optional<String> findSnapshotCsv(String reportId) {
        Optional<BiReportSnapshot> artifact = artifactStore.open(reportId);
        if (artifact.isPresent()) {
            try (BiReportSnapshot snapshot = artifact.get()) {
                return Optional.of(new String(snapshot.inputStream().readAllBytes(), StandardCharsets.UTF_8));
            } catch (IOException exception) {
                throw new IllegalStateException("BI_REPORT_ARTIFACT_READ_FAILED", exception);
            }
        }
        String legacy = mapper.findSnapshotCsv(reportId);
        return legacy == null || legacy.startsWith("@MINIO:")
                ? Optional.empty()
                : Optional.of(legacy);
    }

    @Override
    public boolean hasSnapshot(String reportId) {
        if (artifactStore.exists(reportId)) return true;
        String legacy = mapper.findSnapshotCsv(reportId);
        return StringUtils.hasText(legacy) && !legacy.startsWith("@MINIO:");
    }

    @Override
    public Optional<BiReportSnapshot> openSnapshot(String reportId) {
        Optional<BiReportSnapshot> artifact = artifactStore.open(reportId);
        return artifact.isPresent() ? artifact : BiReportRepository.super.openSnapshot(reportId);
    }

    @Override
    public void saveDownloadToken(String reportId, String tokenHash, LocalDateTime expiresAt) {
        if (mapper.updateDownloadToken(reportId, tokenHash, expiresAt) != 1) {
            throw new IllegalStateException("BI_DOWNLOAD_TOKEN_WRITE_FAILED");
        }
    }

    @Override
    public void saveDownloadToken(
            String reportId, String tokenHash, LocalDateTime expiresAt, Long adminId) {
        if (adminId == null || adminId <= 0) throw new IllegalArgumentException("ADMIN_AUTH_REQUIRED");
        artifactStore.issueGrant(reportId, tokenHash, adminId, expiresAt);
    }

    @Override
    public boolean isDownloadTokenValid(String reportId, String tokenHash, LocalDateTime now) {
        return mapper.countValidDownloadToken(reportId, tokenHash, now) == 1;
    }

    @Override
    public boolean isDownloadTokenValid(
            String reportId, String tokenHash, LocalDateTime now, Long adminId) {
        return adminId != null && artifactStore.isGrantValid(reportId, tokenHash, adminId, now);
    }

    @Override
    public boolean updateActionIfStatus(String reportId, String action, String expectedStatus, String nextStatus, String reason) {
        return mapper.updateActionIfStatus(reportId, action, expectedStatus, nextStatus, reason) == 1;
    }

    @Override
    public String publishReportExported(String reportId, Map<String, Object> payload) {
        return eventOutboxService.publish("BI_REPORT", reportId, "admin.report_exported", payload);
    }

    private Map<String, Object> kpiDashboard() {
        return kpiDashboard("7d", null, null, null, null);
    }

    @Override
    public Map<String, Object> kpiDashboard(
            String window, String cohort, String phase, String locale, String ref) {
        return L1KpiAnalytics.calculate(mapper.selectL1EventFacts(), window, cohort, phase, locale, ref,
                a4RuntimePolicyService.day0Seconds());
    }

    @Override
    public Map<String, Object> kpiDrilldown(
            int kpiId, String window, String cohort, String phase, String locale, String ref) {
        return L1KpiAnalytics.drilldown(mapper.selectL1EventFacts(), kpiId, window, cohort, phase, locale, ref,
                a4RuntimePolicyService.day0Seconds());
    }

    @Override
    public Map<String, Object> kpiTrend(
            int kpiId, String window, String cohort, String phase, String locale, String ref) {
        return L1KpiAnalytics.trend(mapper.selectL1EventFacts(), kpiId, window, cohort, phase, locale, ref,
                a4RuntimePolicyService.day0Seconds());
    }

    private Map<String, Object> funnelDashboard() {
        return funnelDashboard(null, null, null, null, true);
    }

    @Override
    public Map<String, Object> l2Dashboard(String cohort, String phase, String locale, String ref) {
        return funnelDashboard(cohort, phase, locale, ref, false);
    }

    private Map<String, Object> funnelDashboard(
            String cohort, String phase, String locale, String ref, boolean allowIndependentFallback) {
        Map<String, Object> dashboard = linked(
                "module", "L2",
                "sources", List.of("nx_event_outbox:event_name", "nx_event_schema_registry"));
        if (allowIndependentFallback) {
            dashboard.put("stages", List.of(
                        stage("registered", mapper.countA4DistinctActors("auth.register_completed"), "nx_event_outbox:auth.register_completed"),
                        stage("profileCompleted", mapper.countA4DistinctActors("onboarding.profile_completed"), "nx_event_outbox:onboarding.profile_completed"),
                        stage("ordered", mapper.countA4DistinctActors("checkout.started"), "nx_event_outbox:checkout.started"),
                        stage("walletActivity", mapper.countA4DistinctActors("wallet.topup_confirmed"), "nx_event_outbox:wallet.topup_confirmed")));
        }
        Map<String, Object> analytics = L2FunnelAnalytics.calculate(
                mapper.selectL2EventFacts(), cohort, phase, locale, ref);
        dashboard.put("capabilities", linked(
                "sameUserFunnel", Boolean.TRUE.equals(analytics.get("available")),
                "cohortRetention", Boolean.TRUE.equals(analytics.get("available")),
                "crossAnalysis", Boolean.TRUE.equals(analytics.get("available")),
                "incompleteRatesAreNull", true));
        if (Boolean.TRUE.equals(analytics.get("available"))) {
            dashboard.putAll(analytics);
            dashboard.put("module", "L2");
        } else {
            dashboard.putAll(analytics);
            dashboard.put("degraded", analytics);
        }
        return dashboard;
    }

    private Map<String, Object> operationsDashboard() {
        return operationsDashboard("week", "ALL", null, null);
    }

    @Override
    public Map<String, Object> operationsDashboard(String period, String phase, String from, String to) {
        Map<String, Object> result = new LinkedHashMap<>(
                L4OperationsAnalytics.calculate(mapper.selectL4EventFacts(), period, phase, from, to));
        Object deviceValue = result.get("device");
        if (deviceValue instanceof Map<?, ?> deviceRaw) {
            @SuppressWarnings("unchecked")
            Map<String, Object> device = (Map<String, Object>) deviceRaw;
            Object summaryValue = device.get("summary");
            if (summaryValue instanceof Map<?, ?> summaryRaw) {
                @SuppressWarnings("unchecked")
                Map<String, Object> summary = (Map<String, Object>) summaryRaw;
                summary.put("activeDevices", mapper.countActiveUserDevices());
            }
        }
        result.put("liveFacts", linked(
                        "deviceCatalogItems", mapper.countDevices(),
                        "deviceOrders", mapper.countAdminDeviceOrders(),
                        "userDevices", mapper.countUserDevices(),
                        "activeUserDevices", mapper.countActiveUserDevices(),
                        "computeTasks", mapper.countComputeTasks(),
                        "completedComputeTasks", mapper.countCompletedComputeTasks(),
                        "taskCatalogItems", mapper.countDeviceTaskCatalogItems(),
                        "teamRelationships", mapper.countTeamRelationships(),
                        "commissionEvents", mapper.countCommissionEvents(),
                        "configuredPhases", mapper.countConfiguredPhases(),
                        "e3ConfigChanges", mapper.countA4Events("admin.tradein_config_changed"),
                        "tradeinApplications", mapper.countTradeinApplications(),
                        "completedTradeins", mapper.countCompletedTradeins()));
        result.put("snapshotSources", List.of(
                "E device catalog and device ledger",
                "E task engine",
                "F team relationship ledger",
                "H1 phase configuration"));
        return result;
    }

    @Override
    public List<Map<String, Object>> networkTreeRows(String period, int depth, int limit) {
        return mapper.selectL4NetworkTreeRows(period, depth, limit);
    }

    @Override
    public long countRegisteredServerEvent(String eventName) {
        return mapper.countA4Events(eventName);
    }

    private Map<String, Object> behaviorDashboard() {
        return linked(
                "module", "L6",
                "activityByWindow", linked(
                        "24h", behaviorActivity(1),
                        "7d", behaviorActivity(7),
                        "30d", behaviorActivity(30)),
                "sources", List.of(
                        "nx_user",
                        "nx_withdrawal_order",
                        "nx_exchange_order",
                        "nx_wallet_ledger",
                        "nx_order",
                        "nx_admin_device_order",
                        "nx_support_ticket",
                        "nx_conversation",
                        "nx_audit_log"));
    }

    private List<Map<String, Object>> behaviorActivity(int days) {
        return List.of(
                activity("account", mapper.countUsersSince(days), "nx_user"),
                activity("funds", mapper.countWithdrawalsSince(days) + mapper.countExchangesSince(days) + mapper.countWalletLedgersSince(days), "nx_withdrawal_order/nx_exchange_order/nx_wallet_ledger"),
                activity("commerce", mapper.countOrdersSince(days) + mapper.countAdminDeviceOrdersSince(days), "nx_order/nx_admin_device_order"),
                activity("support", mapper.countSupportTicketsSince(days) + mapper.countConversationsSince(days), "nx_support_ticket/nx_conversation"),
                activity("audit", mapper.countAuditLogsSince(days), "nx_audit_log"));
    }

    private Map<String, Object> exportDashboard() {
        return linked(
                "module", "L5",
                "regulatoryTemplates", mapper.regulatoryTemplates(),
                "sources", List.of("nx_admin_fourth_batch_report", "nx_wallet_ledger", "nx_audit_log"));
    }

    private Map<String, Object> stage(String key, long count, String source) {
        return linked("key", key, "count", count, "source", source);
    }

    private Map<String, Object> activity(String bucket, long count, String source) {
        return linked("bucket", bucket, "count", count, "source", source);
    }

    private Map<String, Object> linked(Object... pairs) {
        Map<String, Object> response = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            response.put((String) pairs[i], pairs[i + 1]);
        }
        return response;
    }

    private String normalizeModule(String moduleCode) {
        return StringUtils.hasText(moduleCode) ? moduleCode.trim().toUpperCase() : "";
    }

}
