package ffdd.opsconsole.platform.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import ffdd.opsconsole.common.api.OpsErrorCode;
import ffdd.opsconsole.common.boundary.ApplicationService;
import ffdd.opsconsole.platform.domain.AuditLockTarget;
import ffdd.opsconsole.platform.domain.AuditReplayCommand;
import ffdd.opsconsole.platform.domain.AuditReplayContext;
import ffdd.opsconsole.platform.domain.PlatformConfigItem;
import ffdd.opsconsole.platform.domain.PlatformConfigRepository;
import ffdd.opsconsole.platform.dto.AuditCenterOverview;
import ffdd.opsconsole.platform.dto.AuditCenterOverview.AuditConfirmCategory;
import ffdd.opsconsole.platform.dto.AuditCenterOverview.AuditMechanismParam;
import ffdd.opsconsole.platform.dto.AuditCenterOverview.AuditOperationHistory;
import ffdd.opsconsole.platform.dto.AuditCenterOverview.AuditOperationStats;
import ffdd.opsconsole.platform.dto.AuditCenterOverview.AuditOperationTicket;
import ffdd.opsconsole.platform.dto.AuditMechanismParamUpdateRequest;
import ffdd.opsconsole.platform.dto.AuditOperationDecisionRequest;
import ffdd.opsconsole.platform.dto.AuditOperationProposalRequest;
import ffdd.opsconsole.platform.infrastructure.AuditConfirmCategoryEntity;
import ffdd.opsconsole.platform.infrastructure.AuditObjectLockEntity;
import ffdd.opsconsole.platform.infrastructure.AuditOperationHistoryEntity;
import ffdd.opsconsole.platform.infrastructure.AuditOperationTicketEntity;
import ffdd.opsconsole.platform.mapper.AuditConfirmCategoryMapper;
import ffdd.opsconsole.platform.mapper.AuditObjectLockMapper;
import ffdd.opsconsole.platform.mapper.AuditOperationHistoryMapper;
import ffdd.opsconsole.platform.mapper.AuditOperationTicketMapper;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.audit.AuditLogQueryRequest;
import ffdd.opsconsole.shared.audit.AuditLogRecord;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.shared.audit.AuditStatsBucket;
import ffdd.opsconsole.shared.audit.AuditStatsQueryRequest;
import ffdd.opsconsole.shared.audit.AuditStatsSummaryResponse;
import ffdd.opsconsole.shared.seed.OpsReadTimeSeedPolicy;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@ApplicationService
@RequiredArgsConstructor
public class OpsAuditCenterService {
    private static final String GROUP_A2 = "admin_a2";
    private static final String STATUS_PENDING = "pending";
    private static final String STATUS_APPROVED = "approved";
    private static final String STATUS_REJECTED = "rejected";
    private static final Set<String> TERMINAL_STATUSES = Set.of(STATUS_APPROVED, STATUS_REJECTED, "withdrawn", "expired");
    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    private static final List<OperationSeed> OPERATION_SEEDS = List.of();
    private static final List<AuditOperationHistory> DEFAULT_HISTORY = List.of();
    private static final List<AuditLogSeed> AUDIT_LOG_SEEDS = List.of();

    private static final List<AuditConfirmCategory> CONFIRM_CATEGORY_SEEDS = List.of(
            new AuditConfirmCategory("资金/资产调整", "余额增减(C3)· 手工账单(D4)· 储备注入(B1/D3)· 对账核销(D1)", "财务 / 超管"),
            new AuditConfirmCategory("大额资金放行", "提现放行/冻结/退款(D2)· 渠道退款(D1)", "财务 / 超管"),
            new AuditConfirmCategory("参数批改", "红黄线(B1)· 提现参数(D5)· OTP/锁定(C6)· Phase dial(H1)· 试用敏感参数(H2)", "对应域角色 / 超管(dial 放大方向需风控或超管)"),
            new AuditConfirmCategory("风险模型/KYC 裁决", "K4 权重分档(执行门槛升超管)· K5 大额复审", "超管 / 风控"),
            new AuditConfirmCategory("熔断闸", "6 功能闸 + 地区屏蔽(J1/J2 管理面)", "超管"),
            new AuditConfirmCategory("账户高敏处置", "冻结/解冻 · impersonate(C2)· KYC 人工标记(C4)· 2FA/密码(C5)", "风控 / 超管"),
            new AuditConfirmCategory("批量簇冻结", "关联账户簇批量冻结(K1)", "风控 / 超管"),
            new AuditConfirmCategory("后台账号治理", "建/停/启/改角色/重置双因子(A1)", "超管"),
            new AuditConfirmCategory("平台配置", "feature flag · 系统参数(A3)· 内容发布(I 域)", "超管 / 内容"));

    private final PlatformConfigRepository configRepository;
    private final AuditLogService auditLogService;
    private final OpsReadTimeSeedPolicy readTimeSeedPolicy;
    private final AuditOperationTicketMapper ticketMapper;
    private final AuditOperationHistoryMapper historyMapper;
    private final AuditConfirmCategoryMapper confirmCategoryMapper;
    private final AuditObjectLockMapper lockMapper;
    private final AuditReplayDispatcher replayDispatcher;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    public ApiResult<AuditCenterOverview> overview() {
        ensureSeedData();
        Map<String, PlatformConfigItem> configs = loadConfigMap(List.of(GROUP_A2));
        List<AuditOperationTicket> tickets = tickets();
        List<AuditOperationHistory> history = history(tickets);
        AuditStatsSummaryResponse todaySummary = statsSummary(1);
        return ApiResult.ok(new AuditCenterOverview(
                stats(tickets, history, todaySummary),
                tickets,
                history,
                mechanismParams(configs),
                confirmCategories(),
                recentLogs(),
                todaySummary,
                topActions()));
    }

