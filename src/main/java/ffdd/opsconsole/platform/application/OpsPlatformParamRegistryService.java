package ffdd.opsconsole.platform.application;

import ffdd.opsconsole.common.boundary.ApplicationService;
import ffdd.opsconsole.device.domain.ComputeConfigRegistry;
import ffdd.opsconsole.platform.domain.PlatformConfigItem;
import ffdd.opsconsole.platform.domain.PlatformParamRegistrySource;
import ffdd.opsconsole.platform.dto.PlatformParamRegistryOverview;
import ffdd.opsconsole.platform.dto.PlatformParamRegistryRow;
import ffdd.opsconsole.platform.dto.PlatformParamRegistrySourceState;
import ffdd.opsconsole.platform.dto.PlatformParamRegistryStats;
import ffdd.opsconsole.shared.api.ApiResult;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.util.StringUtils;

/** A5 read model. Owner routes and values are resolved on the server, never guessed by the UI. */
@ApplicationService
@RequiredArgsConstructor
public class OpsPlatformParamRegistryService {
    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_LOCAL_DATE_TIME;
    private static final Map<String, String> DOMAIN_LABELS = Map.ofEntries(
            Map.entry("A", "平台基础"),
            Map.entry("B", "运营总览"),
            Map.entry("C", "用户与账户"),
            Map.entry("D", "资金与财务"),
            Map.entry("E", "设备与商城"),
            Map.entry("F", "分销与团队"),
            Map.entry("G", "金融产品"),
            Map.entry("H", "增长与活动"),
            Map.entry("I", "内容与合规"),
            Map.entry("J", "紧急与合规控制"),
            Map.entry("K", "风控与反作弊"),
            Map.entry("L", "数据与分析"),
            Map.entry("M", "客服中心"));

    /**
     * 健康类参数键 → A3 实时指标名。两边必须是同一份事实:键名不同但指向同一管道时,
     * 只允许在这里做一次映射,不允许各自维护一份取值逻辑。
     */
    private static final Map<String, String> HEALTH_METRIC_NAMES = Map.of(
            "admin.health.event_pipeline", "事件待投递",
            "admin.health.ledger_write", "资金账本可读性");

    private final PlatformParamRegistrySource configSource;
    private final PlatformEmergencyStateProvider emergencyStateProvider;
    private final PlatformSystemHealthProvider healthProvider;

