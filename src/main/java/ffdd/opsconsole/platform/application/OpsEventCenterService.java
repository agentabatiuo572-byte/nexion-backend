package ffdd.opsconsole.platform.application;

import ffdd.opsconsole.common.api.OpsErrorCode;
import ffdd.opsconsole.common.boundary.ApplicationService;
import ffdd.opsconsole.platform.dto.EventCenterMutationRequest;
import ffdd.opsconsole.platform.dto.EventCenterOverview;
import ffdd.opsconsole.platform.dto.EventCenterOverview.EventCenterStats;
import ffdd.opsconsole.platform.dto.EventCenterOverview.EventCommonField;
import ffdd.opsconsole.platform.dto.EventCenterOverview.EventDetailRow;
import ffdd.opsconsole.platform.dto.EventCenterOverview.EventDimensionParam;
import ffdd.opsconsole.platform.dto.EventCenterOverview.EventDomainExtensionBatch;
import ffdd.opsconsole.platform.dto.EventCenterOverview.EventDomainItem;
import ffdd.opsconsole.platform.dto.EventCenterOverview.EventFamily;
import ffdd.opsconsole.platform.dto.EventCenterOverview.EventKpiFormula;
import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.audit.AuditLogQueryRequest;
import ffdd.opsconsole.shared.audit.AuditLogRecord;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.shared.audit.AuditStatsBucket;
import ffdd.opsconsole.shared.audit.AuditStatsQueryRequest;
import ffdd.opsconsole.shared.audit.AuditStatsSummaryResponse;
import ffdd.opsconsole.shared.seed.OpsReadTimeSeedPolicy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.util.StringUtils;

@ApplicationService
@RequiredArgsConstructor
public class OpsEventCenterService {
    private static final String GROUP_A4 = "admin_a4_event";
    private static final String SCHEMA_VERSION_KEY = "admin.a4.event.schema_version";
    private static final String BATCH_KEY_PREFIX = "admin.a4.event.batch.";
    private static final String BATCH_KEY_SUFFIX = ".status";
    private static final String DEFAULT_SCHEMA_VERSION = "v3";
    private static final int MUTATION_VALUE_MAX_LENGTH = 160;

    private static final List<String> REGISTERED_DOMAINS = List.of(
            "app", "auth", "referral", "kyc", "onboarding", "store", "checkout", "device",
            "earnings", "wallet", "withdraw", "commission", "staking", "exchange", "genesis",
            "trial", "quest", "daily", "nova", "phase", "risk", "admin", "event", "milestone",
            "nex", "repurchase");
    private static final List<String> PENDING_DOMAINS = List.of("content", "notification", "disclosure", "learn");
    private static final List<String> SUNSET_DOMAINS = List.of("premium", "points", "nexv2");

