package ffdd.opsconsole.platform.application;

import ffdd.opsconsole.common.api.OpsErrorCode;
import ffdd.opsconsole.common.boundary.ApplicationService;
import ffdd.opsconsole.platform.dto.EventCenterMutationRequest;
import ffdd.opsconsole.platform.dto.EventCenterOverview;
import ffdd.opsconsole.platform.dto.EventDomainExtensionRequest;
import ffdd.opsconsole.platform.dto.EventSchemaRegistrationRequest;
import ffdd.opsconsole.platform.dto.EventCenterOverview.EventCenterStats;
import ffdd.opsconsole.platform.dto.EventCenterOverview.EventCommonField;
import ffdd.opsconsole.platform.dto.EventCenterOverview.EventDetailRow;
import ffdd.opsconsole.platform.dto.EventCenterOverview.EventDimensionParam;
import ffdd.opsconsole.platform.dto.EventCenterOverview.EventDomainExtensionBatch;
import ffdd.opsconsole.platform.dto.EventCenterOverview.EventDomainItem;
import ffdd.opsconsole.platform.dto.EventCenterOverview.EventFamily;
import ffdd.opsconsole.platform.dto.EventCenterOverview.EventKpiFormula;
import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import ffdd.opsconsole.platform.mapper.EventGovernanceMapper;
import ffdd.opsconsole.platform.mapper.EventGovernanceMapper.DomainExtensionRecord;
import ffdd.opsconsole.platform.mapper.EventGovernanceMapper.EventFamilyCount;
import ffdd.opsconsole.platform.mapper.EventGovernanceMapper.EventSchemaRecord;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.audit.AuditLogQueryRequest;
import ffdd.opsconsole.shared.audit.AuditLogRecord;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.shared.audit.AuditStatsBucket;
import ffdd.opsconsole.shared.audit.AuditStatsQueryRequest;
import ffdd.opsconsole.shared.audit.AuditStatsSummaryResponse;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import ffdd.opsconsole.shared.security.AdminActorResolver;
import ffdd.opsconsole.shared.seed.OpsReadTimeSeedPolicy;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@ApplicationService
@RequiredArgsConstructor
public class OpsEventCenterService {
    private static final String GROUP_A4 = "admin_a4_event";
    private static final int MUTATION_VALUE_MAX_LENGTH = 160;
    private static final int REASON_MIN_LENGTH = 8;
    private static final int REASON_MAX_LENGTH = 200;
    private static final Set<String> PRODUCERS = Set.of("server", "client", "server+client");
    private static final Set<String> PROPERTY_TYPES = Set.of("string", "number", "boolean", "enum", "timestamp", "id", "json");

    private static final List<String> REGISTERED_DOMAINS = List.of(
            "app", "auth", "referral", "kyc", "onboarding", "store", "checkout", "device",
            "earnings", "wallet", "withdraw", "commission", "staking", "exchange", "genesis",
            "trial", "quest", "daily", "nova", "phase", "risk", "admin", "event", "milestone",
            "nex", "repurchase", "disclosure");
    private static final List<String> PENDING_DOMAINS = List.of("content", "notification", "learn");
    private static final List<String> SUNSET_DOMAINS = List.of("premium", "points", "nexv2");