    public ApiResult<PlatformParamRegistryOverview> overview() {
        LinkedHashMap<String, PlatformParamRegistryRow> rows = new LinkedHashMap<>();
        int unmappedConfigCount = 0;
        for (PlatformConfigItem item : configSource.findAllActive()) {
            String key = item.configKey();
            if (key != null && (key.equals("E.compute.computeShareEnabled")
                    || key.equals("platform.compute.download.allowedTargets")
                    || key.startsWith("E.compute.gpuTier.") || key.startsWith("E.compute.download.")
                    || key.equals("risk.k1.release.appAttestationReleaseHours"))) continue;
            // H9 acceptance runs store payloads here, not operational parameters.
            // Keep those payloads private without hiding unknown config families.
            if ("growth_sandbox".equals(item.configGroup())
                    && item.configKey() != null
                    && item.configKey().matches("h9\\.sb\\.[A-Za-z0-9][A-Za-z0-9._-]{7,95}\\.(v|data)")) {
                continue;
            }
            PlatformParamRegistryRow row = fromConfig(item);
            if (row == null) {
                // An unregistered family must not disclose values or take down known parameters.
                unmappedConfigCount++;
                continue;
            }
            merge(rows, row);
        }
        for (String field : List.of("allowReplacement", "minReplacementIntervalDays", "replacementWindowDays", "maxReplacementsInWindow")) {
            String key = ComputeConfigRegistry.phoneBindingKey(field);
            if (!rows.containsKey(key)) {
                merge(rows, fromConfig(new PlatformConfigItem(null, key, ComputeConfigRegistry.defaultValue(key),
                        "STRING", ComputeConfigRegistry.CONFIG_GROUP, "ADMIN", "默认值", 1, null, null)));
            }
        }
        String phonePolicyKey = "E.compute.phoneCalibration.policy";
        if (!rows.containsKey(phonePolicyKey)) {
            merge(rows, fromConfig(new PlatformConfigItem(null, phonePolicyKey,
                    "{\"revision\":0,\"current\":null,\"scheduled\":null}", "JSON", "e6_compute", "ADMIN", "尚未发布", 1, null, null)));
        }
        int configCount = rows.size();

        List<Map<String, Object>> emergency = emergencyStateProvider.currentKillSwitches();
        boolean emergencyPartial = false;
        int emergencyCount = 0;
        for (Map<String, Object> state : emergency) {
            String key = text(state.get("key"));
            if (key.endsWith("-unavailable") || Boolean.FALSE.equals(state.get("available"))) {
                emergencyPartial = true;
                continue;
            }
            merge(rows, fromEmergency(state));
            emergencyCount++;
        }

        List<PlatformParamRegistryRow> ordered = rows.values().stream()
                .sorted(Comparator.comparing(PlatformParamRegistryRow::domain)
                        .thenComparing(PlatformParamRegistryRow::ownerCode)
                        .thenComparing(PlatformParamRegistryRow::canonicalKey))
                .toList();
        int domainCount = (int) ordered.stream().map(PlatformParamRegistryRow::domain).distinct().count();
        int highSensitivityCount = (int) ordered.stream().filter(PlatformParamRegistryRow::operationConfirm).count();
        List<PlatformParamRegistrySourceState> sources = List.of(
                new PlatformParamRegistrySourceState(
                        "config", "系统参数",
                        unmappedConfigCount > 0 ? "PARTIAL" : configCount == 0 ? "EMPTY" : "READY", configCount,
                        unmappedConfigCount > 0
                                ? "有 " + unmappedConfigCount + " 项参数还没登记所属页面，暂不显示它们的值；请联系平台运维补登记。"
                                : "所有启用中的参数；手机换绑项没有设置时显示默认值"),
                new PlatformParamRegistrySourceState(
                        "emergency", "紧急开关当前状态", emergencyPartial ? "PARTIAL" : "READY", emergencyCount,
                        emergencyPartial ? "部分紧急开关或地区限制状态加载失败，未知状态不会显示成正常" : "紧急停用开关和地区访问限制的当前状态"));
        PlatformParamRegistryStats stats = new PlatformParamRegistryStats(
                ordered.size(), domainCount, highSensitivityCount, sources.size());
        return ApiResult.ok(new PlatformParamRegistryOverview(
                ordered, stats, sources, LocalDateTime.now().format(ISO)));
    }

    private PlatformParamRegistryRow fromConfig(PlatformConfigItem item) {
        String rawKey = required(item.configKey(), "A5_CONFIG_KEY_REQUIRED");
        String canonicalKey = canonicalConfigKey(rawKey);
        Owner owner = ownerFor(item.configGroup(), canonicalKey);
        if (owner == null) return null;
        String displayName = displayName(canonicalKey, owner);
        boolean secret = isSecretKey(canonicalKey);
        // admin_system_health 组的行是**存量快照**(种于 2026-06-24),不是当前事实。
        // 同一个事件管道 A3 实时现算、A5 读死行,于是同屏出现「严重积压」与「正常 · 延迟 1.2s」。
        // 这些键改由 A3 的实时 provider 供值;取不到就如实标过期,绝不把旧值当当前值。
        if ("admin_system_health".equalsIgnoreCase(item.configGroup())) {
            return liveHealthRow(canonicalKey, displayName, owner);
        }
        boolean retired = ComputeConfigRegistry.coeffKey("h5BaseFactor").equals(canonicalKey);
        String description = retired ? "已退役，值固定为 0，历史数据也不再生效。" : secret
                ? "这个敏感参数在「" + owner.label() + "」设置；这里只显示是否已配置，不显示具体值。"
                : "这个参数在「" + owner.label() + "」设置；这里只显示当前值。";
        String valueType = secret
                ? "SECRET"
                : StringUtils.hasText(item.valueType()) ? item.valueType().trim().toUpperCase(Locale.ROOT) : "STRING";
        return new PlatformParamRegistryRow(
                canonicalKey,
                displayName,
                description,
                owner.domain(),
                DOMAIN_LABELS.get(owner.domain()),
                owner.code(),
                owner.label(),
                owner.route(),
                retired ? "0" : secret ? "已配置（敏感值已隐藏）"
                        : "E.compute.phoneCalibration.policy".equals(canonicalKey)
                        ? phonePolicySummary(item.configValue())
                        : "risk.k1.release.releaseMode".equals(canonicalKey) ? "manual_only"
                        : Objects.toString(item.configValue(), ""),
                valueType,
                secret ? "" : unitFor(canonicalKey),
                "nx_config_item",
                "READY",
                item.updatedAt() == null ? "默认值" : item.updatedAt().format(ISO),
                !retired,
                true,
                false,
                "",
                false);
    }