    public int pendingOperationCountByActionMarker(String marker) {
        if (!StringUtils.hasText(marker)) {
            return 0;
        }
        ensureSeedData();
        String normalizedMarker = marker.trim();
        return (int) ticketMapper.selectList(new LambdaQueryWrapper<AuditOperationTicketEntity>()
                        .eq(AuditOperationTicketEntity::getIsDeleted, 0)
                        .eq(AuditOperationTicketEntity::getStatus, STATUS_PENDING))
                .stream()
                .filter(ticket -> ticket.getAction() != null && ticket.getAction().contains(normalizedMarker))
                .count();
    }

    @Transactional
    public ApiResult<AuditOperationTicket> approve(
            String idempotencyKey, String operationId, AuditOperationDecisionRequest request) {
        return decide(idempotencyKey, operationId, request, STATUS_APPROVED, "A2_OPERATION_APPROVED");
    }

    @Transactional
    public ApiResult<AuditOperationTicket> reject(
            String idempotencyKey, String operationId, AuditOperationDecisionRequest request) {
        return decide(idempotencyKey, operationId, request, STATUS_REJECTED, "A2_OPERATION_REJECTED");
    }

    @Transactional
    public ApiResult<AuditOperationTicket> createProposal(String idempotencyKey, AuditOperationProposalRequest request) {
        ensureSeedData();
        ApiResult<AuditOperationTicket> guard = requireMutation(idempotencyKey,
                request == null ? null : request.reason(),
                request == null ? null : request.operator());
        if (guard != null) {
            return guard;
        }
        String operationType;
        String action;
        String objectText;
        String beforeValue;
        String afterValue;
        String operator;
        String operatorRole;
        String roleGate;
        String reason;
        String sourceDomain;
        try {
            operationType = normalizeOperationType(request.type());
            action = normalizeLimitedText(request.action(), "ACTION_REQUIRED", 160);
            objectText = normalizeLimitedText(request.obj(), "OBJECT_REQUIRED", 255);
            beforeValue = normalizeOptionalText(request.beforeValue(), "—", 128);
            afterValue = normalizeOptionalText(request.afterValue(), "—", 128);
            operator = normalizeLimitedText(request.operator(), "OPERATOR_REQUIRED", 128);
            operatorRole = normalizeOptionalText(request.operatorRole(), "operator", 32);
            roleGate = normalizeOptionalText(request.roleGate(), "超管", 255);
            reason = normalizeLimitedText(request.reason(), "REASON_REQUIRED", 512);
            sourceDomain = normalizeOptionalText(request.sourceDomain(), "A2", 32);
        } catch (IllegalArgumentException ex) {
            return fail(OpsErrorCode.VALIDATION_FAILED, ex.getMessage());
        }

        AuditOperationTicketEntity ticket = new AuditOperationTicketEntity();
        ticket.setOperationId(nextOperationId());
        ticket.setAction(action);
        ticket.setObjectText(objectText);
        ticket.setBeforeValue(beforeValue);
        ticket.setAfterValue(afterValue);
        ticket.setOperatorName(operator);
        ticket.setOperatorRole(operatorRole);
        ticket.setOperationType(operationType);
        ticket.setAmplifies(Boolean.TRUE.equals(request.amplifies()) ? 1 : 0);
        ticket.setSos(Boolean.TRUE.equals(request.sos()) || "sos".equals(operationType) ? 1 : 0);
        ticket.setTimeLabel("刚刚");
        ticket.setMine(0);
        ticket.setRoleGate(roleGate);
        ticket.setReason(reason);
        ticket.setStatus(STATUS_PENDING);
        ticket.setIsDeleted(0);
        // 存回放指令(供 approve 时回放执行)
        AuditReplayCommand command = request.command();
        if (command != null) {
            try {
                ticket.setCommandJson(objectMapper.writeValueAsString(command));
            } catch (Exception ex) {
                return fail(OpsErrorCode.VALIDATION_FAILED, "COMMAND_SERIALIZE_FAILED");
            }
        }
        // 加目标对象锁(防 pending 期间重复发起)
        AuditLockTarget target = request.target();
        if (target != null) {
            int exists = lockMapper.countActiveByTarget(target.domain(), target.type(), target.id());
            if (exists > 0) {
                return ApiResult.fail(409, "OBJECT_ALREADY_PENDING");
            }
            AuditObjectLockEntity lock = new AuditObjectLockEntity();
            lock.setTicketId(ticket.getOperationId());
            lock.setTargetDomain(target.domain());
            lock.setTargetType(target.type());
            lock.setTargetId(target.id());
            lock.setOperator(operator);
            lock.setIsDeleted(0);
            try {
                lockMapper.insert(lock);
            } catch (org.springframework.dao.DataIntegrityViolationException ex) {
                // 并发命中 uk_target 唯一键:转 409 而非 500
                return ApiResult.fail(409, "OBJECT_ALREADY_PENDING");
            }
        }
        // 多锁扩展(J 域 batch_kill/emergency_block):per-target 循环插锁,任一冲突整单回滚
        java.util.List<AuditLockTarget> targets = request.targets();
        if (targets != null && !targets.isEmpty()) {
            for (AuditLockTarget t : targets) {
                int exists = lockMapper.countActiveByTarget(t.domain(), t.type(), t.id());
                if (exists > 0) {
                    return ApiResult.fail(409, "OBJECT_ALREADY_PENDING");
                }
            }
            for (AuditLockTarget t : targets) {
                AuditObjectLockEntity lock = new AuditObjectLockEntity();
                lock.setTicketId(ticket.getOperationId());
                lock.setTargetDomain(t.domain());
                lock.setTargetType(t.type());
                lock.setTargetId(t.id());
                lock.setOperator(operator);
                lock.setIsDeleted(0);
                try {
                    lockMapper.insert(lock);
                } catch (org.springframework.dao.DataIntegrityViolationException ex) {
                    return ApiResult.fail(409, "OBJECT_ALREADY_PENDING");
                }
            }
        }
        ticketMapper.insert(ticket);

        auditLogService.record(AuditLogWriteRequest.builder()
                .action("A2_OPERATION_PROPOSED")
                .resourceType("A2_OPERATION")
                .resourceId(ticket.getOperationId())
                .bizNo(ticket.getOperationId())
                .actorType("ADMIN")
                .actorUsername(ticket.getOperatorName())
                .result("SUCCESS")
                .riskLevel(operationRiskLevel(ticket))
                .detail(Map.of(
                        "operationId", ticket.getOperationId(),
                        "action", ticket.getAction(),
                        "resource", ticket.getObjectText(),
                        "before", ticket.getBeforeValue(),
                        "after", ticket.getAfterValue(),
                        "type", ticket.getOperationType(),
                        "sourceDomain", sourceDomain,
                        "reason", ticket.getReason(),
                        "idempotencyKey", idempotencyKey.trim()))
                .build());
        return ApiResult.ok(toTicket(ticket));
    }

