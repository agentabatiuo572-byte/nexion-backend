package ffdd.opsconsole.platform.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.platform.domain.AuditLockTarget;
import ffdd.opsconsole.platform.domain.AuditReplayCommand;
import ffdd.opsconsole.platform.mapper.AuditObjectLockMapper;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
    private static final H3PendingApproval UNKNOWN_H3 = new H3PendingApproval(null, null, null, null, null, null, null);
    private static final Map<String, String> H3_CONFIG_ACTIONS = Map.ofEntries(
            Map.entry("dayOne.triReward", "调整新手任务三档奖励"),
            Map.entry("weekly.mult.P1", "调整每周倍率 P1"),
            Map.entry("weekly.mult.P2", "调整每周倍率 P2"),
            Map.entry("weekly.mult.P3", "调整每周倍率 P3"),
            Map.entry("weekly.mult.P4", "调整每周倍率 P4"),
            Map.entry("weekly.mult.P5", "调整每周倍率 P5"),
            Map.entry("weekly.mult.P6", "调整每周倍率 P6"));

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

    /**
     * Read-only K2 freeze projection. It is deliberately not added to {@link #OPERATIONS}:
     * that map also drives the write-side {@link #coversTarget(AuditLockTarget)} guard, and
     * K2's existing flag/blockgift/boardflag proposals must keep their old target semantics.
     */
    public Map<String, PendingApproval> readK2RowFreeze(Collection<String> authorizedIds) {
        if (authorizedIds == null || authorizedIds.isEmpty()) return Map.of();
        Map<String, PendingApproval> approvals = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (var lock : lockMapper.selectPendingLocks("K", "arbitrage_row", authorizedIds)) {
            approvals.put(lock.targetId(), describeK2RowFreeze(lock));
        }
        return approvals;
    }

    private PendingApproval describeK2RowFreeze(AuditObjectLockMapper.PendingLock lock) {
        if (lock.commandJson() == null || lock.operatorName() == null || lock.operatorName().isBlank()
                || lock.createdAt() == null) return GENERIC;
        try {
            JsonNode command = objectMapper.readTree(lock.commandJson());
            JsonNode params = command.path("params");
            if (!"k2_row_freeze".equals(command.path("op").asText())
                    || !"K".equals(command.path("domain").asText())
                    || !lock.targetId().equals(params.path("rowId").asText().trim())) return GENERIC;
            return new PendingApproval("冻结关联账户", lock.operatorName(),
                    lock.createdAt().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        } catch (JsonProcessingException invalidHistoricCommand) {
            return GENERIC;
        }
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

    /** H3 task/list-only projection: active lock joined to the exact pending H3 config command. */
    public Map<String, H3PendingApproval> readH3Configs(Collection<String> authorizedKeys) {
        if (authorizedKeys == null || authorizedKeys.isEmpty()) return Map.of();
        Map<String, H3PendingApproval> approvals = new TreeMap<>();
        for (var lock : lockMapper.selectPendingLocks("H", "h3_quest_config", authorizedKeys)) {
            approvals.put(lock.targetId(), describeH3Config(lock));
        }
        return approvals;
    }

    private H3PendingApproval describeH3Config(AuditObjectLockMapper.PendingLock lock) {
        if (lock.ticketId() == null || lock.ticketId().isBlank()
                || lock.commandJson() == null || lock.operatorName() == null || lock.operatorName().isBlank()
                || lock.createdAt() == null) return UNKNOWN_H3;
        try {
            JsonNode command = objectMapper.readTree(lock.commandJson());
            if (!"h3_quest_config".equals(command.path("op").asText())
                    || !"H".equals(command.path("domain").asText())) return UNKNOWN_H3;
            JsonNode values = command.path("params").path("values");
            if (!values.isObject() || values.size() != 3
                    || !values.has("configKey") || !values.has("value") || !values.has("expectedValue")
                    || !values.path("configKey").isTextual()
                    || !values.path("value").isTextual()
                    || !values.path("expectedValue").isTextual()) return UNKNOWN_H3;
            String configKey = values.path("configKey").asText();
            String action = h3ConfigAction(configKey);
            if (action == null || !configKey.equals(lock.targetId())) return UNKNOWN_H3;
            String expectedValue = values.path("expectedValue").asText();
            return new H3PendingApproval(action, lock.ticketId(), lock.operatorName(),
                    lock.createdAt().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME), configKey,
                    "__MISSING__".equals(expectedValue) ? null : expectedValue, values.path("value").asText());
        } catch (JsonProcessingException invalidHistoricCommand) {
            return UNKNOWN_H3;
        }
    }

    private static String h3ConfigAction(String configKey) {
        if (configKey != null && configKey.matches("mission\\.[A-Za-z0-9_-]{2,64}\\.reward")) {
            return "调整任务奖励";
        }
        if (configKey != null && configKey.matches("monthly\\.[A-Za-z0-9_-]{2,64}\\.reward")) {
            return "调整每月奖励";
        }
        return H3_CONFIG_ACTIONS.get(configKey);
    }

    /** H3 task read: active create locks in this module, with only their exact pending create summary. */
    public List<H3PendingMissionCreation> readH3MissionCreations() {
        List<String> targetIds = lockMapper.selectActiveTargetIds("H", "h3_mission");
        if (targetIds == null || targetIds.isEmpty()) return List.of();
        List<H3PendingMissionCreation> approvals = new ArrayList<>();
        for (var lock : lockMapper.selectPendingLocks("H", "h3_mission", targetIds)) {
            approvals.add(describeH3MissionCreation(lock));
        }
        return approvals;
    }

    private H3PendingMissionCreation describeH3MissionCreation(AuditObjectLockMapper.PendingLock lock) {
        String code = safeH3MissionCode(lock.targetId());
        if (lock.ticketId() == null || lock.ticketId().isBlank()
                || lock.commandJson() == null || lock.operatorName() == null || lock.operatorName().isBlank()
                || lock.createdAt() == null) return unknownH3Mission(code);
        try {
            JsonNode command = objectMapper.readTree(lock.commandJson());
            if (!"h3_mission_create".equals(command.path("op").asText())
                    || !"H".equals(command.path("domain").asText())) return unknownH3Mission(code);
            JsonNode values = command.path("params").path("values");
            if (!values.isObject() || values.size() != 6
                    || !values.has("missionCode") || !values.has("missionName") || !values.has("missionType")
                    || !values.has("rewardPoints") || !values.has("category") || !values.has("actionRoute")
                    || !values.path("missionCode").isTextual()
                    || !values.path("missionName").isTextual()
                    || !values.path("missionType").isTextual()
                    || !values.path("rewardPoints").isIntegralNumber()
                    || !values.path("rewardPoints").canConvertToInt()
                    || values.path("rewardPoints").intValue() < 0
                    || !values.path("category").isTextual()
                    || !values.path("actionRoute").isTextual()) return unknownH3Mission(code);
            String missionCode = values.path("missionCode").asText();
            String missionName = values.path("missionName").asText();
            String missionType = values.path("missionType").asText();
            String category = values.path("category").asText();
            String actionRoute = values.path("actionRoute").asText();
            if (!missionCode.matches("[A-Za-z0-9_-]{2,64}") || !missionCode.equals(lock.targetId())
                    || !validCreateText(missionName) || !Set.of("DAY_ONE", "WEEKLY_T1", "WEEKLY_T2").contains(missionType.toUpperCase(java.util.Locale.ROOT))
                    || !validCreateText(category) || !validCreateText(actionRoute)) return unknownH3Mission(code);
            return new H3PendingMissionCreation("新建任务", lock.ticketId(), lock.operatorName(),
                    lock.createdAt().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME), missionCode,
                    missionName, missionType, values.path("rewardPoints").intValue(), category, actionRoute);
        } catch (JsonProcessingException invalidHistoricCommand) {
            return unknownH3Mission(code);
        }
    }

    private static H3PendingMissionCreation unknownH3Mission(String code) {
        return new H3PendingMissionCreation(null, null, null, null, code, null, null, null, null, null);
    }

    private static String safeH3MissionCode(String code) {
        return code != null && code.matches("[A-Za-z0-9_-]{2,64}") ? code : null;
    }

    private static boolean validCreateText(String value) {
        return value != null && !value.isBlank() && value.equals(value.trim());
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
    public record H3PendingApproval(String action, String ticketId, String submittedBy, String submittedAt,
                                    String configKey, String currentValue, String proposedValue) {}
    public record H3PendingMissionCreation(String action, String ticketId, String submittedBy,
                                            String submittedAt, String missionCode, String missionName,
                                            String missionType, Integer rewardPoints, String category,
                                            String actionRoute) {}
}