    private String phonePolicySummary(String value) {
        try {
            var state = new com.fasterxml.jackson.databind.ObjectMapper().readTree(value);
            if (!state.has("revision") || !state.path("revision").canConvertToLong()) return "设置有误，请到「算力与设备配置」检查";
            if (state.path("revision").asLong() == 0) return "还没发布 · 请到「算力与设备配置」设置";
            return "发布版本 " + state.path("revision").asLong()
                    + (state.path("scheduled").isObject() ? " · 有待生效的版本" : "") + " · 到「算力与设备配置」查看";
        } catch (Exception invalid) { return "设置有误，请到「算力与设备配置」检查"; }
    }

    /**
     * 健康类参数的实时取值。
     *
     * <p>键名与 {@code MybatisPlatformSystemHealthProvider.currentHealth()} 的指标名对应:
     * {@code admin.health.event_pipeline} 即「事件待投递」。provider 每次现算并带 observedAt,
     * 与 A3 页读的是同一份事实,因此两页不可能再给出相反结论。</p>
     *
     * <p>provider 抛错或指标缺失时返回 {@code stale=true} 且值为「读取失败 · 不推测正常状态」——
     * 页面据此标「已过期」,而不是把 2026-06-24 的快照冒充当前权威值。</p>
     */
    private PlatformParamRegistryRow liveHealthRow(String canonicalKey, String displayName, Owner owner) {
        String metricName = HEALTH_METRIC_NAMES.get(canonicalKey);
        Map<String, Object> metric = null;
        boolean failed = metricName == null;
        if (!failed) {
            try {
                metric = healthProvider.currentHealth().stream()
                        .filter(row -> metricName.equals(text(row.get("name"))))
                        .findFirst()
                        .orElse(null);
                failed = metric == null;
            } catch (RuntimeException ex) {
                failed = true;
            }
        }
        String value = failed
                ? "加载失败 · 不显示为正常"
                : text(metric.get("metric"));
        String observedAt = failed ? "" : text(metric.get("observedAt"));
        boolean stale = failed || Boolean.TRUE.equals(metric.get("stale"));
        return new PlatformParamRegistryRow(
                canonicalKey,
                displayName,
                "这个指标取自「系统配置」的实时健康检查，和那里显示的一致；这里不缓存，也不代替实时告警。",
                owner.domain(),
                DOMAIN_LABELS.get(owner.domain()),
                owner.code(),
                owner.label(),
                owner.route(),
                StringUtils.hasText(value) ? value : "—",
                "STRING",
                "",
                "系统健康实时检查",
                failed ? "PARTIAL" : "READY",
                StringUtils.hasText(observedAt) ? observedAt : "未知",
                false,
                // 🔴 serverCanonical 必须为 true(与 fromConfig / fromEmergency 一致)。
                //    这里的值来自 A3 的实时 provider,是**服务端权威事实**;此前误传 false,
                //    而 PC 契约拒绝任何 serverCanonical != true 的行 —— 后端一部署,A5 就会
                //    因为这两行把整页判成「数据一致性校验未通过」,一条参数都读不到。
                true,
                true,
                observedAt,
                stale);
    }