    @Transactional
    public ApiResult<AuditOperationTicket> recordExecuted(
            String idempotencyKey, AuditOperationProposalRequest request) {
        // 事后台账:高敏操作已即时执行(单人确认,CLAUDE.md 双签已取消),
        // 建 status=approved 的票作为"已执行留痕",而非 pending 待审。
        ensureSeedData();
        ApiResult<AuditOperationTicket> guard = requireMutation(idempotencyKey,
                request == null ? null : request.reason(),
                request == null ? null : request.operator());
        if (guard != null) {
            return guard;
        }
        String operationType;
        String action;
        String objectText;
        String beforeValue;
        String afterValue;
        String operator;
        String operatorRole;
        String roleGate;
        String reason;
        String sourceDomain;
        try {
            operationType = normalizeOperationType(request.type());
            action = normalizeLimitedText(request.action(), "ACTION_REQUIRED", 160);
            objectText = normalizeLimitedText(request.obj(), "OBJECT_REQUIRED", 255);
            beforeValue = normalizeOptionalText(request.beforeValue(), "—", 128);
            afterValue = normalizeOptionalText(request.afterValue(), "—", 128);
            operator = normalizeLimitedText(request.operator(), "OPERATOR_REQUIRED", 128);
            operatorRole = normalizeOptionalText(request.operatorRole(), "operator", 32);
            roleGate = normalizeOptionalText(request.roleGate(), "超管", 255);
            reason = normalizeLimitedText(request.reason(), "REASON_REQUIRED", 512);
            sourceDomain = normalizeOptionalText(request.sourceDomain(), "A2", 32);
        } catch (IllegalArgumentException ex) {
            return fail(OpsErrorCode.VALIDATION_FAILED, ex.getMessage());
        }

        AuditOperationTicketEntity ticket = new AuditOperationTicketEntity();
        ticket.setOperationId(nextOperationId());
        ticket.setAction(action);
        ticket.setObjectText(objectText);
        ticket.setBeforeValue(beforeValue);
        ticket.setAfterValue(afterValue);
        ticket.setOperatorName(operator);
        ticket.setOperatorRole(operatorRole);
        ticket.setOperationType(operationType);
        ticket.setAmplifies(Boolean.TRUE.equals(request.amplifies()) ? 1 : 0);
        ticket.setSos(Boolean.TRUE.equals(request.sos()) || "sos".equals(operationType) ? 1 : 0);
        ticket.setTimeLabel("刚刚");
        ticket.setMine(0);
        ticket.setRoleGate(roleGate);
        ticket.setReason(reason);
        ticket.setStatus(STATUS_APPROVED);
        ticket.setDecisionReason("即时执行·已生效(单人确认)");
        ticket.setDecidedAt(LocalDateTime.now());
        ticket.setIsDeleted(0);
        ticketMapper.insert(ticket);

        auditLogService.record(AuditLogWriteRequest.builder()
                .action("A2_OPERATION_EXECUTED")
                .resourceType("A2_OPERATION")
                .resourceId(ticket.getOperationId())
                .bizNo(ticket.getOperationId())
                .actorType("ADMIN")
                .actorUsername(ticket.getOperatorName())
                .result("SUCCESS")
                .riskLevel(operationRiskLevel(ticket))
                .detail(Map.of(
                        "operationId", ticket.getOperationId(),
                        "action", ticket.getAction(),
                        "resource", ticket.getObjectText(),
                        "before", ticket.getBeforeValue(),
                        "after", ticket.getAfterValue(),
                        "type", ticket.getOperationType(),
                        "sourceDomain", sourceDomain,
                        "reason", ticket.getReason(),
                        "status", STATUS_APPROVED,
                        "idempotencyKey", idempotencyKey.trim()))
                .build());
        return ApiResult.ok(toTicket(ticket));
    }

