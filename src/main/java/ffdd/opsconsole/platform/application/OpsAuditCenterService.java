package ffdd.opsconsole.platform.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import ffdd.opsconsole.common.api.OpsErrorCode;
import ffdd.opsconsole.common.boundary.ApplicationService;
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
import ffdd.opsconsole.platform.infrastructure.AuditConfirmCategoryEntity;
import ffdd.opsconsole.platform.infrastructure.AuditOperationHistoryEntity;
import ffdd.opsconsole.platform.infrastructure.AuditOperationTicketEntity;
import ffdd.opsconsole.platform.mapper.AuditConfirmCategoryMapper;
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

    private static final List<OperationSeed> OPERATION_SEEDS = List.of(
            op("WO-8852", "提现放行(大额操作确认)", "usr-7F21 · $8,200", "review", "approved",
                    "郑爽(财务)", "finance", "fund", true, false, "2m", false,
                    "财务 lead / 超管", "用户工单 #4471 核实补偿;依据已附"),
            op("WO-8851", "账单手工调整", "bill-2K8842 · 误扣冲正", "-", "+$214.00",
                    "吴桐(财务 lead)", "finance", "fund", true, false, "18m", false,
                    "财务 lead / 超管", "客服工单 #4488 误扣证据齐全"),
            op("WO-8850", "J1 熔断恢复 · exchange 闸", "J 域 · 兑换能力", "disabled", "enabled",
                    "王磊(风控 lead)", "risk", "sos", false, true, "42m", false,
                    "超管(应急轨)", "风险事件已闭环,SLA 内恢复"),
            op("WO-8849", "课程奖励上调(I7)", "genesis-nodes · +40→+45 NEX", "+40", "+45",
                    "李文(内容 lead)", "content", "fund", true, false, "36m", false,
                    "内容 lead / 超管", "P3 阶段教育引流提升,B1 覆盖率核验通过"),
            op("WO-8848", "余额调整(客服小额)", "usr-9C03 · 补偿", "$0", "+$36.00",
                    "刘佳(客服)", "support", "fund", true, false, "34m", false,
                    "财务 lead / 超管", "客诉 ticket #5512 已核实"),
            op("WO-8847", "Phase dial · 周任务倍率", "H1 · P3 月档", "1.25x", "1.30x",
                    "高翔(增长)", "growth", "param", true, false, "1h", false,
                    "对应域 lead / 超管(dial 放大方向加风控 lead)", "本周 KPI 节奏校准,7 日窗口实验"),
            op("WO-8846", "OTP 发送频次(C6)", "注册风控", "3 次/小时", "5 次/小时",
                    "许晴(风控)", "risk", "param", false, false, "2h", false,
                    "风控 lead / 超管", "正常用户重发投诉激增,放宽频控"),
            op("WO-8845", "风险模型权重(K4)", "多账户信号权重", "0.32", "0.40",
                    "王磊(风控 lead)", "risk", "param", false, false, "3h", false,
                    "超管 / 风控 lead", "K1 簇击中样本回归,权重需上调"),
            op("WO-8844", "账户冻结(C2)", "usr-1A77 · 套利簇关联", "active", "frozen",
                    "许晴(风控)", "risk", "acct", false, false, "4h", false,
                    "风控 lead / 超管", "K1 多账户簇命中,套利路径已闭合证据"),
            op("WO-8843", "披露新版发布(I5)", "SFC · v12→v13", "v12", "v13",
                    "王磊(风控 lead)", "risk", "acct", false, false, "5h", false,
                    "风控 lead / 超管", "监管发函要求条款更新,7 日内全量重确认"),
            op("WO-8842", "运营账号创建(A1)", "新风控成员", "-", "op-072",
                    "赵敏(超管)", "super", "acct", false, false, "6h", false,
                    "超管", "新员工入职,风控成员岗 op-072"),
            op("WO-8840", "feature flag · 灰度 20%", "新提现页灰度", "off", "20%",
                    "高翔(增长)", "growth", "param", false, false, "8h", false,
                    "超管", "新提现 UX A/B,灰度 20% 7 日观察"),
            op("WO-8839", "储备注入登记(B1/D3)", "+$500K 入储备池", "-", "+$500K",
                    "吴桐(财务 lead)", "finance", "fund", false, false, "12h", false,
                    "财务 lead / 超管", "财务季度调拨,提升 B1 覆盖率 +5pp"),
            op("WO-8838", "提现参数 · 冷却时长(D5)", "非 Phase 参数", "48h", "36h",
                    "吴桐(财务 lead)", "finance", "param", true, false, "14h", true,
                    "财务 lead / 超管", "缩短冷却提升用户体验,逐步降低摩擦"));

    private static final List<AuditOperationHistory> DEFAULT_HISTORY = List.of(
            new AuditOperationHistory("WO-8841", "提现放行 $12,400", STATUS_APPROVED,
                    "吴桐(财务 lead) · reason + admin.operation_confirmed", "今天 09:21",
                    "原因:大额操作确认线上人工核验,KYC 与风险分均过"),
            new AuditOperationHistory("WO-8836", "幸运 2x 概率 5%→8%", STATUS_REJECTED,
                    "吴桐(财务 lead) · reason + admin.operation_rejected", "今天 08:40",
                    "取消原因:本周代币流出已超预算 12%,下周再议"),
            new AuditOperationHistory("WO-8830", "余额调整 +$50", "withdrawn",
                    "刘佳(客服) · reason + admin.operation_cancelled", "昨天 18:02",
                    "取消原因:用户工单重复,已有在途补偿"),
            new AuditOperationHistory("WO-8798", "OTP 频次参数", "expired",
                    "许晴(风控) · reason 校验未通过", "06-08 00:00",
                    "作废事件落审计(admin.operation_expired)"));

    private static final List<AuditLogSeed> AUDIT_LOG_SEEDS = List.of(
            audit("今天 10:12", "王磊", "风控 lead", "killswitch_toggled", "J1 · exchange 闸",
                    "enabled -> disabled", "A", "10.2.3.21", "A2_AUDIT_ADMIN"),
            audit("今天 09:21", "吴桐", "财务 lead", "withdraw_approved", "D2 · usr-7F21 $12,400",
                    "review -> sent", "D", "10.2.3.31", "A2_AUDIT_WITHDRAW"),
            audit("今天 09:02", "李文", "内容 lead", "content_published", "I1 · home.conversionBanner",
                    "v7 -> v8", "I", "10.2.3.41", "A2_AUDIT_CONTENT"),
            audit("今天 08:55", "陈锐", "超管", "operator_role_changed", "A1 · op-041",
                    "增长 -> 增长(lead)", "A", "10.2.3.11", "A2_AUDIT_ADMIN"),
            audit("今天 08:40", "吴桐", "财务 lead", "operation_rejected", "H5 · 幸运 2x 概率",
                    "5% -> 8%(驳回)", "H", "10.2.3.31", "A2_AUDIT_OPERATION"),
            audit("昨天 21:18", "许晴", "风控", "user_frozen", "C2 · usr-1A77",
                    "active -> frozen", "C", "10.2.3.22", "A2_AUDIT_USER"),
            audit("昨天 18:02", "刘佳", "客服", "operation_withdrawn", "C3 · usr-9C03 余额",
                    "+$36(撤回)", "C", "10.2.3.51", "A2_AUDIT_OPERATION"),
            audit("昨天 15:44", "高翔", "增长", "phase_dial_changed", "H1 · 周任务倍率",
                    "1.20x -> 1.25x", "H", "10.2.3.41", "A2_AUDIT_PHASE"));

    private static final List<AuditConfirmCategory> CONFIRM_CATEGORY_SEEDS = List.of(
            new AuditConfirmCategory("资金/资产调整", "余额增减(C3)· 手工账单(D4)· 储备注入(B1/D3)· 对账核销(D1)", "财务 lead / 超管"),
            new AuditConfirmCategory("大额资金放行", "提现放行/冻结/退款(D2)· 渠道退款(D1)", "财务 lead / 超管"),
            new AuditConfirmCategory("参数批改", "红黄线(B1)· 提现参数(D5)· OTP/锁定(C6)· Phase dial(H1)· 试用敏感参数(H2)", "对应域 lead / 超管(dial 放大方向加风控 lead)"),
            new AuditConfirmCategory("风险模型/KYC 裁决", "K4 权重分档(执行门槛升超管)· K5 大额复审", "超管 / 风控 lead"),
            new AuditConfirmCategory("熔断闸", "6 功能闸 + 地区屏蔽(J1/J2 管理面)", "超管"),
            new AuditConfirmCategory("账户高敏处置", "冻结/解冻 · impersonate(C2)· KYC 人工标记(C4)· 2FA/密码(C5)", "风控 lead / 超管"),
            new AuditConfirmCategory("批量簇冻结", "关联账户簇批量冻结(K1)", "风控 lead / 超管"),
            new AuditConfirmCategory("后台账号治理", "建/停/启/改角色/重置双因子(A1)", "超管"),
            new AuditConfirmCategory("平台配置", "feature flag · 系统参数(A3)· 内容发布(I 域)", "超管 / 内容 lead"));

    private final PlatformConfigRepository configRepository;
    private final AuditLogService auditLogService;
    private final AuditOperationTicketMapper ticketMapper;
    private final AuditOperationHistoryMapper historyMapper;
    private final AuditConfirmCategoryMapper confirmCategoryMapper;

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
                .riskLevel(isTrue(ticket.getAmplifies()) || isTrue(ticket.getSos()) ? "HIGH" : "MEDIUM")
                .detail(decisionDetail(ticket, nextStatus, idempotencyKey, request.reason()))
                .build());
        return ApiResult.ok(toTicket(ticket));
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
                        .orderByDesc(AuditOperationTicketEntity::getOperationId))
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
        String displayValue = "schema".equals(config.key()) ? "统一 schema · " + normalizeSchemaValue(value) : value;
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
        return item == null || !StringUtils.hasText(item.configValue()) ? fallback : item.configValue();
    }

    private String status(String value) {
        if (!StringUtils.hasText(value)) {
            return STATUS_PENDING;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return TERMINAL_STATUSES.contains(normalized) || STATUS_PENDING.equals(normalized) ? normalized : STATUS_PENDING;
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
        seedTicketsIfEmpty();
        seedHistoryIfEmpty();
        seedConfirmCategoriesIfEmpty();
        seedMechanismConfigsIfMissing();
        seedAuditLogsIfEmpty();
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
        seedConfigIfMissing("admin.a2.reason_min_chars", "8 字", "A2 seed: reason minimum length");
        seedConfigIfMissing("admin.a2.retention_months", "13 个月", "A2 seed: audit retention");
        seedConfigIfMissing("admin.a2.schema_version", "v3", "A2 seed: audit schema version");
    }

    private void seedConfigIfMissing(String key, String value, String remark) {
        if (configRepository.findActiveByKey(key).isPresent()) {
            return;
        }
        saveConfig(key, value, remark);
    }

    private void seedAuditLogsIfEmpty() {
        AuditLogQueryRequest request = new AuditLogQueryRequest();
        request.setLimit(1);
        List<AuditLogRecord> existing = auditLogService.list(request);
        if (existing != null && !existing.isEmpty()) {
            return;
        }
        AUDIT_LOG_SEEDS.forEach(seed -> auditLogService.record(AuditLogWriteRequest.builder()
                .action(seed.action())
                .resourceType(seed.resourceType())
                .resourceId(seed.resourceId())
                .actorType("ADMIN")
                .actorUsername(seed.actor())
                .clientIp(seed.ip())
                .result("SUCCESS")
                .riskLevel(seed.riskLevel())
                .detail(Map.of(
                        "tsLabel", seed.ts(),
                        "actor", seed.actor(),
                        "role", seed.role(),
                        "domain", seed.domain(),
                        "obj", seed.obj(),
                        "delta", seed.delta()))
                .build()));
    }

    private Optional<AuditOperationTicketEntity> ticket(String operationId) {
        String normalized = normalizeText(operationId).toUpperCase(Locale.ROOT);
        return Optional.ofNullable(ticketMapper.selectOne(new LambdaQueryWrapper<AuditOperationTicketEntity>()
                .eq(AuditOperationTicketEntity::getOperationId, normalized)
                .eq(AuditOperationTicketEntity::getIsDeleted, 0)
                .last("LIMIT 1")));
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
