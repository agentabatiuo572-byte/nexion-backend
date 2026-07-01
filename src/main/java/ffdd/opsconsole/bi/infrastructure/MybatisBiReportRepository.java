package ffdd.opsconsole.bi.infrastructure;


import lombok.RequiredArgsConstructor;
import ffdd.opsconsole.bi.domain.BiReportCreateCommand;
import ffdd.opsconsole.bi.domain.BiReportRepository;
import ffdd.opsconsole.bi.domain.BiReportView;
import ffdd.opsconsole.bi.mapper.BiReportMapper;
import ffdd.opsconsole.shared.api.PageResult;
import jakarta.annotation.PostConstruct;
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

    @PostConstruct
    void ensureSchema() {
        mapper.createReportTable();
    }

    @Override
    public Map<String, Object> overview() {
        Map<String, Object> overview = new LinkedHashMap<>();
        overview.put("totalReports", mapper.countTotalReports());
        overview.put("sensitiveReports", mapper.countSensitiveReports());
        overview.put("pendingConfirm", mapper.countPendingConfirm());
        overview.put("readyReports", mapper.countReadyReports());
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
    public void updateAction(String reportId, String action, String nextStatus, String reason) {
        mapper.updateAction(reportId, action, nextStatus, reason);
    }

    private Map<String, Object> kpiDashboard() {
        return linked(
                "module", "L1",
                "totals", linked(
                        "users", mapper.countUsers(),
                        "orders", mapper.countOrders() + mapper.countAdminDeviceOrders(),
                        "withdrawals", mapper.countWithdrawals(),
                        "exchanges", mapper.countExchanges(),
                        "stakingPositions", mapper.countStakingPositions(),
                        "walletLedgerRows", mapper.countWalletLedgers(),
                        "supportTickets", mapper.countSupportTickets(),
                        "auditLogs", mapper.countAuditLogs()),
                "sources", List.of(
                        "nx_user",
                        "nx_order",
                        "nx_admin_device_order",
                        "nx_withdrawal_order",
                        "nx_exchange_order",
                        "nx_staking_position",
                        "nx_wallet_ledger",
                        "nx_support_ticket",
                        "nx_audit_log"));
    }

    private Map<String, Object> funnelDashboard() {
        return linked(
                "module", "L2",
                "stages", List.of(
                        stage("registered", mapper.countUsers(), "nx_user"),
                        stage("profileCompleted", mapper.countUserProfiles(), "nx_user_profile"),
                        stage("kycSubmitted", mapper.countKycProfiles(), "nx_kyc_profile"),
                        stage("kycApproved", mapper.countKycProfilesByStatus("APPROVED"), "nx_kyc_profile"),
                        stage("ordered", mapper.countOrders() + mapper.countAdminDeviceOrders(), "nx_order/nx_admin_device_order"),
                        stage("walletActivity", mapper.countWalletLedgers() + mapper.countWalletBills(), "nx_wallet_ledger/nx_wallet_bill")),
                "sources", List.of(
                        "nx_user",
                        "nx_user_profile",
                        "nx_kyc_profile",
                        "nx_order",
                        "nx_admin_device_order",
                        "nx_wallet_ledger",
                        "nx_wallet_bill"));
    }

    private Map<String, Object> operationsDashboard() {
        return linked(
                "module", "L4",
                "workload", linked(
                        "supportTickets", mapper.countSupportTickets(),
                        "openSupportTickets", mapper.countSupportTicketsByStatus("OPEN"),
                        "conversations", mapper.countConversations(),
                        "deviceOrders", mapper.countAdminDeviceOrders(),
                        "devices", mapper.countDevices(),
                        "pendingWithdrawals", mapper.countWithdrawalsByStatus("PENDING"),
                        "auditLogs", mapper.countAuditLogs()),
                "sources", List.of(
                        "nx_support_ticket",
                        "nx_conversation",
                        "nx_admin_device_order",
                        "nx_device",
                        "nx_withdrawal_order",
                        "nx_audit_log"));
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
                "regulatoryTemplates", List.of(),
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
