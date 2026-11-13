package ffdd.opsconsole.platform.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.platform.domain.AuditLockTarget;
import ffdd.opsconsole.platform.domain.AuditReplayCommand;
import ffdd.opsconsole.platform.mapper.AuditObjectLockMapper;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.Map;
import java.util.TreeMap;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Adds an active lock indicator only to objects already authorized by the calling read endpoint. */
@Service
@RequiredArgsConstructor
public class PendingApprovalProjection {
    private static final PendingApproval GENERIC = new PendingApproval(null, null, null);
    private static final Map<String, String> VOUCHER_OPERATIONS = Map.of(
            "h7_voucher_delete", "删除代金券",
            "h7_voucher_revoke_available", "作废未使用的代金券");
    private static final VoucherPendingApproval UNKNOWN_VOUCHER = new VoucherPendingApproval(null, null, null, null);

    private static final Map<String, Operation> OPERATIONS = Map.ofEntries(
            Map.entry("e4_order_cancel", new Operation("E", "device_order", "orderNo", "取消订单")),
            Map.entry("e4_order_terminal", new Operation("E", "device_order", "orderNo", "标记失败")),
            Map.entry("e4_order_state", new Operation("E", "device_order", "orderNo", "推进订单")),
            Map.entry("e4_order_refund", new Operation("E", "device_order", "orderNo", "退款")),
            Map.entry("h2_trial_cancel", new Operation("H", "trial_session", "sid", "强制取消试用")),
            Map.entry("h2_trial_charge", new Operation("H", "trial_session", "sid", "扣款")),
            Map.entry("k1_cluster_freeze", new Operation("K", "cluster", "clusterId", "冻结账户组")),
            Map.entry("k1_cluster_release", new Operation("K", "cluster", "clusterId", "解除误判")),
            Map.entry("k1_cluster_cleared", new Operation("K", "cluster", "clusterId", "判定正常")),
            Map.entry("k1_cluster_flag", new Operation("K", "cluster", "clusterId", "标记为可疑")));

    private final AuditObjectLockMapper lockMapper;
    private final ObjectMapper objectMapper;

    public Map<String, PendingApproval> read(String domain, String targetType, Collection<String> authorizedIds) {
        if (authorizedIds.isEmpty()) return Map.of();
        Map<String, PendingApproval> approvals = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (var lock : lockMapper.selectPendingLocks(domain, targetType, authorizedIds)) {
            approvals.put(lock.targetId(), describe(domain, targetType, lock));
        }
        return approvals;
    }

    /** H7 list-only projection: active lock joined to its real pending ticket and exact delete/revoke command. */
    public Map<String, VoucherPendingApproval> readVouchers(Collection<String> authorizedIds) {
        if (authorizedIds == null || authorizedIds.isEmpty()) return Map.of();
        Map<String, VoucherPendingApproval> approvals = new TreeMap<>();
        for (var lock : lockMapper.selectPendingLocks("H", "voucher", authorizedIds)) {
            approvals.put(lock.targetId(), describeVoucher(lock));
        }
        return approvals;
    }

    static Operation operation(AuditReplayCommand command) {
        if (command == null || command.op() == null) return null;
        Operation operation = OPERATIONS.get(command.op());
        return operation != null && operation.domain().equals(command.domain()) ? operation : null;
    }

    static boolean coversTarget(AuditLockTarget target) {
        return target != null && OPERATIONS.values().stream()
                .anyMatch(operation -> operation.domain().equalsIgnoreCase(target.domain())
                        && operation.targetType().equalsIgnoreCase(target.type()));
    }

    private PendingApproval describe(String domain, String targetType, AuditObjectLockMapper.PendingLock lock) {
        if (lock.commandJson() == null || lock.operatorName() == null || lock.operatorName().isBlank()
                || lock.createdAt() == null) return GENERIC;
        try {
            JsonNode command = objectMapper.readTree(lock.commandJson());
            Operation operation = OPERATIONS.get(command.path("op").asText());
            if (operation == null || !domain.equals(operation.domain()) || !targetType.equals(operation.targetType())
                    || !domain.equals(command.path("domain").asText())
                    || !lock.targetId().equals(command.path("params").path(operation.idField()).asText().trim())) {
                return GENERIC;
            }
            return new PendingApproval(operation.label(), lock.operatorName(),
                    lock.createdAt().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        } catch (JsonProcessingException invalidHistoricCommand) {
            return GENERIC;
        }
    }

    private VoucherPendingApproval describeVoucher(AuditObjectLockMapper.PendingLock lock) {
        if (lock.ticketId() == null || lock.ticketId().isBlank()
                || lock.commandJson() == null || lock.operatorName() == null || lock.operatorName().isBlank()
                || lock.createdAt() == null) return UNKNOWN_VOUCHER;
        try {
            JsonNode command = objectMapper.readTree(lock.commandJson());
            String label = VOUCHER_OPERATIONS.get(command.path("op").asText());
            JsonNode params = command.path("params");
            if (label == null || !"H".equals(command.path("domain").asText())
                    || !params.isObject() || params.size() != 2
                    || !params.has("voucherId") || !params.has("expectedVersion")
                    || !params.path("voucherId").isTextual()
                    || !params.path("expectedVersion").isIntegralNumber()
                    || !params.path("expectedVersion").canConvertToLong()
                    || params.path("expectedVersion").longValue() <= 0) return UNKNOWN_VOUCHER;
            String voucherId = params.path("voucherId").asText();
            if (!voucherId.matches("[A-Za-z0-9_-]{1,80}") || !voucherId.equals(lock.targetId())) {
                return UNKNOWN_VOUCHER;
            }
            return new VoucherPendingApproval(label, lock.ticketId(), lock.operatorName(),
                    lock.createdAt().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        } catch (JsonProcessingException invalidHistoricCommand) {
            return UNKNOWN_VOUCHER;
        }
    }

    record Operation(String domain, String targetType, String idField, String label) {
        AuditLockTarget target(String id) {
            return new AuditLockTarget(domain, targetType, id);
        }
    }

    public record PendingApproval(String action, String submittedBy, String submittedAt) {}

    public record VoucherPendingApproval(String action, String ticketId, String submittedBy, String submittedAt) {}
}