    private boolean isSecretKey(String keyValue) {
        String key = lower(keyValue);
        return List.of(
                        ".secret", "_secret", ".password", "_password", ".passwd", "_passwd",
                        ".token", "_token", ".credential", "_credential", ".private_key", "_private_key",
                        ".access_key", "_access_key", ".api_key", "_api_key")
                .stream()
                .anyMatch(key::contains);
    }

    private PlatformParamRegistryRow fromEmergency(Map<String, Object> state) {
        String key = required(text(state.get("key")), "A5_EMERGENCY_KEY_REQUIRED");
        boolean geo = "geo-block".equals(key);
        Owner owner = geo
                ? new Owner("J", "J2", "地区访问限制", "/emergency/geo-block")
                : new Owner("J", "J1", "紧急停用开关", "/emergency/kill-switch");
        return new PlatformParamRegistryRow(
                geo ? "emergency.geo-block" : "emergency.gate." + key,
                required(text(state.get("name")), "A5_EMERGENCY_NAME_REQUIRED"),
                geo ? "地区访问限制当前状态" : "业务紧急停用开关当前状态",
                owner.domain(),
                DOMAIN_LABELS.get(owner.domain()),
                owner.code(),
                owner.label(),
                owner.route(),
                required(text(state.get("status")), "A5_EMERGENCY_STATUS_REQUIRED"),
                "ENUM",
                "",
                owner.code(),
                "READY",
                StringUtils.hasText(text(state.get("lastChange"))) ? text(state.get("lastChange")) : "未知",
                true,
                true,
                true,
                StringUtils.hasText(text(state.get("lastChange"))) ? text(state.get("lastChange")) : "",
                false);
    }

    private void merge(Map<String, PlatformParamRegistryRow> rows, PlatformParamRegistryRow candidate) {
        PlatformParamRegistryRow existing = rows.get(candidate.canonicalKey());
        if (existing == null) {
            rows.put(candidate.canonicalKey(), candidate);
            return;
        }
        if (!Objects.equals(existing.currentValue(), candidate.currentValue())
                || !Objects.equals(existing.ownerCode(), candidate.ownerCode())) {
            throw new IllegalStateException("A5_DUPLICATE_CANONICAL_KEY:" + candidate.canonicalKey());
        }
        if ("未知".equals(existing.updatedAt()) && !"未知".equals(candidate.updatedAt())) {
            rows.put(candidate.canonicalKey(), candidate);
        }
    }

    private String canonicalConfigKey(String key) {
        String trimmed = key.trim();
        return trimmed.startsWith("wallet.withdrawal.") ? trimmed.substring("wallet.".length()) : trimmed;
    }