    private static final List<EventFamily> EVENT_FAMILIES = List.of(
            family("acquisition", "① 获客漏斗", "注册前后身份拼接 + 渠道归因",
                    "app.session_started · auth.register_completed · kyc.express_verified · device.first_yield_received",
                    "部分(注册/验证类服务器发)", "1.4M",
                    row("app.session_started", "匿名设备首次会话"),
                    row("auth.register_started / completed", "注册链路开始与完成"),
                    row("kyc.express_verified", "KYC 快速验证通过"),
                    row("device.first_yield_received", "首笔设备收益到账")),
            family("conversion", "② 转化", "商城浏览、下单、试用和邀请转化",
                    "store.viewed · checkout.completed · trial.started · referral.invite_sent",
                    "下单/试用服务器发", "620K",
                    row("store.viewed", "商城详情曝光"),
                    row("checkout.completed", "支付完成"),
                    row("trial.started", "试用开始"),
                    row("referral.invite_sent", "邀请发出")),
            family("monetization", "③ 资金 / 资产", "钱包、提现、质押、兑换、Genesis 交易",
                    "wallet.* · withdraw.* · staking.opened · exchange.swapped · genesis.purchased",
                    "全部服务器发", "480K",
                    row("wallet.ledger_posted", "钱包流水落账"),
                    row("withdraw.requested / approved / paid", "提现全链路"),
                    row("staking.opened", "质押开仓"),
                    row("exchange.swapped", "兑换完成"),
                    row("genesis.purchased", "Genesis 购买完成")),
            family("retention", "④ 留存", "日活、签到、任务和推送触达",
                    "app.dau · daily.checkin · quest.completed · nova.push_clicked",
                    "推送送达服务器发", "1.6M",
                    row("app.dau", "日活跃(KPI #2 口径)"),
                    row("daily.checkin", "每日签到"),
                    row("quest.completed", "任务完成"),
                    row("nova.push_sent / push_clicked", "推送送达与点击(KPI #6)")),
            family("risk", "⑤ 风控", "风险信号(服务器检测产出)",
                    "risk.multi_account_flagged · risk.score_updated · auth.login_locked",
                    "全部服务器发", "88K",
                    row("risk.multi_account_flagged", "多账户关联命中"),
                    row("risk.arbitrage_suspected", "套利嫌疑"),
                    row("risk.trial_cycle_detected", "循环养号"),
                    row("risk.withdraw_held", "提现拦截"),
                    row("risk.score_updated", "风险分更新"),
                    row("risk.kyc_review_triggered", "大额复审触发"),
                    row("auth.login_locked", "登录锁定")),
            family("phase_admin", "⑥ 节奏 / admin", "阶段切换 + 全部后台治理审计",
                    "phase.transitioned · phase.dial_changed · admin.*",
                    "全部服务器发", "12K",
                    row("phase.transitioned", "阶段切换"),
                    row("phase.dial_changed", "dial 改动(同时落 A2 审计)"),
                    row("admin.*(全后台治理事件)", "各域高敏动作审计,命名在 A4 注册,落库在 A2")));

    private static final List<EventCommonField> COMMON_FIELDS = List.of(
            new EventCommonField("event_id", "event_id", "服务器生成唯一号,去重用", "必带"),
            new EventCommonField("ts", "ts", "服务器收到时间(毫秒),客户端时钟不算数", "服务器权威"),
            new EventCommonField("phase_age", "phase + 账户月龄", "事发时用户处于 P 几,阶段效果归因字段", "必带"),
            new EventCommonField("cohort", "cohort", "注册周(按周分群)", "必带"),
            new EventCommonField("is_server_authoritative", "is_server_authoritative",
                    "资金/状态事件 = true(服务器发);界面交互 = false", "必带"),
            new EventCommonField("misc", "其余:身份三件套 / 归因来源 ref / 端信息", "locale · 平台 · 版本号", "必带"));

    private static final List<EventKpiFormula> KPI_FORMULAS = List.of(
            new EventKpiFormula(1, "Day0 接入 >95%", "90 秒内拿到首笔收益的人 ÷ 注册数"),
            new EventKpiFormula(2, "Day7 留存 >60%", "第 8 天还活跃的人 ÷ 当周注册群"),
            new EventKpiFormula(3, "逛商城 >30%", "看过商城的人 ÷ 注册数"),
            new EventKpiFormula(4, "下单 5-10%", "完成支付的人 ÷ 看过商城的人"),
            new EventKpiFormula(5, "持有者推广 >40%", "发过邀请的设备持有者 ÷ 持有者总数"),
            new EventKpiFormula(6, "Nova 点击率 >25%", "推送点击 ÷ 推送送达(只认服务器送达)"),
            new EventKpiFormula(7, "佣金触发 >80%", "直推首单产生佣金的人 ÷ 直推总数"),
            new EventKpiFormula(8, "Genesis 售罄 <14 天", "累计售出达 1,000 张用的天数"));

    private final PlatformConfigFacade configFacade;
    private final AuditLogService auditLogService;
    private final OpsReadTimeSeedPolicy readTimeSeedPolicy;

