package ffdd.opsconsole.platform.application;

import ffdd.opsconsole.common.boundary.ApplicationService;
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
            Map.entry("B", "总览驾驶舱"),
            Map.entry("C", "用户与账户"),
            Map.entry("D", "资金与财务"),
            Map.entry("E", "设备与商城"),
            Map.entry("F", "分销与团队"),
            Map.entry("G", "金融产品"),
            Map.entry("H", "增长节奏"),
            Map.entry("I", "内容合规"),
            Map.entry("J", "紧急合规"),
            Map.entry("M", "客服中心"));

    private final PlatformParamRegistrySource configSource;
    private final PlatformEmergencyStateProvider emergencyStateProvider;

    public ApiResult<PlatformParamRegistryOverview> overview() {
        LinkedHashMap<String, PlatformParamRegistryRow> rows = new LinkedHashMap<>();
        for (PlatformConfigItem item : configSource.findAllActive()) {
            merge(rows, fromConfig(item));
        }
        int configCount = rows.size();

        List<Map<String, Object>> emergency = emergencyStateProvider.currentKillSwitches();
        boolean emergencyPartial = false;
        int emergencyCount = 0;
        for (Map<String, Object> state : emergency) {
            String key = text(state.get("key"));
            if (key.endsWith("-unavailable") || "读取失败".equals(text(state.get("status")))) {
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
                        "config", "服务端配置中心", configCount == 0 ? "EMPTY" : "READY", configCount,
                        "仅统计数据库中启用且未删除的配置"),
                new PlatformParamRegistrySourceState(
                        "emergency", "应急控制实时态", emergencyPartial ? "PARTIAL" : "READY", emergencyCount,
                        emergencyPartial ? "部分 J1/J2 状态读取失败，未将未知值伪装为正常" : "J1/J2 服务端权威状态"));
        PlatformParamRegistryStats stats = new PlatformParamRegistryStats(
                ordered.size(), domainCount, highSensitivityCount, sources.size());
        return ApiResult.ok(new PlatformParamRegistryOverview(
                ordered, stats, sources, LocalDateTime.now().format(ISO)));
    }

    private PlatformParamRegistryRow fromConfig(PlatformConfigItem item) {
        String rawKey = required(item.configKey(), "A5_CONFIG_KEY_REQUIRED");
        String canonicalKey = canonicalConfigKey(rawKey);
        Owner owner = ownerFor(item.configGroup(), canonicalKey);
        String displayName = displayName(canonicalKey, owner);
        boolean secret = isSecretKey(canonicalKey);
        String description = secret
                ? "该敏感参数由" + owner.label() + "在服务端维护；A5 只确认已配置，不返回原始值。"
                : "该参数由" + owner.label() + "在服务端维护；A5 仅展示当前值和归属入口。";
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
                secret ? "已配置（敏感值已隐藏）" : Objects.toString(item.configValue(), ""),
                valueType,
                secret ? "" : unitFor(canonicalKey),
                "nx_config_item",
                "READY",
                item.updatedAt() == null ? "未知" : item.updatedAt().format(ISO),
                !"admin_system_health".equalsIgnoreCase(item.configGroup()),
                true);
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
                ? new Owner("J", "J2", "J2 地区屏蔽", "/emergency/geo-block")
                : new Owner("J", "J1", "J1 功能闸", "/emergency/kill-switch");
        return new PlatformParamRegistryRow(
                geo ? "emergency.geo-block" : "emergency.gate." + key,
                required(text(state.get("name")), "A5_EMERGENCY_NAME_REQUIRED"),
                geo ? "地区准入策略实时状态" : "全局业务功能闸实时状态",
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
                true);
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
            return new Owner("A", "A2", "A2 审计与追溯", "/platform/audit");
        }
        if ("admin_a4_event".equals(group) || key.startsWith("admin.a4.")) {
            return new Owner("A", "A4", "A4 埋点事件中台", "/platform/events");
        }
        if (group.startsWith("admin") || key.startsWith("feature.") || key.startsWith("a.sys.")) {
            return new Owner("A", "A3", "A3 系统配置", "/platform/config");
        }
        if (group.startsWith("auth") || key.startsWith("auth.")) {
            return new Owner("C", "C6", "C6 注册与登录风控", "/users/reg-risk");
        }
        if ("content_support_load".equals(group) || key.startsWith("content.support.load.")) {
            return new Owner("M", "M1", "M1 客服总览", "/service/overview");
        }
        if (group.startsWith("content_session") || group.startsWith("content-session") || key.startsWith("i.session.")) {
            return new Owner("M", "M5", "M5 话术与模板配置", "/service/scripts");
        }
        if ("content".equals(group) || key.startsWith("disclosure.")) {
            return new Owner("I", "I5", "I5 风险披露", "/content/disclosures");
        }
        if ("e6_compute".equals(group) || key.startsWith("e.compute.")) {
            return new Owner("E", "E6", "E6 算力与设备配置", "/devices/compute-config");
        }
        if ("finance-topup".equals(group) || key.startsWith("finance.topup.")) {
            return new Owner("D", "D1", "D1 充值对账中心", "/finance/recon");
        }
        if (key.startsWith("growth.phase.") || key.startsWith("h1.rhythm.")) {
            return new Owner("H", "H1", "H1 Phase 调度器", "/growth/phase");
        }
        if (key.startsWith("growth.quest.")) {
            return new Owner("H", "H3", "H3 任务引擎", "/growth/quest");
        }
        if (key.startsWith("growth.event.")) {
            return new Owner("H", "H4", "H4 增长活动", "/growth/events");
        }
        if (key.startsWith("growth.checkin.") || key.startsWith("growth.earn_milestone.")) {
            return new Owner("H", "H5", "H5 签到与里程碑", "/growth/daily");
        }
        if (key.contains(".binary.")) {
            return new Owner("F", "F3", "F3 双轨结算引擎", "/network/binary");
        }
        if (key.contains(".unilevel.")) {
            return new Owner("F", "F2", "F2 代际与同级佣金", "/network/royalty");
        }
        if (key.contains(".pool.")) {
            return new Owner("F", "F4", "F4 领导池", "/network/leadership-pool");
        }
        if (key.startsWith("team.")) {
            return new Owner("F", "F1", "F1 V 级阶梯", "/network/v-rank");
        }
        if (key.startsWith("wallet.dual-ledger.")) {
            return new Owner("B", "B1", "B1 双账本健康", "/overview/dual-ledger");
        }
        if (key.startsWith("wallet.exchange.")) {
            return new Owner("G", "G2", "G2 兑换风控", "/finance-products/exchange");
        }
        if (key.startsWith("wallet.nex_market.")) {
            return new Owner("G", "G3", "G3 NEX 行情", "/finance-products/market");
        }
        if (key.startsWith("withdrawal.")) {
            return new Owner("D", "D5", "D5 提现参数", "/finance/params");
        }
        throw new IllegalStateException("A5_OWNER_MAPPING_MISSING:" + keyValue);
    }

    private String displayName(String key, Owner owner) {
        if ("feature.ops.maintenanceBanner".equals(key)) {
            return "维护公告横幅";
        }
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