    private Owner ownerFor(String groupValue, String keyValue) {
        String group = lower(groupValue);
        String key = lower(keyValue);
        if ("admin_a2".equals(group) || "admin_audit".equals(group) || key.startsWith("admin.a2.") || key.startsWith("admin.audit.")) {
            return new Owner("A", "A2", "操作记录与审批", "/platform/audit");
        }
        if ("admin_a4_event".equals(group) || key.startsWith("admin.a4.")) {
            return new Owner("A", "A4", "用户行为事件", "/platform/events");
        }
        if (group.startsWith("admin") || key.startsWith("feature.") || key.startsWith("a.sys.")) {
            return new Owner("A", "A3", "系统配置", "/platform/config");
        }
        if (group.startsWith("auth") || key.startsWith("auth.")) {
            return new Owner("C", "C6", "注册与登录风险", "/users/reg-risk");
        }
        if ("content_support_load".equals(group) || key.startsWith("content.support.load.")) {
            return new Owner("M", "M1", "客服工作台", "/service/overview");
        }
        if (group.startsWith("content_session") || group.startsWith("content-session") || key.startsWith("i.session.")) {
            return new Owner("M", "M5", "服务规则与话术", "/service/scripts");
        }
        if ("content".equals(group) || key.startsWith("disclosure.")) {
            return new Owner("I", "I5", "风险披露", "/content/disclosures");
        }
        if ("e2".equals(group) || key.startsWith("e.task.")) {
            return new Owner("E", "E2", "设备收益与任务", "/devices/tasks");
        }
        if (key.startsWith("store.bundle.discount.")) {
            return new Owner("E", "E1", "商品管理与上架条件", "/devices/pricing");
        }
        if ("e6_compute".equals(group) || key.startsWith("e.compute.")) {
            return new Owner("E", "E6", "算力与设备配置", "/devices/compute-config");
        }
        if (List.of("dailyusdtperbaseline", "nexperusdt").contains(key)) {
            return new Owner("E", "E6", "算力与设备配置", "/devices/compute-config");
        }
        if ("finance-topup".equals(group) || key.startsWith("finance.topup.")) {
            return new Owner("D", "D1", "充值对账中心", "/finance/recon");
        }
        if (key.startsWith("finance.payout_vnd.")) {
            return new Owner("D", "D7", "法币提现参数", "/finance/payout-vnd");
        }
        if (key.startsWith("growth.phase.") || key.startsWith("h1.rhythm.")) {
            return new Owner("H", "H1", "运营阶段设置", "/growth/phase");
        }
        if (key.startsWith("growth.quest.")) {
            return new Owner("H", "H3", "用户任务管理", "/growth/quest");
        }
        if (key.startsWith("growth.event.")) {
            return new Owner("H", "H4", "活动中心", "/growth/events");
        }
        if (key.startsWith("growth.wheel.")) {
            return new Owner("H", "H3", "用户任务管理", "/growth/quest");
        }
        if (key.startsWith("growth.checkin.") || key.startsWith("growth.earn_milestone.")) {
            return new Owner("H", "H5", "签到与UVT奖励", "/growth/daily");
        }
        if (key.startsWith("growth.withdraw_nex_gate.")) {
            return new Owner("H", "H1", "运营阶段设置", "/growth/phase");
        }
        if (key.startsWith("growth.public_stats.")) {
            return new Owner("H", "H9", "首页与历史展示设置", "/growth/public-stats");
        }
        if (key.startsWith("k.rewards.")) {
            return new Owner("H", "H8", "新人礼与邀请奖励", "/growth/referral-rewards");
        }
        if (key.startsWith("g.staking.")) {
            return new Owner("G", "G1", "质押产品配置", "/finance-products/staking");
        }
        if (key.startsWith("g.genesis.")) {
            return new Owner("G", "G4", "创世节点销售与收益", "/finance-products/genesis");
        }
        if (key.startsWith("risk.k1.")) {
            return new Owner("K", "K1", "多账户风险排查", "/risk/multi-account");
        }
        if (key.startsWith("payout.")) {
            return new Owner("D", "D5", "提现参数配置", "/finance/params");
        }
        if (key.startsWith("treasury.d3.")) {
            return new Owner("D", "D3", "资金储备与应付款", "/finance/pool");
        }
        if ("commission/cooling-days".equals(key)) {
            return new Owner("F", "F2", "团队分成", "/network/royalty");
        }
        if (key.contains(".binary.")) {
            return new Owner("F", "F3", "左右团队业绩结算", "/network/binary");
        }
        if (key.contains(".unilevel.")) {
            return new Owner("F", "F2", "团队分成", "/network/royalty");
        }
        if (key.contains(".pool.")) {
            return new Owner("F", "F4", "奖励池、名额与排行榜", "/network/leadership-pool");
        }
        if (key.startsWith("team.")) {
            return new Owner("F", "F1", "团队等级晋升", "/network/v-rank");
        }
        if (key.startsWith("wallet.dual-ledger.")) {
            return new Owner("B", "B1", "资金与应付款总览", "/overview/dual-ledger");
        }
        if (key.startsWith("wallet.exchange.")) {
            return new Owner("G", "G2", "兑换风险规则", "/finance-products/exchange");
        }
        if (key.startsWith("wallet.nex_market.")) {
            return new Owner("G", "G3", "UVT价格管理", "/finance-products/market");
        }
        if (key.startsWith("withdrawal.")) {
            return new Owner("D", "D5", "提现参数配置", "/finance/params");
        }
        return null;
    }