    private static final List<EventFamilyDefinition> EVENT_FAMILY_DEFINITIONS = List.of(
            familyDef("acquisition", "① 获客漏斗", "注册前后身份拼接 + 渠道归因",
                    "app.session_started · auth.register_completed · kyc.express_verified · device.first_yield_received",
                    "部分(注册/验证类服务器发)",
                    List.of("APP_", "AUTH_", "KYC_", "C1_", "C4_", "DEVICE_FIRST"),
                    row("app.session_started", "匿名设备首次会话"),
                    row("auth.register_started / completed", "注册链路开始与完成"),
                    row("kyc.express_verified", "KYC 快速验证通过"),
                    row("device.first_yield_received", "首笔设备收益到账")),
            familyDef("conversion", "② 转化", "商城浏览、下单、试用和邀请转化",
                    "store.viewed · checkout.completed · trial.started · referral.invite_sent",
                    "下单/试用服务器发",
                    List.of("E1_", "E2_", "E4_", "H2_", "REFERRAL_", "CHECKOUT_", "STORE_", "TRIAL_"),
                    row("store.viewed", "商城详情曝光"),
                    row("checkout.completed", "支付完成"),
                    row("trial.started", "试用开始"),
                    row("referral.invite_sent", "邀请发出")),
            familyDef("monetization", "③ 资金 / 资产", "钱包、提现、质押、兑换、Genesis 交易",
                    "wallet.topup_* · withdraw.* · staking.opened · exchange.swapped · genesis.purchased",
                    "全部服务器发",
                    List.of("D1_", "D2_", "D3_", "D4_", "G1_", "G2_", "G3_", "G4_", "G7_", "B1_", "WALLET_", "WITHDRAW_", "STAKING_", "EXCHANGE_", "GENESIS_"),
                    row("wallet.topup_created / confirmed / failed", "充值全链路"),
                    row("withdraw.submitted / risk_held / approved / confirmed", "提现全链路"),
                    row("staking.opened", "质押开仓"),
                    row("exchange.swapped", "兑换完成"),
                    row("genesis.purchased", "Genesis 购买完成")),
            familyDef("retention", "④ 留存", "日活、签到、任务和推送触达",
                    "app.dau · daily.checkin · quest.completed · nova.push_clicked",
                    "推送送达服务器发",
                    List.of("H3_", "H4_", "H5_", "H6_", "I2_", "I3_", "I7_", "DAILY_", "QUEST_", "NOVA_", "NOTIFICATION_"),
                    row("app.dau", "日活跃(KPI #2 口径)"),
                    row("daily.checkin", "每日签到"),
                    row("quest.completed", "任务完成"),
                    row("nova.push_sent / push_clicked", "推送送达与点击(KPI #6)")),
            familyDef("risk", "⑤ 风控", "风险信号(服务器检测产出)",
                    "risk.multi_account_flagged · risk.score_updated · auth.login_locked",
                    "全部服务器发",
                    List.of("K1_", "K2_", "K3_", "K4_", "K5_", "C5_", "C6_", "J2_", "J3_", "RISK_", "AUTH_LOGIN"),
                    row("risk.multi_account_flagged", "多账户关联命中"),
                    row("risk.arbitrage_suspected", "套利嫌疑"),
                    row("risk.trial_cycle_detected", "循环养号"),
                    row("risk.withdraw_held", "提现拦截"),
                    row("risk.score_updated", "风险分更新"),
                    row("risk.kyc_review_triggered", "大额复审触发"),
                    row("auth.login_locked", "登录锁定")),
            familyDef("phase_admin", "⑥ 节奏 / admin", "阶段切换 + 全部后台治理审计",
                    "phase.transitioned · phase.dial_changed · admin.*",
                    "全部服务器发",
                    List.of("A1_", "A2_", "A3_", "A4_", "H1_", "J1_", "J4_", "ADMIN_", "PHASE_"),
                    row("phase.transitioned", "阶段切换"),
                    row("phase.dial_changed", "dial 改动(同时落 A2 审计)"),
                    row("admin.*(全后台治理事件)", "各域高敏动作审计,命名在 A4 注册,落库在 A2")));

    private static final List<EventCommonField> COMMON_FIELDS = List.of(
            new EventCommonField("event_id", "event_id", "服务器生成唯一号,去重用", "必带"),
            new EventCommonField("event_name", "event_name", "domain.object_action,动作使用过去式", "必带"),
            new EventCommonField("ts", "ts", "服务器收到时间(毫秒),客户端时钟不算数", "服务器权威"),
            new EventCommonField("identity", "user_id / anon_id", "身份二选一,注册拼接期可并存", "字段必带,值可空"),
            new EventCommonField("session_id", "session_id", "单次会话 ID", "字段必带,值可空"),
            new EventCommonField("phase", "phase + 账户月龄", "事发时用户处于 P 几,阶段效果归因字段", "必带"),
            new EventCommonField("cohort", "cohort", "注册周(按周分群)", "必带"),
            new EventCommonField("attribution", "ref / source", "推荐码或渠道归因来源", "字段必带,值可空"),
            new EventCommonField("client", "platform / app_version / locale", "端、版本和语言", "必带"),
            new EventCommonField("is_server_authoritative", "is_server_authoritative",
                    "资金/状态事件 = true(服务器发);界面交互 = false", "必带"));

    private static final List<EventKpiFormula> KPI_FORMULAS = List.of(
            new EventKpiFormula(1, "Day0 接入", "90 秒内拿到首笔收益的人 ÷ 注册数"),
            new EventKpiFormula(2, "Day7 留存", "第 8 天还活跃的人 ÷ 当周注册群"),
            new EventKpiFormula(3, "逛商城", "看过商城的人 ÷ 注册数"),
            new EventKpiFormula(4, "下单转化", "完成支付的人 ÷ 看过商城的人"),
            new EventKpiFormula(5, "持有者推广", "发过邀请的设备持有者 ÷ 持有者总数"),
            new EventKpiFormula(6, "Nova 点击率", "推送点击 ÷ 推送送达(只认服务器送达)"),
            new EventKpiFormula(7, "佣金触发", "直推首单产生佣金的人 ÷ 直推总数"),
            new EventKpiFormula(8, "Genesis 售罄天数", "累计售出达成售罄口径用的天数"));

    private final PlatformConfigFacade configFacade;
    private final AuditLogService auditLogService;
    private final OpsReadTimeSeedPolicy readTimeSeedPolicy;
    private final EventGovernanceMapper governanceMapper;
    private final AdminIdempotencyService idempotencyService;