    public ApiResult<AuditMechanismParam> updateMechanismParam(
            String idempotencyKey, String paramKey, AuditMechanismParamUpdateRequest request) {
        ensureSeedData();
        ApiResult<AuditMechanismParam> guard = requireMutation(idempotencyKey, request == null ? null : request.reason(),
                request == null ? null : request.operator());
        if (guard != null) {
            return guard;
        }
        MechanismConfig config = mechanismConfig(paramKey);
        if (config == null || config.locked()) {
            return fail(OpsErrorCode.VALIDATION_FAILED, "A2_MECHANISM_PARAM_NOT_MUTABLE");
        }
        String normalizedValue;
        try {
            normalizedValue = normalizeMechanismValue(config.key(), request.value());
        } catch (IllegalArgumentException ex) {
            return fail(OpsErrorCode.VALIDATION_FAILED, ex.getMessage());
        }
        PlatformConfigItem saved = saveConfig(config.configKey(), normalizedValue, config.remark(request.reason()));
        auditLogService.record(AuditLogWriteRequest.builder()
                .action("A2_MECHANISM_PARAM_CHANGED")
                .resourceType("A2_MECHANISM_PARAM")
                .resourceId(config.key())
                .actorType("ADMIN")
                .actorUsername(request.operator().trim())
                .result("SUCCESS")
                .riskLevel("MEDIUM")
                .detail(Map.of(
                        "paramKey", config.key(),
                        "configKey", config.configKey(),
                        "value", normalizedValue,
                        "reason", request.reason().trim(),
                        "idempotencyKey", idempotencyKey.trim()))
                .build());
        return ApiResult.ok(paramView(config, saved.configValue()));
    }

    private ApiResult<AuditOperationTicket> decide(
            String idempotencyKey,
            String operationId,
            AuditOperationDecisionRequest request,
            String nextStatus,
            String auditAction) {
        ensureSeedData();
        ApiResult<AuditOperationTicket> guard = requireMutation(idempotencyKey, request == null ? null : request.reason(),
                request == null ? null : request.operator());
        if (guard != null) {
            return guard;
        }
        AuditOperationTicketEntity ticket = ticket(operationId).orElse(null);
        if (ticket == null) {
            return fail(OpsErrorCode.VALIDATION_FAILED, "A2_OPERATION_NOT_FOUND");
        }
        if (!STATUS_PENDING.equals(status(ticket.getStatus()))) {
            return fail(OpsErrorCode.INVALID_STATE_TRANSITION, "A2_OPERATION_ALREADY_TERMINAL");
        }
        // approve 才回放目标域;reject/withdrawn 只删锁不回放(原值天然保持)
        if (STATUS_APPROVED.equals(nextStatus)) {
            AuditReplayCommand cmd = deserializeCommand(ticket.getCommandJson());
            if (cmd == null) {
                return fail(OpsErrorCode.VALIDATION_FAILED, "COMMAND_REQUIRED");
            }
            AuditReplayContext ctx = new AuditReplayContext(
                    request.operator().trim(), request.reason().trim(), idempotencyKey.trim());
            A2ReplayContext.enterReplay();
            try {
                ApiResult<?> replayResult = replayDispatcher.dispatch(cmd, ctx);
                if (replayResult.getCode() != 0) {
                    // 回放失败:不删锁、不改状态、目标域域方法已 return fail 未写入 → ticket 保持 pending,原值保持
                    return ApiResult.fail(replayResult.getCode(), replayResult.getMessage());
                }
            } finally {
                A2ReplayContext.exitReplay();
            }
            releaseLock(ticket.getOperationId());
        } else {
            // reject / withdrawn:删锁,目标域不动(原值恢复)
            releaseLock(ticket.getOperationId());
        }
        ticket.setStatus(nextStatus);
        ticket.setDecisionReason(request.reason().trim());
        ticket.setDecidedAt(LocalDateTime.now());
        ticketMapper.updateById(ticket);
        auditLogService.record(AuditLogWriteRequest.builder()
                .action(auditAction)
                .resourceType("A2_OPERATION")
                .resourceId(ticket.getOperationId())
                .bizNo(ticket.getOperationId())
                .actorType("ADMIN")
                .actorUsername(request.operator().trim())
                .result("SUCCESS")
                .riskLevel(operationRiskLevel(ticket))
                .detail(decisionDetail(ticket, nextStatus, idempotencyKey, request.reason()))
                .build());
        return ApiResult.ok(toTicket(ticket));
    }

