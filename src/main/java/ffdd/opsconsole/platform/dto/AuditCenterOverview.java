package ffdd.opsconsole.platform.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
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
            String rejectDeniedMessage,
            String objDisplayName,
            RoleGrantsDiff roleGrantsDiff,
            @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
            com.fasterxml.jackson.databind.JsonNode disclosureReview,
            @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
            CommissionReverseEvidence evidence,
            @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
            V8PromotionSnapshot v8PromotionSnapshot,
            @JsonInclude(JsonInclude.Include.NON_NULL) JsonNode changes,
            @JsonInclude(JsonInclude.Include.NON_NULL) String approvalFailureReason) {
        public AuditOperationTicket(
                String id, String action, String obj, String beforeValue, String afterValue, String operator,
                String operatorRole, String type, boolean amplifies, boolean sos, String ts, boolean mine, String roleGate,
                String reason, String status, String phoneCalibrationProposal, boolean coverageCheckOnApproval,
                String operationCode, FundEffect fundEffect, String refundOrderNo, boolean retiredOperation,
                String ruleTaskAction, Map<String,Object> ambassadorMaterialProposal, BigDecimal originalAmountUsdt,
                BigDecimal refundAmountUsdt, boolean canApprove, boolean canReject, Integer approveDeniedCode,
                String approveDeniedMessage, Integer rejectDeniedCode, String rejectDeniedMessage, String objDisplayName,
                RoleGrantsDiff roleGrantsDiff, JsonNode disclosureReview, CommissionReverseEvidence evidence,
                V8PromotionSnapshot v8PromotionSnapshot) {
            this(id, action, obj, beforeValue, afterValue, operator, operatorRole, type, amplifies, sos, ts, mine,
                    roleGate, reason, status, phoneCalibrationProposal, coverageCheckOnApproval, operationCode,
                    fundEffect, refundOrderNo, retiredOperation, ruleTaskAction, ambassadorMaterialProposal,
                    originalAmountUsdt, refundAmountUsdt, canApprove, canReject, approveDeniedCode, approveDeniedMessage,
                    rejectDeniedCode, rejectDeniedMessage, objDisplayName, roleGrantsDiff, disclosureReview, evidence,
                    v8PromotionSnapshot, null, null);
        }

        public AuditOperationTicket(
                String id, String action, String obj, String beforeValue, String afterValue, String operator,
                String operatorRole, String type, boolean amplifies, boolean sos, String ts, boolean mine, String roleGate,
                String reason, String status, String phoneCalibrationProposal, boolean coverageCheckOnApproval,
                String operationCode, FundEffect fundEffect, String refundOrderNo, boolean retiredOperation,
                String ruleTaskAction, Map<String,Object> ambassadorMaterialProposal, BigDecimal originalAmountUsdt,
                BigDecimal refundAmountUsdt, boolean canApprove, boolean canReject, Integer approveDeniedCode,
                String approveDeniedMessage, Integer rejectDeniedCode, String rejectDeniedMessage, String objDisplayName,
                RoleGrantsDiff roleGrantsDiff, JsonNode disclosureReview, CommissionReverseEvidence evidence,
                V8PromotionSnapshot v8PromotionSnapshot, JsonNode changes) {
            this(id, action, obj, beforeValue, afterValue, operator, operatorRole, type, amplifies, sos, ts, mine,
                    roleGate, reason, status, phoneCalibrationProposal, coverageCheckOnApproval, operationCode,
                    fundEffect, refundOrderNo, retiredOperation, ruleTaskAction, ambassadorMaterialProposal,
                    originalAmountUsdt, refundAmountUsdt, canApprove, canReject, approveDeniedCode, approveDeniedMessage,
                    rejectDeniedCode, rejectDeniedMessage, objDisplayName, roleGrantsDiff, disclosureReview, evidence,
                    v8PromotionSnapshot, changes, null);
        }

        public AuditOperationTicket withViewerMetadata(boolean ownedByViewer, String code, FundEffect effect,
                                                       String orderNo, boolean retired, String displayedRoleGate,
                                                       ffdd.opsconsole.shared.api.ApiResult<Void> approve,
                                                       ffdd.opsconsole.shared.api.ApiResult<Void> reject,
                                                       com.fasterxml.jackson.databind.JsonNode authorizedDisclosureReview,
                                                       CommissionReverseEvidence authorizedEvidence,
                                                       V8PromotionSnapshot authorizedV8PromotionSnapshot,
                                                       JsonNode authorizedChanges) {
            return new AuditOperationTicket(id, action, obj, beforeValue, afterValue, operator, operatorRole,
                    type, amplifies, sos, ts, ownedByViewer, displayedRoleGate, reason, status, phoneCalibrationProposal,
                    coverageCheckOnApproval, code, effect, orderNo, retired, ruleTaskAction, ambassadorMaterialProposal,
                    originalAmountUsdt, refundAmountUsdt,
                    approve.getCode() == 0, reject.getCode() == 0,
                    approve.getCode() == 0 ? null : approve.getCode(),
                    approve.getCode() == 0 ? null : approve.getMessage(),
                    reject.getCode() == 0 ? null : reject.getCode(),
                    reject.getCode() == 0 ? null : reject.getMessage(), objDisplayName, roleGrantsDiff,
                    authorizedDisclosureReview, authorizedEvidence, authorizedV8PromotionSnapshot, authorizedChanges,
                    approvalFailureReason);
        }
    }

    public record CommissionReverseEvidence(String type, String value) {}

    public record V8PromotionSnapshot(String originalRank, List<Map<String, Object>> v8Rewards,
                                     VRankBenefits v7, VRankBenefits v8) {
        public static final String FIELD = "v8PromotionSnapshot";
    }

    public record VRankBenefits(BigDecimal purchaseL1Rate, BigDecimal purchaseL2Rate, String monthlySalaryUsdt) {}

    public record RoleGrantsDiff(
            List<String> permissionCodesAdded,
            List<String> permissionCodesRemoved,
            List<Long> menuIdsAdded,
            List<Long> menuIdsRemoved) {
        public static final String FIELD = "roleGrantsDiff";
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