    public ApiResult<EventCenterOverview> overview() {
        AuditStatsSummaryResponse todaySummary = auditLogService.summary(statsQuery(1, 10));
        long todayAuditEvents = todaySummary.getTotal() == null ? 0L : todaySummary.getTotal();
        List<EventDomainExtensionBatch> batches = domainExtensions();
        int batchDone = (int) batches.stream()
                .filter(batch -> "done".equals(batch.state()) || "inprogress".equals(batch.state()))
                .count();
        return ApiResult.ok(new EventCenterOverview(
                new EventCenterStats(
                        formatCount(todayAuditEvents),
                        todayAuditEvents,
                        REGISTERED_DOMAINS.size(),
                        PENDING_DOMAINS.size(),
                        batchDone,
                        batches.size(),
                        schemaVersion()),
                EVENT_FAMILIES,
                REGISTERED_DOMAINS,
                PENDING_DOMAINS,
                SUNSET_DOMAINS,
                COMMON_FIELDS,
                dimensionParams(),
                KPI_FORMULAS,
                batches,
                recentLogs(),
                auditLogService.topActions(statsQuery(7, 10)),
                guardrails()));
    }

    public ApiResult<EventDimensionParam> updateParam(
            String idempotencyKey, String paramKey, EventCenterMutationRequest request) {
        ApiResult<EventDimensionParam> guard = requireMutation(idempotencyKey, request);
        if (guard != null) {
            return guard;
        }
        ParamDefinition definition = paramDefinition(paramKey);
        if (definition == null) {
            return fail(OpsErrorCode.VALIDATION_FAILED, "A4_EVENT_PARAM_UNKNOWN");
        }
        if (definition.locked()) {
            return fail(OpsErrorCode.PHASE_PARAM_READONLY, "A4_EVENT_PARAM_LOCKED");
        }
        String value;
        try {
            value = normalizeTextValue(request.value());
        } catch (IllegalArgumentException ex) {
            return fail(OpsErrorCode.VALIDATION_FAILED, ex.getMessage());
        }
        if (containsSunsetTerm(value)) {
            return fail(OpsErrorCode.RETIRED_FEATURE, "SUNSET_CAPABILITY_READONLY");
        }
        configFacade.upsertAdminValue(definition.configKey(), value, "STRING", GROUP_A4, mutationRemark(request.reason()));
        audit("A4_EVENT_PARAM_CHANGED", "A4_EVENT_CENTER_PARAM", definition.key(), idempotencyKey, request,
                Map.of("paramKey", definition.key(), "configKey", definition.configKey(), "value", value));
        return ApiResult.ok(paramView(definition, value));
    }

    public ApiResult<EventCenterOverview> registerSchema(String idempotencyKey, EventCenterMutationRequest request) {
        ApiResult<EventCenterOverview> guard = requireMutation(idempotencyKey, request);
        if (guard != null) {
            return guard;
        }
        String value;
        try {
            value = normalizeTextValue(request.value());
        } catch (IllegalArgumentException ex) {
            return fail(OpsErrorCode.VALIDATION_FAILED, ex.getMessage());
        }
        if (containsSunsetTerm(value)) {
            return fail(OpsErrorCode.RETIRED_FEATURE, "SUNSET_CAPABILITY_READONLY");
        }
        if (containsPiiTerm(value)) {
            return fail(OpsErrorCode.VALIDATION_FAILED, "A4_SCHEMA_PII_REJECTED");
        }
        configFacade.upsertAdminValue(SCHEMA_VERSION_KEY, value, "STRING", GROUP_A4, mutationRemark(request.reason()));
        audit("A4_EVENT_SCHEMA_REGISTERED", "A4_EVENT_SCHEMA", value, idempotencyKey, request,
                Map.of("schemaVersion", value));
        return overview();
    }

