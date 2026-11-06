package ffdd.opsconsole.platform.dto;

import ffdd.opsconsole.shared.audit.AuditLogRecord;
import ffdd.opsconsole.shared.audit.AuditStatsBucket;
import ffdd.opsconsole.shared.audit.AuditStatsSummaryResponse;
import java.util.List;

public record AuditCenterOverview(
        AuditOperationStats stats,
        List<AuditOperationTicket> operationQueue,
        List<AuditOperationHistory> operationHistory,
        List<AuditMechanismParam> mechanismParams,
        List<AuditConfirmCategory> confirmCategories,
        List<AuditLogRecord> recentLogs,
        AuditStatsSummaryResponse auditSummary,
        List<AuditStatsBucket> topActions) {

    public record AuditOperationStats(
            int pendingTickets,
            int fundTickets,
            int sosTickets,
            long todayAuditEvents,
            int weeklyApproved,
            int weeklyRejected,
            int weeklyExpired,
            int weeklyWithdrawn) {
    }

    public record AuditOperationTicket(
            String id,
            String action,
            String obj,
            String beforeValue,
            String afterValue,
            String operator,
            String operatorRole,
            String type,
            boolean amplifies,
            boolean sos,
            String ts,
            boolean mine,
            String roleGate,
            String reason,
            String status,
            String phoneCalibrationProposal,
            boolean coverageCheckOnApproval,
            String operationCode,
            FundEffect fundEffect,
            String refundOrderNo) {
        public AuditOperationTicket withViewerMetadata(boolean ownedByViewer, String code, FundEffect effect,
                                                       String orderNo) {
            return new AuditOperationTicket(id, action, obj, beforeValue, afterValue, operator, operatorRole,
                    type, amplifies, sos, ts, ownedByViewer, roleGate, reason, status, phoneCalibrationProposal,
                    coverageCheckOnApproval, code, effect, orderNo);
        }
    }

    public enum FundEffect {
        REWARD_SUSPENSION,
        REWARD_RESUMPTION,
        ORDER_REFUND
    }

    public record AuditOperationHistory(
            String id,
            String action,
            String st,
            String chain,
            String t,
            String note) {
    }

    public record AuditMechanismParam(
            String key,
            String name,
            String sub,
            String value,
            boolean locked) {
    }

    public record AuditConfirmCategory(
            String cat,
            String examples,
            String roleGate) {
    }
}
