package ffdd.opsconsole.platform.dto;

import ffdd.opsconsole.shared.audit.AuditLogRecord;
import ffdd.opsconsole.shared.audit.AuditStatsBucket;
import ffdd.opsconsole.shared.audit.AuditStatsSummaryResponse;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

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
            String refundOrderNo,
            boolean retiredOperation,
            /** 规则任务审批单动作: CREATE/UPDATE/ENABLE/DISABLE;其它审批单为 null。 */
            String ruleTaskAction,
            Map<String,Object> ambassadorMaterialProposal,
            BigDecimal originalAmountUsdt,
            BigDecimal refundAmountUsdt,
            boolean canApprove,
            boolean canReject,
            Integer approveDeniedCode,
            String approveDeniedMessage,
            Integer rejectDeniedCode,
            String rejectDeniedMessage) {
        public AuditOperationTicket withViewerMetadata(boolean ownedByViewer, String code, FundEffect effect,
                                                       String orderNo, boolean retired,
                                                       ffdd.opsconsole.shared.api.ApiResult<Void> approve,
                                                       ffdd.opsconsole.shared.api.ApiResult<Void> reject) {
            return new AuditOperationTicket(id, action, obj, beforeValue, afterValue, operator, operatorRole,
                    type, amplifies, sos, ts, ownedByViewer, roleGate, reason, status, phoneCalibrationProposal,
                    coverageCheckOnApproval, code, effect, orderNo, retired, ruleTaskAction, ambassadorMaterialProposal,
                    originalAmountUsdt, refundAmountUsdt,
                    approve.getCode() == 0, reject.getCode() == 0,
                    approve.getCode() == 0 ? null : approve.getCode(),
                    approve.getCode() == 0 ? null : approve.getMessage(),
                    reject.getCode() == 0 ? null : reject.getCode(),
                    reject.getCode() == 0 ? null : reject.getMessage());
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
            String ruleTaskAction,
            String st,
            String chain,
            String t,
            String note,
            String operationCode) {
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