    public ApiResult<EventDomainExtensionBatch> registerDomainExtension(
            String idempotencyKey, EventCenterMutationRequest request) {
        ApiResult<EventDomainExtensionBatch> guard = requireMutation(idempotencyKey, request);
        if (guard != null) {
            return guard;
        }
        String value;
        try {
            value = normalizeTextValue(request.value());
        } catch (IllegalArgumentException ex) {
            return fail(OpsErrorCode.VALIDATION_FAILED, ex.getMessage());
        }
        if (containsSunsetTerm(value)) {
            return fail(OpsErrorCode.RETIRED_FEATURE, "SUNSET_CAPABILITY_READONLY");
        }
        String slug = slug(value);
        if (!StringUtils.hasText(slug)) {
            return fail(OpsErrorCode.VALIDATION_FAILED, "A4_DOMAIN_EXTENSION_INVALID");
        }
        String configKey = BATCH_KEY_PREFIX + slug + BATCH_KEY_SUFFIX;
        configFacade.upsertAdminValue(configKey, "registered", "STRING", GROUP_A4, mutationRemark(request.reason()));
        EventDomainExtensionBatch batch = dynamicBatch(slug, value, "registered");
        audit("A4_DOMAIN_EXTENSION_REGISTERED", "A4_DOMAIN_EXTENSION", slug, idempotencyKey, request,
                Map.of("batchId", batch.id(), "value", value, "configKey", configKey));
        return ApiResult.ok(batch);
    }

    private List<EventDimensionParam> dimensionParams() {
        return paramDefinitions().stream()
                .map(definition -> paramView(definition, configFacade.activeValue(definition.configKey())
                        .orElseGet(() -> readTimeSeedPolicy.enabled() ? definition.defaultValue() : "")))
                .toList();
    }

    private EventDimensionParam paramView(ParamDefinition definition, String value) {
        return new EventDimensionParam(definition.key(), definition.name(), definition.sub(), value, definition.locked());
    }

    private List<EventDomainExtensionBatch> domainExtensions() {
        List<EventDomainExtensionBatch> batches = new ArrayList<>();
        if (readTimeSeedPolicy.enabled()) {
            batches.addAll(List.of(
                    new EventDomainExtensionBatch(
                            "v3-init",
                            "V3 起始批",
                            "done",
                            "活动(H4)/ 里程碑(H6)/ 金融产品(G)",
                            "活跃事件已迁回各自 domain; premium 仅保留历史兼容说明",
                            domains("event", "milestone", "nex", "repurchase"),
                            List.of(
                                    row("event.*", "活动中心事件(H4)已迁回"),
                                    row("milestone.*", "收益里程碑(H6)已迁回"),
                                    row("nex.* / repurchase.*", "金融产品活跃事件已迁回"),
                                    row("premium.*", "已下线,仅允许历史兼容、到期兑付或迁移对账"))),
                    new EventDomainExtensionBatch(
                            "v4-content",
                            "V4 内容批",
                            "inprogress",
                            "内容域(I1/I3/I5/I7)",
                            "四类事件暂记 admin 占位 + 临时编号",
                            domains("content", "notification", "disclosure", "learn"),
                            List.of(
                                    row("content.variant_exposed / converted", "文案 A/B 曝光与转化(I1/I6)占位中"),
                                    row("content.trust_section_viewed", "信任版块曝光(I4)占位中"),
                                    row("notification.delivered / read / swipe_action_taken", "通知三件套(I3)占位中"),
                                    row("disclosure.viewed / acked / reack_triggered / gated_action_blocked", "披露操作链(I5)占位中"),
                                    row("learn.course_started / quiz_passed / course_completed", "课程链(I7)占位中"))),
                    new EventDomainExtensionBatch(
                            "j-schema",
                            "J 域 schema 批",
                            "pending",
                            "应急域(J3/J4)",
                            "risk / admin 域内新事件,无需新 domain",
                            List.of(new EventDomainItem("risk.tamper_detected", false),
                                    new EventDomainItem("admin.emergency_playbook_*", false)),
                            List.of(
                                    row("risk.tamper_detected", "篡改防御命中(J3)"),
                                    row("admin.emergency_playbook_executed", "应急剧本执行(J4)"),
                                    row("admin.emergency_playbook_edited", "应急剧本编辑(J4)"))),
                    new EventDomainExtensionBatch(
                            "v4-close",
                            "V4 收口核对",
                            "scheduled",
                            "A4 自查",
                            "目标:无遗漏、无双源",
                            List.of(),
                            List.of(
                                    row("核对范围", "12 域全部 admin.* 事件"),
                                    row("目标", "无遗漏 · 无双源 · 命名合规"),
                                    row("产出", "差异清单 → 逐条补注册或改名")))));
        }

        configFacade.activeValuesByGroup(GROUP_A4).entrySet().stream()
                .filter(entry -> entry.getKey().startsWith(BATCH_KEY_PREFIX) && entry.getKey().endsWith(BATCH_KEY_SUFFIX))
                .map(entry -> dynamicBatch(slugFromConfigKey(entry.getKey()), slugFromConfigKey(entry.getKey()), entry.getValue()))
                .forEach(batches::add);
        return batches;
    }