    public ApiResult<EventCenterOverview> overview() {
        AuditStatsSummaryResponse todaySummary = auditLogService.summary(statsQuery(1, 10));
        long todayAuditEvents = todaySummary.getTotal() == null ? 0L : todaySummary.getTotal();
        LocalDateTime todayStart = LocalDate.now().atStartOfDay();
        long todayEvents = governanceMapper.countEventsSince(todayStart);
        List<DomainExtensionRecord> extensionRecords = governanceMapper.listDomainExtensions(100);
        List<EventDomainExtensionBatch> batches = domainExtensions(extensionRecords);
        List<String> registeredDomains = completedDomainNames(extensionRecords);
        List<String> pendingDomains = pendingDomainNames(extensionRecords);
        int batchDone = (int) batches.stream()
                .filter(batch -> "done".equals(batch.state()))
                .count();
        return ApiResult.ok(new EventCenterOverview(
                new EventCenterStats(
                        formatCount(todayEvents),
                        todayAuditEvents,
                        registeredDomains.size(),
                        pendingDomains.size(),
                        batchDone,
                        batches.size(),
                        schemaVersion()),
                eventFamilies(),
                registeredDomains,
                pendingDomains,
                SUNSET_DOMAINS,
                COMMON_FIELDS,
                dimensionParams(),
                kpiFormulas(),
                governanceMapper.listSchemas(50),
                batches,
                recentLogs(),
                auditLogService.topActions(statsQuery(7, 10)),
                guardrails()));
    }