    private String displayName(String key, Owner owner) {
        if ("feature.ops.maintenanceBanner".equals(key)) {
            return "维护公告横幅";
        }
        if ("E.compute.phoneCalibration.policy".equals(key)) return "手机算力规则（Android 硬件 / iPhone 机型）与档位分界";
        if (ComputeConfigRegistry.phoneBindingKey("allowReplacement").equals(key)) return "允许更换绑定手机";
        if (ComputeConfigRegistry.phoneBindingKey("minReplacementIntervalDays").equals(key)) return "手机换绑最短间隔天数";
        if (ComputeConfigRegistry.phoneBindingKey("replacementWindowDays").equals(key)) return "手机换绑滚动窗口天数";
        if (ComputeConfigRegistry.phoneBindingKey("maxReplacementsInWindow").equals(key)) return "手机换绑窗口内次数上限";
        if (ComputeConfigRegistry.coeffKey("h5BaseFactor").equals(key)) return "H5 基础托管系数（已退役）";
        String normalized = lower(key);
        if (normalized.startsWith("finance.topup.channel.")) {
            String[] parts = key.split("\\.");
            String channel = parts.length > 3 ? parts[3].toUpperCase(Locale.ROOT) : "充值";
            return channel + " 渠道 · " + tokenLabel(parts[parts.length - 1]);
        }
        if (normalized.startsWith("wallet.nex_market.")) {
            return "NEX 行情 · " + tokenLabel(lastToken(key));
        }
        if (normalized.startsWith("e.compute.gputier.")) {
            return "GPU 档位关键词";
        }
        return owner.code() + " · " + tokenLabel(lastToken(key));
    }

    private String lastToken(String key) {
        String[] parts = key.split("\\.");
        return parts[parts.length - 1];
    }