    private EventDomainExtensionBatch dynamicBatch(String slug, String value, String status) {
        return new EventDomainExtensionBatch(
                "manual-" + slug,
                "登记扩展工单",
                normalizeBatchStatus(status),
                "A4 手工登记",
                "待 schema 注册和归属确认",
                List.of(new EventDomainItem(value, true)),
                List.of(row(value, "管理员登记的 domain / 事件名,等待 schema registry 复核")));
    }

    private List<AuditLogRecord> recentLogs() {
        AuditLogQueryRequest request = new AuditLogQueryRequest();
        request.setResourceType("A4_EVENT_CENTER");
        request.setLimit(10);
        return auditLogService.list(request);
    }

    private AuditStatsQueryRequest statsQuery(int days, int limit) {
        AuditStatsQueryRequest request = new AuditStatsQueryRequest();
        request.setDays(days);
        request.setLimit(limit);
        return request;
    }

    private String schemaVersion() {
        return configFacade.activeValue(SCHEMA_VERSION_KEY)
                .orElseGet(() -> readTimeSeedPolicy.enabled() ? DEFAULT_SCHEMA_VERSION : "");
    }

    private <T> ApiResult<T> requireMutation(String idempotencyKey, EventCenterMutationRequest request) {
        if (!StringUtils.hasText(idempotencyKey)) {
            return fail(OpsErrorCode.IDEMPOTENCY_KEY_REQUIRED, OpsErrorCode.IDEMPOTENCY_KEY_REQUIRED.name());
        }
        if (request == null || !StringUtils.hasText(request.value())) {
            return fail(OpsErrorCode.VALIDATION_FAILED, "VALUE_REQUIRED");
        }
        if (!StringUtils.hasText(request.reason())) {
            return fail(OpsErrorCode.REASON_REQUIRED, OpsErrorCode.REASON_REQUIRED.name());
        }
        if (!StringUtils.hasText(request.operator())) {
            return fail(OpsErrorCode.VALIDATION_FAILED, "OPERATOR_REQUIRED");
        }
        return null;
    }

    private String normalizeTextValue(String value) {
        String normalized = value.trim();
        if (normalized.length() > MUTATION_VALUE_MAX_LENGTH) {
            throw new IllegalArgumentException("A4_VALUE_TOO_LONG");
        }
        if (normalized.startsWith("{") || normalized.startsWith("[")) {
            throw new IllegalArgumentException("A4_RAW_JSON_REJECTED");
        }
        String lower = normalized.toLowerCase(Locale.ROOT);
        if (lower.startsWith("http://") || lower.startsWith("https://")) {
            throw new IllegalArgumentException("A4_URL_VALUE_REJECTED");
        }
        return normalized;
    }

    private void audit(
            String action,
            String resourceType,
            String resourceId,
            String idempotencyKey,
            EventCenterMutationRequest request,
            Map<String, Object> extraDetail) {
        Map<String, Object> detail = new LinkedHashMap<>(extraDetail);
        detail.put("reason", request.reason().trim());
        detail.put("idempotencyKey", idempotencyKey.trim());
        auditLogService.record(AuditLogWriteRequest.builder()
                .action(action)
                .resourceType(resourceType)
                .resourceId(resourceId)
                .actorType("ADMIN")
                .actorUsername(request.operator().trim())
                .result("SUCCESS")
                .riskLevel("MEDIUM")
                .detail(detail)
                .build());
    }

