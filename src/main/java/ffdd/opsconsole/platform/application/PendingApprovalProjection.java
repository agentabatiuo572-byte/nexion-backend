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

    record Operation(String domain, String targetType, String idField, String label) {
        AuditLockTarget target(String id) {
            return new AuditLockTarget(domain, targetType, id);
        }
    }

    public record PendingApproval(String action, String submittedBy, String submittedAt) {}
}