    private String tokenLabel(String value) {
        return switch (lower(value).replace('-', '_')) {
            case "session_timeout_min" -> "会话超时时间";
            case "withdraw_review_floor_usd" -> "提现人工复核门槛";
            case "reason_min_chars", "reason_min" -> "操作理由最短长度";
            case "schema_version" -> "字段版本";
            case "day0" -> "Day0 接入窗口";
            case "event_retention" -> "事件留存期";
            case "retention" -> "留存口径";
            case "sampling" -> "事件采样策略";
            case "retention_months" -> "审计日志保留月数";
            case "admin_api_availability" -> "后台接口可用性";
            case "event_pipeline" -> "事件管道状态";
            case "ledger_write" -> "账本写入状态";
            case "sse" -> "实时推送通道状态";
            case "captcha_off_window" -> "验证码关闭窗口";
            case "captcha_trigger_failures" -> "验证码触发失败次数";
            case "captcha_triggered_today" -> "今日验证码触发数";
            case "lock_duration_minutes" -> "短期锁定时长";
            case "locked_long_count" -> "长期锁定账户数";
            case "locked_short_count" -> "短期锁定账户数";
            case "login_lock_threshold" -> "登录锁定阈值";
            case "login_long_lock_threshold" -> "长期锁定阈值";
            case "long_lock_duration_hours" -> "长期锁定时长";
            case "otp_max_24h" -> "24 小时验证码上限";
            case "otp_quota_per_hour" -> "每小时验证码额度";
            case "otp_sent_today" -> "今日验证码发送数";
            case "otp_ttl_minutes", "otp_ttl_seconds" -> "验证码有效期";
            case "sponsor_bind_dedup_enabled" -> "推荐人绑定去重开关";
            case "stuffing_clusters_7d" -> "近 7 日撞库簇数";
            case "access_ttl_hours" -> "访问会话有效期";
            case "otp_cooldown_seconds" -> "验证码发送冷却";
            case "trade" -> "交易披露门禁";
            case "transfer" -> "转账披露门禁";
            case "withdraw" -> "提现披露门禁";
            case "cooldownhours" -> "顾问推送冷却";
            case "delayms" -> "顾问首次推送延迟";
            case "enabled" -> "启用状态";
            case "maxpersession" -> "单会话推送上限";
            case "timeoutfallback" -> "超时降级开关";
            case "autobalance" -> "自动均衡开关";
            case "burstcap" -> "突发接待上限";
            case "defaultcap" -> "默认接待上限";
            case "overflowqueue" -> "溢出队列";
            case "quiethourbalance" -> "静默时段均衡开关";
            case "warnpct" -> "负载预警阈值";
            case "audience" -> "推送受众";
            case "computeshareenabled" -> "算力共享开关";
            case "continuityfullhours" -> "连续满载时长";
            case "enguide" -> "英文下载指引";
            case "entitle" -> "英文下载标题";
            case "url" -> "客户端下载地址";
            case "zhguide" -> "中文下载指引";
            case "zhtitle" -> "中文下载标题";
            case "nexperusdt" -> "收益估算比例";
            case "cardlockhours" -> "卡片锁定时长";
            case "cardretrylimit" -> "同卡重试上限";
            case "threedsthreshold" -> "3DS 强验证门槛";
            case "fee" -> "渠道费率";
            case "min_amount" -> "最小充值额";
            case "fee_buffer_usd" -> "卡费缓冲";
            case "primary" -> "主支付服务商";
            case "reward_nex" -> "签到奖励";
            case "tick_interval_seconds" -> "级联刷新间隔";
            case "featured" -> "活动精选状态";
            case "reward" -> "活动奖励";
            case "status" -> "当前状态";
            case "commission_tightening_pct" -> "佣金收紧比例";
            case "current" -> "当前阶段";
            case "current_month", "currentmonth" -> "当前运营月份";
            case "device_release_pacing_pct" -> "设备释放节奏";
            case "genesis_emissions_open" -> "Genesis 排放开关";
            case "commissiontighteningpct" -> "月度佣金收紧比例";
            case "quest_reward_multiplier" -> "任务奖励倍率";
            case "tri_reward" -> "首日任务奖励";
            case "window_ms" -> "任务有效窗口";
            case "phaseprogresspct" -> "阶段进度";
            case "totalmonths" -> "总运营月数";
            case "matchrate" -> "双轨匹配率";
            case "cooldown" -> "佣金冷却周期";
            case "ratio" -> "领导池比例";
            case "l1" -> "一代佣金比例";
            case "healthy_pct" -> "双账本健康阈值";
            case "redline_pct" -> "双账本红线";
            case "run_risk_pct" -> "挤兑风险阈值";
            case "fee_pct" -> "兑换费率";
            case "queue_mode" -> "超额队列策略";
            case "user_daily_cap_usdt" -> "用户每日兑换上限";
            case "active_day_index" -> "当前曲线日";
            case "loop" -> "行情循环策略";
            case "pin" -> "行情控制锚点";
            case "schedule" -> "行情推进计划";
            case "pump_probability" -> "上涨概率";
            case "volatility_pct" -> "波动幅度";
            case "daily_count_limit" -> "每日提现次数上限";
            case "fee_rate" -> "提现费率";
            case "max_balance_pct" -> "余额提现比例上限";
            case "min_usdt" -> "最小提现额";
            default -> "服务端参数";
        };
    }

    private String unitFor(String keyValue) {
        String key = lower(keyValue);
        if (key.endsWith("_pct") || key.endsWith(".pct") || key.contains("percentage")) return "%";
        if (key.endsWith("_seconds") || key.endsWith(".seconds")) return "秒";
        if (key.endsWith("_minutes") || key.endsWith(".minutes")) return "分钟";
        if (key.endsWith("_hours") || key.endsWith(".hours")) return "小时";
        if (key.endsWith("_ms") || key.endsWith(".delayms")) return "毫秒";
        if (key.endsWith("_usd") || key.endsWith(".usdt") || key.endsWith("_usdt")) return "USDT";
        return "";
    }

    private String required(String value, String message) {
        if (!StringUtils.hasText(value)) throw new IllegalStateException(message);
        return value.trim();
    }

    private String lower(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private record Owner(String domain, String code, String label, String route) {
    }
}