    private ParamDefinition paramDefinition(String paramKey) {
        if (!StringUtils.hasText(paramKey)) {
            return null;
        }
        return paramDefinitions().stream()
                .filter(definition -> definition.key().equals(paramKey.trim()))
                .findFirst()
                .orElse(null);
    }

    private List<ParamDefinition> paramDefinitions() {
        return List.of(
                new ParamDefinition("day0", "Day0 接入窗口", "注册到首笔收益到账的达标时限(KPI #1 口径)",
                        "90 秒", false, "admin.a4.event.kpi.day0"),
                new ParamDefinition("retention", "留存口径", "Day1 / Day7 / Day30 三窗",
                        "D1·D7·D30", true, "admin.a4.event.kpi.retention"),
                new ParamDefinition("event_retention", "事件留存期", "完整 12 月周期 + 1 月缓冲;审计日志同口径",
                        "13 个月", false, "admin.a4.event.kpi.event_retention"),
                new ParamDefinition("sampling", "采样率", "浏览/会话类抽样省成本;资金/风控/转化类永远全量",
                        "浏览 10% · 资金 100%", false, "admin.a4.event.kpi.sampling"));
    }

    private List<String> guardrails() {
        return List.of(
                "资金和 KPI 口径只统计 is_server_authoritative=true 的服务器事件",
                "事件名和属性先注册后使用,PII 明文禁入 schema",
                "Premium、积分系统、NEX v2 只保留历史兼容、到期兑付或迁移说明,不得作为活跃 domain");
    }

    private static EventFamily family(
            String key,
            String title,
            String sub,
            String sample,
            String serverAuth,
            String todayCount,
            EventDetailRow... events) {
        return new EventFamily(key, title, sub, sample, serverAuth, todayCount, List.of(events));
    }

    private static EventDetailRow row(String item, String desc) {
        return new EventDetailRow(item, desc);
    }

    private static List<EventDomainItem> domains(String... names) {
        return java.util.Arrays.stream(names)
                .map(name -> new EventDomainItem(name, true))
                .toList();
    }

    private String mutationRemark(String reason) {
        return "A4 event center mutation: " + reason.trim();
    }

    private String formatCount(long count) {
        if (count <= 0) {
            return "0";
        }
        if (count >= 1_000_000) {
            return String.format(Locale.US, "%.1fM", count / 1_000_000d).replace(".0M", "M");
        }
        if (count >= 1_000) {
            return String.format(Locale.US, "%.1fK", count / 1_000d).replace(".0K", "K");
        }
        return String.valueOf(count);
    }

    private boolean containsPiiTerm(String value) {
        String normalized = value.toLowerCase(Locale.ROOT);
        return normalized.contains("phone")
                || normalized.contains("mobile")
                || normalized.contains("address")
                || normalized.contains("手机号")
                || normalized.contains("地址");
    }

    private boolean containsSunsetTerm(String value) {
        if (!StringUtils.hasText(value)) {
            return false;
        }
        String normalized = value.toLowerCase(Locale.ROOT).replace("_", "").replace("-", "").replace(".", "");
        return normalized.contains("premium")
                || normalized.contains("points")
                || normalized.contains("积分")
                || normalized.contains("nexv2");
    }

    private String slug(String value) {
        String normalized = value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_")
                .replaceAll("^_+|_+$", "");
        return normalized.length() > 48 ? normalized.substring(0, 48).replaceAll("_+$", "") : normalized;
    }

    private String slugFromConfigKey(String configKey) {
        return configKey.substring(BATCH_KEY_PREFIX.length(), configKey.length() - BATCH_KEY_SUFFIX.length());
    }

    private String normalizeBatchStatus(String status) {
        if (!StringUtils.hasText(status)) {
            return "pending";
        }
        String normalized = status.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "done", "inprogress", "scheduled", "registered" -> normalized;
            default -> "pending";
        };
    }

    private <T> ApiResult<T> fail(OpsErrorCode code, String message) {
        return ApiResult.fail(code.httpStatus(), message);
    }

    private record ParamDefinition(
            String key,
            String name,
            String sub,
            String defaultValue,
            boolean locked,
            String configKey) {
    }
}