    private String operationRiskLevel(AuditOperationTicketEntity ticket) {
        if (ticket == null) {
            return "MEDIUM";
        }
        return isTrue(ticket.getAmplifies()) || isTrue(ticket.getSos()) || "acct".equals(ticket.getOperationType())
                ? "HIGH"
                : "MEDIUM";
    }

    private Map<String, Object> decisionDetail(
            AuditOperationTicketEntity ticket, String status, String idempotencyKey, String reason) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("operationId", ticket.getOperationId());
        detail.put("status", status);
        detail.put("action", ticket.getAction());
        detail.put("resource", ticket.getObjectText());
        detail.put("before", ticket.getBeforeValue());
        detail.put("after", ticket.getAfterValue());
        detail.put("delta", ticket.getBeforeValue() + " -> " + ticket.getAfterValue());
        detail.put("type", ticket.getOperationType());
        detail.put("amplifies", isTrue(ticket.getAmplifies()));
        detail.put("sos", isTrue(ticket.getSos()));
        detail.put("reason", reason.trim());
        detail.put("idempotencyKey", idempotencyKey.trim());
        detail.put("domain", inferAuditDomain(ticket.getAction(), ticket.getObjectText()));
        return detail;
    }

    private <T> ApiResult<T> requireMutation(String idempotencyKey, String reason, String operator) {
        if (!StringUtils.hasText(idempotencyKey)) {
            return fail(OpsErrorCode.IDEMPOTENCY_KEY_REQUIRED, OpsErrorCode.IDEMPOTENCY_KEY_REQUIRED.name());
        }
        if (!StringUtils.hasText(reason)) {
            return fail(OpsErrorCode.REASON_REQUIRED, OpsErrorCode.REASON_REQUIRED.name());
        }
        if (!StringUtils.hasText(operator)) {
            return fail(OpsErrorCode.VALIDATION_FAILED, "OPERATOR_REQUIRED");
        }
        return null;
    }

    /** 释放目标对象锁(approve/reject 后调用,物理删除 nx_audit_object_lock 行,放开 uk_target 唯一键).单锁/多锁均按 ticketId 全删。 */
    private void releaseLock(String operationId) {
        java.util.List<AuditObjectLockEntity> locks = lockMapper.selectActiveByTicketId(operationId);
        for (AuditObjectLockEntity lock : locks) {
            lockMapper.deleteById(lock.getId());
        }
    }

    /** 反序列化回放指令;空或解析失败返回 null。 */
    private AuditReplayCommand deserializeCommand(String commandJson) {
        if (!StringUtils.hasText(commandJson)) {
            return null;
        }
        try {
            return objectMapper.readValue(commandJson, AuditReplayCommand.class);
        } catch (Exception ex) {
            return null;
        }
    }

    private Map<String, PlatformConfigItem> loadConfigMap(Collection<String> groups) {
        return configRepository.findActiveByGroups(groups).stream()
                .collect(Collectors.toMap(
                        PlatformConfigItem::configKey,
                        item -> item,
                        (left, right) -> left,
                        LinkedHashMap::new));
    }

    private List<AuditOperationTicket> tickets() {
        return ticketMapper.selectList(new LambdaQueryWrapper<AuditOperationTicketEntity>()
                        .eq(AuditOperationTicketEntity::getIsDeleted, 0)
                        .orderByDesc(AuditOperationTicketEntity::getCreatedAt)
                        .orderByDesc(AuditOperationTicketEntity::getId))
                .stream()
                .map(this::toTicket)
                .toList();
    }

    private AuditOperationTicket toTicket(AuditOperationTicketEntity row) {
        return new AuditOperationTicket(
                row.getOperationId(),
                row.getAction(),
                row.getObjectText(),
                row.getBeforeValue(),
                row.getAfterValue(),
                row.getOperatorName(),
                row.getOperatorRole(),
                row.getOperationType(),
                isTrue(row.getAmplifies()),
                isTrue(row.getSos()),
                row.getTimeLabel(),
                isTrue(row.getMine()),
                row.getRoleGate(),
                row.getReason(),
                status(row.getStatus()));
    }

    private List<AuditOperationHistory> history(List<AuditOperationTicket> tickets) {
        List<AuditOperationHistory> rows = new ArrayList<>();
        terminalTickets().stream()
                .map(ticket -> new AuditOperationHistory(
                        ticket.getOperationId(),
                        ticket.getAction(),
                        status(ticket.getStatus()),
                        ticket.getOperatorName() + " · reason + admin.operation_" + terminalAction(status(ticket.getStatus())),
                        ticket.getDecidedAt() == null ? LocalDateTime.now().format(ISO) : ticket.getDecidedAt().format(ISO),
                        "原因:" + (StringUtils.hasText(ticket.getDecisionReason())
                                ? ticket.getDecisionReason()
                                : ticket.getReason())))
                .forEach(rows::add);
        historyMapper.selectList(new LambdaQueryWrapper<AuditOperationHistoryEntity>()
                        .eq(AuditOperationHistoryEntity::getIsDeleted, 0)
                        .orderByDesc(AuditOperationHistoryEntity::getId))
                .stream()
                .map(row -> new AuditOperationHistory(
                        row.getOperationId(),
                        row.getAction(),
                        status(row.getStatus()),
                        row.getChainText(),
                        row.getTimeLabel(),
                        row.getNote()))
                .forEach(rows::add);
        return rows;
    }

    private String terminalAction(String status) {
        return STATUS_APPROVED.equals(status) ? "confirmed" : STATUS_REJECTED.equals(status) ? "rejected" : status;
    }

    private AuditOperationStats stats(
            List<AuditOperationTicket> tickets,
            List<AuditOperationHistory> history,
            AuditStatsSummaryResponse todaySummary) {
        long todayTotal = todaySummary == null || todaySummary.getTotal() == null ? 0L : todaySummary.getTotal();
        return new AuditOperationStats(
                (int) tickets.stream().filter(ticket -> STATUS_PENDING.equals(ticket.status())).count(),
                (int) tickets.stream().filter(ticket -> STATUS_PENDING.equals(ticket.status()) && "fund".equals(ticket.type())).count(),
                (int) tickets.stream().filter(ticket -> STATUS_PENDING.equals(ticket.status()) && ticket.sos()).count(),
                todayTotal,
                countHistory(history, STATUS_APPROVED),
                countHistory(history, STATUS_REJECTED),
                countHistory(history, "expired"),
                countHistory(history, "withdrawn"));
    }

    private int countHistory(List<AuditOperationHistory> history, String status) {
        return (int) history.stream().filter(row -> status.equals(row.st())).count();
    }

    private List<AuditMechanismParam> mechanismParams(Map<String, PlatformConfigItem> configs) {
        return List.of(
                new AuditMechanismParam("reason_required", "理由必填",
                        "所有高敏动作的确认弹窗都校验 reason,为空或过短不写入", "强制", true),
                paramView(mechanismConfig("ttl"), value(configs, "admin.a2.reason_min_chars", "8 字")),
                paramView(mechanismConfig("retention"), value(configs, "admin.a2.retention_months", "13 个月")),
                new AuditMechanismParam("confirm_list", "操作确认适用动作清单",
                        "哪些动作必须打开独立确认弹窗,汇总自各域", "9 大类", false),
                paramView(mechanismConfig("schema"), value(configs, "admin.a2.schema_version", "v3")));
    }

    private List<AuditConfirmCategory> confirmCategories() {
        return confirmCategoryMapper.selectList(new LambdaQueryWrapper<AuditConfirmCategoryEntity>()
                        .eq(AuditConfirmCategoryEntity::getIsDeleted, 0)
                        .orderByAsc(AuditConfirmCategoryEntity::getSortOrder)
                        .orderByAsc(AuditConfirmCategoryEntity::getId))
                .stream()
                .map(row -> new AuditConfirmCategory(row.getCategoryName(), row.getExamples(), row.getRoleGate()))
                .toList();
    }

    private AuditMechanismParam paramView(MechanismConfig config, String value) {
        String displayValue = "schema".equals(config.key())
                ? (StringUtils.hasText(value) ? "统一 schema · " + normalizeSchemaValue(value) : "")
                : value;
        return new AuditMechanismParam(config.key(), config.name(), config.sub(), displayValue, config.locked());
    }

    private MechanismConfig mechanismConfig(String key) {
        if (!StringUtils.hasText(key)) {
            return null;
        }
        String normalized = normalizeText(key).toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "ttl" -> new MechanismConfig("ttl", "理由最短长度",
                    "默认 8 字;用于确认弹窗提交前校验,历史审计原文保留", "admin.a2.reason_min_chars", false);
            case "retention" -> new MechanismConfig("retention", "日志保留期",
                    "覆盖整个 12 月运营周期 + 1 个月缓冲,合规取证底线", "admin.a2.retention_months", false);
            case "schema" -> new MechanismConfig("schema", "审计字段结构",
                    "全后台统一一套字段;加事件/加属性走注册流程", "admin.a2.schema_version", false);
            default -> null;
        };
    }

    private String normalizeMechanismValue(String key, String value) {
        return switch (key) {
            case "ttl" -> normalizeReasonMin(value);
            case "retention" -> normalizeRetention(value);
            case "schema" -> normalizeSchemaValue(value);
            default -> normalizeText(value);
        };
    }

    private String normalizeReasonMin(String value) {
        int chars = parsePositiveInt(value, "reason min chars");
        if (chars < 8 || chars > 200) {
            throw new IllegalArgumentException("reason min chars must be 8-200");
        }
        return chars + " 字";
    }

    private String normalizeRetention(String value) {
        int months = parsePositiveInt(value, "audit retention months");
        if (months < 13 || months > 36) {
            throw new IllegalArgumentException("audit retention months must be 13-36");
        }
        return months + " 个月";
    }

    private String normalizeSchemaValue(String value) {
        String normalized = normalizeText(value)
                .replace("统一 schema ·", "")
                .replace("统一 schema", "")
                .replace("schema", "")
                .trim();
        if (!StringUtils.hasText(normalized)) {
            throw new IllegalArgumentException("schema version is required");
        }
        if (normalized.length() > 32) {
            throw new IllegalArgumentException("schema version is too long");
        }
        return normalized;
    }

    private int parsePositiveInt(String value, String label) {
        String digits = normalizeText(value).replaceAll("[^0-9]", "");
        if (!StringUtils.hasText(digits)) {
            throw new IllegalArgumentException(label + " is required");
        }
        return Integer.parseInt(digits);
    }

    private String value(Map<String, PlatformConfigItem> configs, String key, String fallback) {
        PlatformConfigItem item = configs.get(key);
        if (item != null && StringUtils.hasText(item.configValue())) {
            return item.configValue();
        }
        return "";
    }

    private String status(String value) {
        if (!StringUtils.hasText(value)) {
            return STATUS_PENDING;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return TERMINAL_STATUSES.contains(normalized) || STATUS_PENDING.equals(normalized) ? normalized : STATUS_PENDING;
    }

    private String nextOperationId() {
        return "WO-" + DateTimeFormatter.ofPattern("yyMMddHHmmssSSS").format(LocalDateTime.now())
                + "-" + Math.floorMod(System.nanoTime(), 1000);
    }

    private String normalizeOperationType(String value) {
        String normalized = normalizeLimitedText(value, "OPERATION_TYPE_REQUIRED", 32).toLowerCase(Locale.ROOT);
        if (!Set.of("fund", "param", "acct", "sos").contains(normalized)) {
            throw new IllegalArgumentException("A2_OPERATION_TYPE_INVALID");
        }
        return normalized;
    }

    private String normalizeOptionalText(String value, String fallback, int maxLength) {
        if (!StringUtils.hasText(value)) {
            return fallback;
        }
        return trimToLength(value, maxLength);
    }

    private String normalizeLimitedText(String value, String message, int maxLength) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException(message);
        }
        return trimToLength(value, maxLength);
    }

    private String trimToLength(String value, int maxLength) {
        String normalized = value.trim();
        if (normalized.length() > maxLength) {
            return normalized.substring(0, maxLength);
        }
        return normalized;
    }

    private PlatformConfigItem saveConfig(String configKey, String value, String remark) {
        LocalDateTime now = LocalDateTime.now();
        PlatformConfigItem existing = configRepository.findActiveByKey(configKey).orElseGet(() ->
                new PlatformConfigItem(null, configKey, value, "STRING", GROUP_A2, "ADMIN", remark, 1, now, now));
        return configRepository.save(existing.withValue(value, GROUP_A2, remark, 1));
    }

    private void ensureSeedData() {
        ticketMapper.createTicketTable();
        historyMapper.createHistoryTable();
        confirmCategoryMapper.createConfirmCategoryTable();
        lockMapper.createLockTable();
        if (readTimeBusinessSeedsDisabled()) {
            return;
        }
        if (!readTimeSeedPolicy.enabled()) {
            return;
        }
        seedTicketsIfEmpty();
        seedHistoryIfEmpty();
        seedConfirmCategoriesIfEmpty();
        seedMechanismConfigsIfMissing();
        seedAuditLogsIfEmpty();
    }

    private boolean readTimeBusinessSeedsDisabled() {
        return true;
    }

    private void seedTicketsIfEmpty() {
        Long total = ticketMapper.selectCount(new LambdaQueryWrapper<AuditOperationTicketEntity>()
                .eq(AuditOperationTicketEntity::getIsDeleted, 0));
        if (total != null && total > 0) {
            return;
        }
        OPERATION_SEEDS.stream()
                .map(this::ticketEntity)
                .forEach(ticketMapper::insert);
    }

    private AuditOperationTicketEntity ticketEntity(OperationSeed seed) {
        AuditOperationTicketEntity entity = new AuditOperationTicketEntity();
        entity.setOperationId(seed.id());
        entity.setAction(seed.action());
        entity.setObjectText(seed.obj());
        entity.setBeforeValue(seed.beforeValue());
        entity.setAfterValue(seed.afterValue());
        entity.setOperatorName(seed.operator());
        entity.setOperatorRole(seed.operatorRole());
        entity.setOperationType(seed.type());
        entity.setAmplifies(seed.amplifies() ? 1 : 0);
        entity.setSos(seed.sos() ? 1 : 0);
        entity.setTimeLabel(seed.ts());
        entity.setMine(seed.mine() ? 1 : 0);
        entity.setRoleGate(seed.roleGate());
        entity.setReason(seed.reason());
        entity.setStatus(STATUS_PENDING);
        entity.setIsDeleted(0);
        return entity;
    }

    private void seedHistoryIfEmpty() {
        Long total = historyMapper.selectCount(new LambdaQueryWrapper<AuditOperationHistoryEntity>()
                .eq(AuditOperationHistoryEntity::getIsDeleted, 0));
        if (total != null && total > 0) {
            return;
        }
        DEFAULT_HISTORY.stream()
                .map(this::historyEntity)
                .forEach(historyMapper::insert);
    }

    private AuditOperationHistoryEntity historyEntity(AuditOperationHistory row) {
        AuditOperationHistoryEntity entity = new AuditOperationHistoryEntity();
        entity.setOperationId(row.id());
        entity.setAction(row.action());
        entity.setStatus(row.st());
        entity.setChainText(row.chain());
        entity.setTimeLabel(row.t());
        entity.setNote(row.note());
        entity.setIsDeleted(0);
        return entity;
    }

    private void seedConfirmCategoriesIfEmpty() {
        Long total = confirmCategoryMapper.selectCount(new LambdaQueryWrapper<AuditConfirmCategoryEntity>()
                .eq(AuditConfirmCategoryEntity::getIsDeleted, 0));
        if (total != null && total > 0) {
            return;
        }
        for (int i = 0; i < CONFIRM_CATEGORY_SEEDS.size(); i++) {
            confirmCategoryMapper.insert(confirmCategoryEntity(CONFIRM_CATEGORY_SEEDS.get(i), i + 1));
        }
    }

    private AuditConfirmCategoryEntity confirmCategoryEntity(AuditConfirmCategory row, int sortOrder) {
        AuditConfirmCategoryEntity entity = new AuditConfirmCategoryEntity();
        entity.setCategoryName(row.cat());
        entity.setExamples(row.examples());
        entity.setRoleGate(row.roleGate());
        entity.setSortOrder(sortOrder);
        entity.setIsDeleted(0);
        return entity;
    }

    private void seedMechanismConfigsIfMissing() {
        // Intentionally empty: A2 reads existing configuration only.
    }

    private void seedConfigIfMissing(String key, String value, String remark) {
        // Intentionally empty: A2 reads existing configuration only.
    }

    private void seedAuditLogsIfEmpty() {
        // Intentionally empty: A2 reads audit logs from the audit table only.
    }

    private Optional<AuditOperationTicketEntity> ticket(String operationId) {
        String normalized = normalizeText(operationId).toUpperCase(Locale.ROOT);
        return Optional.ofNullable(ticketMapper.selectActiveByOperationId(normalized));
    }

    private List<AuditOperationTicketEntity> terminalTickets() {
        return ticketMapper.selectList(new LambdaQueryWrapper<AuditOperationTicketEntity>()
                .eq(AuditOperationTicketEntity::getIsDeleted, 0)
                .in(AuditOperationTicketEntity::getStatus, TERMINAL_STATUSES)
                .orderByDesc(AuditOperationTicketEntity::getDecidedAt)
                .orderByDesc(AuditOperationTicketEntity::getId));
    }

    private boolean isTrue(Integer value) {
        return value != null && value == 1;
    }

    private String inferAuditDomain(String action, String objectText) {
        String source = ((action == null ? "" : action) + " " + (objectText == null ? "" : objectText)).toUpperCase(Locale.ROOT);
        if (source.contains("D2") || source.contains("D5") || source.contains("提现") || source.contains("账单")) {
            return "D";
        }
        if (source.contains("C2") || source.contains("C3") || source.contains("账户") || source.contains("余额")) {
            return "C";
        }
        if (source.contains("H1") || source.contains("PHASE")) {
            return "H";
        }
        if (source.contains("I5") || source.contains("I7") || source.contains("课程") || source.contains("披露")) {
            return "I";
        }
        return "A";
    }

    private AuditStatsSummaryResponse statsSummary(int days) {
        AuditStatsQueryRequest request = new AuditStatsQueryRequest();
        request.setDays(days);
        AuditStatsSummaryResponse response = auditLogService.summary(request);
        if (response != null) {
            return response;
        }
        AuditStatsSummaryResponse empty = new AuditStatsSummaryResponse();
        empty.setStartAt(LocalDateTime.now().minusDays(days));
        empty.setEndAt(LocalDateTime.now());
        empty.setTotal(0L);
        empty.setByResult(List.of());
        empty.setByRiskLevel(List.of());
        return empty;
    }

    private List<AuditLogRecord> recentLogs() {
        AuditLogQueryRequest request = new AuditLogQueryRequest();
        request.setLimit(20);
        List<AuditLogRecord> rows = auditLogService.list(request);
        return rows == null ? List.of() : rows;
    }

    private List<AuditStatsBucket> topActions() {
        AuditStatsQueryRequest request = new AuditStatsQueryRequest();
        request.setDays(7);
        request.setLimit(10);
        List<AuditStatsBucket> rows = auditLogService.topActions(request);
        return rows == null ? List.of() : rows;
    }

    private String normalizeText(String value) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException("value is required");
        }
        return value.trim();
    }

    private <T> ApiResult<T> fail(OpsErrorCode errorCode, String message) {
        return ApiResult.fail(errorCode.httpStatus(), message);
    }

    private <T> ApiResult<T> fail(OpsErrorCode errorCode) {
        return fail(errorCode, errorCode.name());
    }

    private static OperationSeed op(
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
            String reason) {
        return new OperationSeed(id, action, obj, beforeValue, afterValue, operator, operatorRole, type, amplifies, sos,
                ts, mine, roleGate, reason);
    }

    private static AuditLogSeed audit(
            String ts,
            String actor,
            String role,
            String action,
            String obj,
            String delta,
            String domain,
            String ip,
            String resourceType) {
        return new AuditLogSeed(ts, actor, role, action, obj, delta, domain, ip, resourceType);
    }

    private record OperationSeed(
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
            String reason) {
    }

    private record AuditLogSeed(
            String ts,
            String actor,
            String role,
            String action,
            String obj,
            String delta,
            String domain,
            String ip,
            String resourceType) {
        String riskLevel() {
            return "D".equals(domain) || "A".equals(domain) ? "HIGH" : "MEDIUM";
        }

        String resourceId() {
            return obj;
        }
    }

    private record MechanismConfig(
            String key,
            String name,
            String sub,
            String configKey,
            boolean locked) {
        String remark(String reason) {
            return "A2 mechanism param change: " + reason.trim();
        }
    }
}