    @Transactional
    @SuppressWarnings({"rawtypes", "unchecked"})
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
            value = normalizeParamValue(definition.key(), request.value());
        } catch (IllegalArgumentException ex) {
            return fail(OpsErrorCode.VALIDATION_FAILED, ex.getMessage());
        }
        if (containsSunsetTerm(value)) {
            return fail(OpsErrorCode.RETIRED_FEATURE, "SUNSET_CAPABILITY_READONLY");
        }
        String reason = normalizeReason(request.reason());
        String hash = requestHash(definition.key(), value, reason);
        return (ApiResult<EventDimensionParam>) idempotencyService.execute(
                "A4_PARAM:" + definition.key(), idempotencyKey.trim(), hash, ApiResult.class,
                () -> updateParamOnce(definition, value, reason, idempotencyKey.trim()));
    }

    @Transactional
    @SuppressWarnings({"rawtypes", "unchecked"})
    public ApiResult<EventCenterOverview> registerSchema(
            String idempotencyKey, EventSchemaRegistrationRequest request) {
        ApiResult<EventCenterOverview> guard = requireSchemaMutation(idempotencyKey, request);
        if (guard != null) {
            return guard;
        }
        NormalizedSchema normalized;
        try {
            normalized = normalizeSchema(request);
        } catch (IllegalArgumentException ex) {
            return fail(OpsErrorCode.VALIDATION_FAILED, ex.getMessage());
        }
        String hash = requestHash(
                normalized.eventName(), normalized.ownerDomain(), normalized.producer(), normalized.consumer(),
                normalized.propertyName(), normalized.propertyType(), String.valueOf(normalized.serverAuthoritative()),
                normalized.samplingPolicy(), normalized.expectedVersion(), normalized.reason());
        return (ApiResult<EventCenterOverview>) idempotencyService.execute(
                "A4_SCHEMA:" + normalized.eventName(), idempotencyKey.trim(), hash, ApiResult.class,
                () -> registerSchemaOnce(normalized, idempotencyKey.trim()));
    }

    @Transactional
    @SuppressWarnings({"rawtypes", "unchecked"})
    public ApiResult<EventDomainExtensionBatch> registerDomainExtension(
            String idempotencyKey, EventDomainExtensionRequest request) {
        ApiResult<EventDomainExtensionBatch> guard = requireDomainMutation(idempotencyKey, request);
        if (guard != null) {
            return guard;
        }
        NormalizedDomainExtension normalized;
        try {
            normalized = normalizeDomainExtension(request);
        } catch (IllegalArgumentException ex) {
            return fail(OpsErrorCode.VALIDATION_FAILED, ex.getMessage());
        }
        String hash = requestHash(normalized.domainName(), normalized.eventName(), normalized.producer(),
                normalized.consumer(), normalized.reason());
        return (ApiResult<EventDomainExtensionBatch>) idempotencyService.execute(
                "A4_DOMAIN_EXTENSION:" + normalized.domainName(), idempotencyKey.trim(), hash, ApiResult.class,
                () -> registerDomainExtensionOnce(normalized, idempotencyKey.trim()));
    }

    private List<EventDimensionParam> dimensionParams() {
        return paramDefinitions().stream()
                .map(definition -> paramView(definition, configFacade.activeValue(definition.configKey())
                        .orElse(definition.defaultValue())))
                .toList();
    }

    private EventDimensionParam paramView(ParamDefinition definition, String value) {
        return new EventDimensionParam(definition.key(), definition.name(), definition.sub(), value, definition.locked());
    }

    private List<EventDomainExtensionBatch> domainExtensions(List<DomainExtensionRecord> extensionRecords) {
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
                                    row("content.variant_exposed / converted", "A4 已注册 · I1 服务端稳定分桶、首曝与已支付/完成订单转化"),
                                    row("content.trust_section_viewed / admin.trust_content_published / archived / rolledback", "A4 已注册 · I4 App 曝光与发布/归档/回滚治理事件"),
                                    row("notification.delivered / read / swipe_action_taken", "A4 已注册 · I3 持久通知送达、服务端已读与幂等 CTA/滑动动作"),
                                    row("disclosure.viewed / acked / reack_triggered / gated_action_blocked", "A4 已注册 · I5 披露曝光、确认、逐用户 re-ack 与合规闸拦截"),
                                    row("learn.course_started / quiz_passed / course_completed", "A4 已注册 · I7 完课事件保留 H3 投递类型，完成态、奖励账本与三语运行时均由后端权威写入"))),
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

        // J3 is a live canonical event and must remain visible even when optional read-time demo
        // batches are disabled. Keeping it outside the seed policy prevents a false A4 pending state.
        batches.add(new EventDomainExtensionBatch(
                "j-schema",
                "J 域 schema 批",
                "inprogress",
                "应急域(J3/J4)",
                "J3 篡改事件已注册并落地；J4 剧本事件仍待注册",
                List.of(new EventDomainItem("risk.tamper_detected", true),
                        new EventDomainItem("admin.emergency_playbook_*", false)),
                List.of(
                        row("risk.tamper_detected", "篡改防御命中(J3)"),
                        row("admin.emergency_playbook_executed", "应急剧本执行(J4)"),
                        row("admin.emergency_playbook_edited", "应急剧本编辑(J4)"))));

        extensionRecords.stream()
                .map(this::dynamicBatch)
                .forEach(batches::add);
        return batches;
    }

    private List<String> domainNames(
            List<String> baseline, List<DomainExtensionRecord> extensionRecords, String status) {
        List<String> result = new ArrayList<>(baseline);
        extensionRecords.stream()
                .filter(record -> status.equalsIgnoreCase(record.status()))
                .map(DomainExtensionRecord::domainName)
                .filter(domain -> !result.contains(domain))
                .forEach(result::add);
        return List.copyOf(result);
    }

    private List<String> pendingDomainNames(List<DomainExtensionRecord> extensionRecords) {
        List<String> result = new ArrayList<>(domainNames(PENDING_DOMAINS, extensionRecords, "REGISTERED"));
        extensionRecords.stream()
                .filter(record -> "DONE".equalsIgnoreCase(record.status()))
                .map(DomainExtensionRecord::domainName)
                .distinct()
                .filter(domain -> !hasOpenExtension(extensionRecords, domain))
                .forEach(result::remove);
        return List.copyOf(result);
    }

    private List<String> completedDomainNames(List<DomainExtensionRecord> extensionRecords) {
        List<String> result = new ArrayList<>(REGISTERED_DOMAINS);
        extensionRecords.stream()
                .filter(record -> "DONE".equalsIgnoreCase(record.status()))
                .map(DomainExtensionRecord::domainName)
                .distinct()
                .filter(domain -> !hasOpenExtension(extensionRecords, domain))
                .filter(domain -> !result.contains(domain))
                .forEach(result::add);
        return List.copyOf(result);
    }

    private boolean hasOpenExtension(List<DomainExtensionRecord> extensionRecords, String domain) {
        return extensionRecords.stream().anyMatch(record -> domain.equals(record.domainName())
                && "REGISTERED".equalsIgnoreCase(record.status()));
    }

    private List<EventKpiFormula> kpiFormulas() {
        String day0 = configFacade.activeValue("admin.a4.event.kpi.day0").orElse("90 秒");
        return KPI_FORMULAS.stream()
                .map(formula -> formula.n() == 1
                        ? new EventKpiFormula(1, formula.kpi(), day0 + "内拿到首笔收益的人 ÷ 注册数")
                        : formula)
                .toList();
    }

    private EventDomainExtensionBatch dynamicBatch(DomainExtensionRecord record) {
        return new EventDomainExtensionBatch(
                "manual-" + record.id(),
                "登记扩展工单",
                normalizeBatchStatus(record.status()),
                record.producer(),
                "done".equalsIgnoreCase(record.status()) ? "schema 已注册并完成归属确认" : "待 schema 注册和归属确认",
                List.of(new EventDomainItem(record.domainName(), true)),
                List.of(row(record.eventName(), "生产方 " + record.producer() + " → 消费方 " + record.consumer())));
    }

    private List<AuditLogRecord> recentLogs() {
        AuditLogQueryRequest request = new AuditLogQueryRequest();
        request.setResourceType("A4_EVENT_CENTER");
        request.setLimit(10);
        return auditLogService.list(request);
    }

    private List<EventFamily> eventFamilies() {
        Map<String, Long> counts = new LinkedHashMap<>();
        governanceMapper.countEventsByFamilySince(LocalDate.now().atStartOfDay())
                .forEach(bucket -> counts.put(bucket.familyKey(), bucket.eventCount()));
        return EVENT_FAMILY_DEFINITIONS.stream()
                .map(definition -> family(
                        definition.key(),
                        definition.title(),
                        definition.sub(),
                        definition.sample(),
                        definition.serverAuth(),
                        formatCount(counts.getOrDefault(definition.key(), 0L)),
                        definition.events().toArray(EventDetailRow[]::new)))
                .toList();
    }

    private AuditStatsQueryRequest statsQuery(int days, int limit) {
        AuditStatsQueryRequest request = new AuditStatsQueryRequest();
        request.setDays(days);
        request.setLimit(limit);
        return request;
    }

    private String schemaVersion() {
        Integer revision = governanceMapper.currentRevision();
        if (revision == null || revision < 1) {
            throw new IllegalStateException("A4_SCHEMA_REVISION_MISSING");
        }
        return "v" + revision;
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
        if (!validReason(request.reason())) {
            return fail(OpsErrorCode.VALIDATION_FAILED, "A4_REASON_LENGTH_INVALID");
        }
        return null;
    }

    private <T> ApiResult<T> requireSchemaMutation(String idempotencyKey, EventSchemaRegistrationRequest request) {
        if (!StringUtils.hasText(idempotencyKey)) {
            return fail(OpsErrorCode.IDEMPOTENCY_KEY_REQUIRED, OpsErrorCode.IDEMPOTENCY_KEY_REQUIRED.name());
        }
        if (request == null) {
            return fail(OpsErrorCode.VALIDATION_FAILED, "A4_SCHEMA_REQUEST_REQUIRED");
        }
        if (!StringUtils.hasText(request.reason())) {
            return fail(OpsErrorCode.REASON_REQUIRED, OpsErrorCode.REASON_REQUIRED.name());
        }
        if (!validReason(request.reason())) {
            return fail(OpsErrorCode.VALIDATION_FAILED, "A4_REASON_LENGTH_INVALID");
        }
        return null;
    }

    private <T> ApiResult<T> requireDomainMutation(String idempotencyKey, EventDomainExtensionRequest request) {
        if (!StringUtils.hasText(idempotencyKey)) {
            return fail(OpsErrorCode.IDEMPOTENCY_KEY_REQUIRED, OpsErrorCode.IDEMPOTENCY_KEY_REQUIRED.name());
        }
        if (request == null) {
            return fail(OpsErrorCode.VALIDATION_FAILED, "A4_DOMAIN_EXTENSION_REQUEST_REQUIRED");
        }
        if (!StringUtils.hasText(request.reason())) {
            return fail(OpsErrorCode.REASON_REQUIRED, OpsErrorCode.REASON_REQUIRED.name());
        }
        if (!validReason(request.reason())) {
            return fail(OpsErrorCode.VALIDATION_FAILED, "A4_REASON_LENGTH_INVALID");
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

    private ApiResult<EventDimensionParam> updateParamOnce(
            ParamDefinition definition, String value, String reason, String idempotencyKey) {
        String before = configFacade.activeValue(definition.configKey()).orElse(definition.defaultValue());
        if (before.equals(value)) {
            return fail(OpsErrorCode.INVALID_STATE_TRANSITION, "A4_EVENT_PARAM_SAME_VALUE");
        }
        configFacade.upsertAdminValue(definition.configKey(), value, "STRING", GROUP_A4, mutationRemark(reason));
        auditRequired("A4_EVENT_PARAM_CHANGED", "A4_EVENT_CENTER_PARAM", definition.key(), idempotencyKey, reason,
                Map.of("paramKey", definition.key(), "configKey", definition.configKey(), "before", before, "after", value));
        return ApiResult.ok(paramView(definition, value));
    }

    private ApiResult<EventCenterOverview> registerSchemaOnce(NormalizedSchema schema, String idempotencyKey) {
        int currentRevision = requiredRevision(governanceMapper.lockCurrentRevision());
        if (!schema.expectedVersion().equals("v" + currentRevision)) {
            return fail(OpsErrorCode.INVALID_STATE_TRANSITION, "A4_SCHEMA_VERSION_STALE");
        }
        EventSchemaRecord existing = governanceMapper.findSchema(schema.eventName());
        if (existing != null && governanceMapper.countProperty(existing.id(), schema.propertyName()) > 0) {
            return fail(OpsErrorCode.INVALID_STATE_TRANSITION, "A4_SCHEMA_PROPERTY_DUPLICATE");
        }
        if (existing != null && (!existing.ownerDomain().equals(schema.ownerDomain())
                || !existing.producer().equals(schema.producer())
                || existing.serverAuthoritative() != schema.serverAuthoritative()
                || !existing.samplingPolicy().equals(schema.samplingPolicy()))) {
            return fail(OpsErrorCode.INVALID_STATE_TRANSITION, "A4_SCHEMA_METADATA_CONFLICT");
        }

        int nextRevision = currentRevision + 1;
        if (governanceMapper.advanceRevision(currentRevision, nextRevision) != 1) {
            return fail(OpsErrorCode.INVALID_STATE_TRANSITION, "A4_SCHEMA_VERSION_STALE");
        }
        String actor = authenticatedActor();
        try {
            long schemaId;
            if (existing == null) {
                governanceMapper.insertSchema(
                        schema.eventName(), schema.ownerDomain(), schema.familyKey(), schema.producer(), schema.consumer(),
                        schema.serverAuthoritative(), schema.samplingPolicy(), nextRevision, actor, schema.reason());
                EventSchemaRecord inserted = governanceMapper.findSchema(schema.eventName());
                if (inserted == null) {
                    throw new IllegalStateException("A4_SCHEMA_INSERT_NOT_VISIBLE");
                }
                schemaId = inserted.id();
            } else {
                schemaId = existing.id();
                if (governanceMapper.updateSchemaRevision(schemaId, nextRevision, actor, schema.reason()) != 1) {
                    throw new IllegalStateException("A4_SCHEMA_UPDATE_FAILED");
                }
            }
            governanceMapper.insertProperty(schemaId, schema.propertyName(), schema.propertyType(), nextRevision);
            if (schema.extensionDomain()
                    && governanceMapper.completeDomainExtension(schema.ownerDomain(), schema.eventName()) != 1) {
                throw new IllegalStateException("A4_DOMAIN_EXTENSION_CLOSE_FAILED");
            }
        } catch (DuplicateKeyException ex) {
            return fail(OpsErrorCode.INVALID_STATE_TRANSITION, "A4_SCHEMA_DUPLICATE");
        }
        auditRequired("A4_EVENT_SCHEMA_REGISTERED", "A4_EVENT_SCHEMA", schema.eventName(), idempotencyKey, schema.reason(),
                Map.of(
                        "eventName", schema.eventName(),
                        "ownerDomain", schema.ownerDomain(),
                        "producer", schema.producer(),
                        "consumer", schema.consumer(),
                        "propertyName", schema.propertyName(),
                        "propertyType", schema.propertyType(),
                        "serverAuthoritative", schema.serverAuthoritative(),
                        "samplingPolicy", schema.samplingPolicy(),
                        "beforeVersion", schema.expectedVersion(),
                        "afterVersion", "v" + nextRevision));
        return overview();
    }

    private ApiResult<EventDomainExtensionBatch> registerDomainExtensionOnce(
            NormalizedDomainExtension extension, String idempotencyKey) {
        if (governanceMapper.countDomainExtension(extension.domainName(), extension.eventName()) > 0) {
            return fail(OpsErrorCode.INVALID_STATE_TRANSITION, "A4_DOMAIN_EXTENSION_DUPLICATE");
        }
        String actor = authenticatedActor();
        try {
            governanceMapper.insertDomainExtension(
                    extension.domainName(), extension.eventName(), extension.producer(), extension.consumer(),
                    actor, extension.reason());
        } catch (DuplicateKeyException ex) {
            return fail(OpsErrorCode.INVALID_STATE_TRANSITION, "A4_DOMAIN_EXTENSION_DUPLICATE");
        }
        auditRequired("A4_DOMAIN_EXTENSION_REGISTERED", "A4_DOMAIN_EXTENSION", extension.eventName(),
                idempotencyKey, extension.reason(), Map.of(
                        "domainName", extension.domainName(),
                        "eventName", extension.eventName(),
                        "producer", extension.producer(),
                        "consumer", extension.consumer()));
        return ApiResult.ok(new EventDomainExtensionBatch(
                "manual-" + slug(extension.domainName() + "-" + extension.eventName()),
                "登记扩展工单", "registered", extension.producer(), "待 schema 注册和归属确认",
                List.of(new EventDomainItem(extension.domainName(), true)),
                List.of(row(extension.eventName(), "生产方 " + extension.producer() + " → 消费方 " + extension.consumer()))));
    }

    private void auditRequired(
            String action,
            String resourceType,
            String resourceId,
            String idempotencyKey,
            String reason,
            Map<String, Object> extraDetail) {
        Map<String, Object> detail = new LinkedHashMap<>(extraDetail);
        detail.put("reason", reason);
        detail.put("idempotencyKey", idempotencyKey.trim());
        auditLogService.recordRequired(AuditLogWriteRequest.builder()
                .action(action)
                .resourceType(resourceType)
                .resourceId(resourceId)
                .actorType("ADMIN")
                .actorUsername(authenticatedActor())
                .result("SUCCESS")
                .riskLevel("MEDIUM")
                .detail(detail)
                .build());
    }

    private String normalizeParamValue(String paramKey, String rawValue) {
        String value = normalizeTextValue(rawValue);
        return switch (paramKey) {
            case "day0" -> {
                String digits = value.replaceAll("[^0-9]", "");
                if (!value.matches("(?i)^\\s*\\d{1,4}\\s*(?:秒|s|sec|seconds?)?\\s*$") || !StringUtils.hasText(digits)) {
                    throw new IllegalArgumentException("A4_DAY0_VALUE_INVALID");
                }
                int seconds = Integer.parseInt(digits);
                if (seconds < 30 || seconds > 600) {
                    throw new IllegalArgumentException("A4_DAY0_VALUE_INVALID");
                }
                yield seconds + " 秒";
            }
            case "event_retention" -> {
                String digits = value.replaceAll("[^0-9]", "");
                if (!value.matches("^\\s*\\d{1,3}\\s*(?:个月|月|months?)?\\s*$") || !StringUtils.hasText(digits)) {
                    throw new IllegalArgumentException("A4_EVENT_RETENTION_INVALID");
                }
                int months = Integer.parseInt(digits);
                if (months < 13 || months > 60) {
                    throw new IllegalArgumentException("A4_EVENT_RETENTION_INVALID");
                }
                yield months + " 个月";
            }
            case "sampling" -> normalizeSampling(value, false);
            default -> value;
        };
    }

    private String normalizeSampling(String value, boolean protectedFamily) {
        String compact = value.replace(" ", "");
        for (String protectedName : List.of("资金", "风控", "转化")) {
            java.util.regex.Matcher protectedMatcher = java.util.regex.Pattern
                    .compile(protectedName + "[^0-9]*(\\d{1,3})%?")
                    .matcher(compact);
            if (protectedMatcher.find() && Integer.parseInt(protectedMatcher.group(1)) != 100) {
                throw new IllegalArgumentException("A4_PROTECTED_EVENT_SAMPLING_INVALID");
            }
        }
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("(\\d{1,3})%?").matcher(compact);
        if (!matcher.find()) {
            throw new IllegalArgumentException("A4_SAMPLING_VALUE_INVALID");
        }
        int percent = Integer.parseInt(matcher.group(1));
        if (percent < 1 || percent > 100 || (protectedFamily && percent != 100)) {
            throw new IllegalArgumentException(protectedFamily
                    ? "A4_PROTECTED_EVENT_SAMPLING_INVALID"
                    : "A4_SAMPLING_VALUE_INVALID");
        }
        return protectedFamily ? "100%" : "浏览/会话 " + percent + "% · 资金/风控/转化 100%";
    }

    private NormalizedSchema normalizeSchema(EventSchemaRegistrationRequest request) {
        String eventName = lowerRequired(request.eventName(), "A4_SCHEMA_EVENT_NAME_REQUIRED");
        String ownerDomain = lowerRequired(request.ownerDomain(), "A4_SCHEMA_OWNER_DOMAIN_REQUIRED");
        String producer = lowerRequired(request.producer(), "A4_SCHEMA_PRODUCER_REQUIRED");
        String consumer = textRequired(request.consumer(), "A4_SCHEMA_CONSUMER_REQUIRED");
        String propertyName = lowerRequired(request.propertyName(), "A4_SCHEMA_PROPERTY_REQUIRED");
        String propertyType = lowerRequired(request.propertyType(), "A4_SCHEMA_PROPERTY_TYPE_REQUIRED");
        String expectedVersion = lowerRequired(request.expectedVersion(), "A4_SCHEMA_VERSION_REQUIRED");
        String reason = normalizeReason(request.reason());
        if (containsSunsetTerm(eventName) || containsSunsetTerm(ownerDomain)) {
            throw new IllegalArgumentException("SUNSET_CAPABILITY_READONLY");
        }
        if (!eventName.matches("^[a-z][a-z0-9_]*\\.[a-z0-9]+(?:_[a-z0-9]+)*$") || !pastTenseEvent(eventName)) {
            throw new IllegalArgumentException("A4_SCHEMA_EVENT_NAME_INVALID");
        }
        String eventDomain = eventName.substring(0, eventName.indexOf('.'));
        boolean extensionDomain = !REGISTERED_DOMAINS.contains(ownerDomain)
                && governanceMapper.countRegistrableDomainExtension(ownerDomain, eventName) > 0;
        if (!ownerDomain.equals(eventDomain)
                || (!REGISTERED_DOMAINS.contains(ownerDomain) && !extensionDomain)) {
            throw new IllegalArgumentException("A4_SCHEMA_OWNER_DOMAIN_INVALID");
        }
        if (!PRODUCERS.contains(producer)) {
            throw new IllegalArgumentException("A4_SCHEMA_PRODUCER_INVALID");
        }
        if (!propertyName.matches("^[a-z][a-z0-9_]{0,63}$") || containsPiiTerm(propertyName)
                || Boolean.TRUE.equals(request.pii())) {
            throw new IllegalArgumentException("A4_SCHEMA_PII_REJECTED");
        }
        if (!PROPERTY_TYPES.contains(propertyType)) {
            throw new IllegalArgumentException("A4_SCHEMA_PROPERTY_TYPE_INVALID");
        }
        if (!expectedVersion.matches("^v[1-9][0-9]*$")) {
            throw new IllegalArgumentException("A4_SCHEMA_VERSION_INVALID");
        }
        String familyKey = familyKey(eventName);
        boolean protectedFamily = Set.of("conversion", "monetization", "risk").contains(familyKey);
        boolean authoritative = Boolean.TRUE.equals(request.serverAuthoritative());
        if (protectedFamily && (!producer.contains("server") || !authoritative)) {
            throw new IllegalArgumentException("A4_SERVER_AUTHORITY_REQUIRED");
        }
        String sampling = normalizeSampling(lowerRequired(request.samplingPolicy(), "A4_SCHEMA_SAMPLING_REQUIRED"), protectedFamily);
        return new NormalizedSchema(eventName, ownerDomain, familyKey, producer, consumer, propertyName,
                propertyType, authoritative, sampling, expectedVersion, reason, extensionDomain);
    }

    private NormalizedDomainExtension normalizeDomainExtension(EventDomainExtensionRequest request) {
        String domain = lowerRequired(request.domainName(), "A4_DOMAIN_EXTENSION_DOMAIN_REQUIRED");
        String eventName = lowerRequired(request.eventName(), "A4_DOMAIN_EXTENSION_EVENT_REQUIRED");
        String producer = textRequired(request.producer(), "A4_DOMAIN_EXTENSION_PRODUCER_REQUIRED");
        String consumer = textRequired(request.consumer(), "A4_DOMAIN_EXTENSION_CONSUMER_REQUIRED");
        if (containsSunsetTerm(domain) || containsSunsetTerm(eventName)) {
            throw new IllegalArgumentException("SUNSET_CAPABILITY_READONLY");
        }
        if (!domain.matches("^[a-z][a-z0-9_]{1,31}$") || REGISTERED_DOMAINS.contains(domain)) {
            throw new IllegalArgumentException("A4_DOMAIN_EXTENSION_INVALID");
        }
        if (!eventName.startsWith(domain + ".")
                || !eventName.matches("^[a-z][a-z0-9_]*\\.[a-z0-9]+(?:_[a-z0-9]+)*$")
                || !pastTenseEvent(eventName)) {
            throw new IllegalArgumentException("A4_DOMAIN_EXTENSION_EVENT_INVALID");
        }
        return new NormalizedDomainExtension(domain, eventName, producer, consumer, normalizeReason(request.reason()));
    }

    private String familyKey(String eventName) {
        String domain = eventName.substring(0, eventName.indexOf('.'));
        if (domain.equals("risk") || eventName.equals("auth.login_locked")) return "risk";
        if (Set.of("wallet", "withdraw", "earnings", "commission", "staking", "exchange", "genesis").contains(domain)) {
            return "monetization";
        }
        if (Set.of("store", "checkout", "trial").contains(domain) || eventName.equals("referral.invite_sent")) {
            return "conversion";
        }
        if (Set.of("daily", "quest", "nova").contains(domain) || eventName.equals("app.dau")) return "retention";
        if (Set.of("phase", "admin").contains(domain)) return "phase_admin";
        return "acquisition";
    }

    private boolean pastTenseEvent(String eventName) {
        String suffix = eventName.substring(eventName.indexOf('.') + 1);
        String action = suffix.substring(suffix.lastIndexOf('_') + 1);
        return action.endsWith("ed") || Set.of("sent", "paid", "held", "bound", "dau").contains(action);
    }

    private String requestHash(String... parts) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String part : parts) {
                digest.update((part == null ? "" : part).getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    private int requiredRevision(Integer revision) {
        if (revision == null || revision < 1) throw new IllegalStateException("A4_SCHEMA_REVISION_MISSING");
        return revision;
    }

    private boolean validReason(String reason) {
        if (!StringUtils.hasText(reason)) return false;
        int length = reason.trim().length();
        return length >= REASON_MIN_LENGTH && length <= REASON_MAX_LENGTH;
    }

    private String normalizeReason(String reason) {
        if (!validReason(reason)) throw new IllegalArgumentException("A4_REASON_LENGTH_INVALID");
        return reason.trim();
    }

    private String lowerRequired(String value, String message) {
        return textRequired(value, message).toLowerCase(Locale.ROOT);
    }

    private String textRequired(String value, String message) {
        if (!StringUtils.hasText(value)) throw new IllegalArgumentException(message);
        String normalized = value.trim();
        if (normalized.length() > MUTATION_VALUE_MAX_LENGTH) throw new IllegalArgumentException("A4_VALUE_TOO_LONG");
        return normalized;
    }

    private String text(String value) {
        return StringUtils.hasText(value) ? value.trim() : "";
    }

    private String authenticatedActor() {
        String actor = AdminActorResolver.resolve(null);
        return StringUtils.hasText(actor) ? actor : "authenticated-admin";
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
                        "浏览/会话 10% · 资金/风控/转化 100%", false, "admin.a4.event.kpi.sampling"));
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

    private static EventFamilyDefinition familyDef(
            String key,
            String title,
            String sub,
            String sample,
            String serverAuth,
            List<String> actionPrefixes,
            EventDetailRow... events) {
        return new EventFamilyDefinition(key, title, sub, sample, serverAuth, actionPrefixes, List.of(events));
    }

    private static EventDetailRow row(String item, String desc) {
        return new EventDetailRow(item, desc);
    }

    private record EventFamilyDefinition(
            String key,
            String title,
            String sub,
            String sample,
            String serverAuth,
            List<String> actionPrefixes,
            List<EventDetailRow> events) {
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
        boolean mentionsRawIdentifier = normalized.contains("phone")
                || normalized.contains("mobile")
                || normalized.contains("address")
                || normalized.contains("手机号")
                || normalized.contains("地址");
        boolean explicitlyPseudonymized = normalized.endsWith("_hash") || normalized.endsWith("_id");
        return mentionsRawIdentifier && !explicitlyPseudonymized;
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

    private record NormalizedSchema(
            String eventName,
            String ownerDomain,
            String familyKey,
            String producer,
            String consumer,
            String propertyName,
            String propertyType,
            boolean serverAuthoritative,
            String samplingPolicy,
            String expectedVersion,
            String reason,
            boolean extensionDomain) {
    }

    private record NormalizedDomainExtension(
            String domainName,
            String eventName,
            String producer,
            String consumer,
            String reason) {
    }
}
