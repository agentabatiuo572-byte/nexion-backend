package ffdd.opsconsole.platform.dto;

import ffdd.opsconsole.shared.audit.AuditLogRecord;
import ffdd.opsconsole.shared.audit.AuditStatsBucket;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.fasterxml.jackson.annotation.JsonProperty;

public record EventCenterOverview(
        EventCenterStats stats,
        List<EventFamily> eventFamilies,
        List<String> registeredDomains,
        List<String> pendingDomains,
        List<String> sunsetDomains,
        List<EventCommonField> commonFields,
        List<EventDimensionParam> dimensionParams,
        List<EventKpiFormula> kpiFormulas,
        List<EventSchemaRegistration> schemaRegistrations,
        List<EventDomainExtensionBatch> domainExtensions,
        List<AuditLogRecord> recentLogs,
        List<AuditStatsBucket> topActions,
        List<String> guardrails) {

    private static final Map<String, String> DOMAIN_LABELS = Map.ofEntries(
            Map.entry("app", "应用"), Map.entry("auth", "账户认证"), Map.entry("referral", "邀请推荐"),
            Map.entry("onboarding", "新手引导"), Map.entry("store", "商城"), Map.entry("checkout", "订单支付"),
            Map.entry("device", "设备"), Map.entry("earnings", "收益"), Map.entry("wallet", "钱包"),
            Map.entry("withdraw", "提现"), Map.entry("commission", "团队奖励"), Map.entry("staking", "质押"),
            Map.entry("exchange", "兑换"), Map.entry("genesis", "创世节点"), Map.entry("trial", "试用"),
            Map.entry("quest", "任务"), Map.entry("daily", "签到"), Map.entry("nova", "消息推送"),
            Map.entry("phase", "运营阶段"), Map.entry("risk", "风控"), Map.entry("admin", "运营后台"),
            Map.entry("event", "活动"), Map.entry("milestone", "里程碑"), Map.entry("nex", "平台资产"),
            Map.entry("repurchase", "复购"), Map.entry("disclosure", "风险披露"), Map.entry("content", "内容"),
            Map.entry("notification", "通知"), Map.entry("learn", "课程"));
    private static final Map<String, String> EVENT_LABELS = Map.ofEntries(
            Map.entry("app.session_started", "应用会话开始"), Map.entry("app.dau", "应用日活跃"),
            Map.entry("auth.register_started", "开始注册"), Map.entry("auth.register_completed", "注册完成"),
            Map.entry("device.first_yield_received", "首笔设备收益到账"), Map.entry("store.viewed", "浏览商城"),
            Map.entry("checkout.completed", "支付完成"), Map.entry("trial.started", "开始试用"),
            Map.entry("referral.invite_sent", "发出邀请"), Map.entry("referral.bound", "绑定邀请关系"),
            Map.entry("wallet.topup_created", "创建充值单"), Map.entry("wallet.topup_confirmed", "充值确认"),
            Map.entry("wallet.topup_failed", "充值失败"), Map.entry("withdraw.submitted", "提交提现"),
            Map.entry("withdraw.risk_held", "提现风控暂挂"), Map.entry("withdraw.approved", "提现批准"),
            Map.entry("withdraw.confirmed", "提现确认"), Map.entry("staking.opened", "质押开仓"),
            Map.entry("exchange.swapped", "兑换完成"), Map.entry("genesis.purchased", "购买创世节点"),
            Map.entry("daily.checkin", "每日签到"), Map.entry("quest.completed", "任务完成"),
            Map.entry("nova.push_sent", "发送推送"), Map.entry("nova.push_clicked", "点击推送"),
            Map.entry("risk.multi_account_flagged", "发现多账户关联"),
            Map.entry("risk.arbitrage_suspected", "发现套利嫌疑"), Map.entry("risk.trial_cycle_detected", "发现循环试用"),
            Map.entry("risk.withdraw_held", "提现风控拦截"), Map.entry("risk.score_updated", "更新风险评分"),
            Map.entry("auth.login_locked", "登录锁定"), Map.entry("phase.transitioned", "运营阶段切换"),
            Map.entry("phase.dial_changed", "修改运营参数"), Map.entry("admin.emergency_playbook_executed", "执行应急预案"));

    @JsonProperty(value = "domainDisplayNames", access = JsonProperty.Access.READ_ONLY)
    public Map<String, String> domainDisplayNames() {
        Map<String, String> labels = new LinkedHashMap<>();
        for (List<String> domains : List.of(registeredDomains, pendingDomains)) {
            for (String code : domains) {
                if (code != null && DOMAIN_LABELS.containsKey(code)) labels.put(code, DOMAIN_LABELS.get(code));
            }
        }
        return labels;
    }

    public record EventCenterStats(
            String todayEvents,
            long todayAuditEvents,
            int registeredDomains,
            int pendingDomains,
            int batchDone,
            int batchTotal,
            String schemaVersion) {
    }

    public record EventFamily(
            String key,
            String title,
            String sub,
            String sample,
            String serverAuth,
            String todayCount,
            List<EventDetailRow> events) {
    }

    public record EventDetailRow(String item, String desc) {
    }

    public record EventCommonField(String key, String name, String sub, String value) {
    }

    public record EventDimensionParam(String key, String name, String sub, String value, boolean locked) {
    }

    public record EventKpiFormula(int n, String kpi, String formula) {
    }

    public record EventSchemaRegistration(
            String eventName,
            String ownerDomain,
            String familyKey,
            String producer,
            String consumers,
            String properties,
            boolean serverAuthoritative,
            String samplingPolicy,
            String version,
            String updatedAt,
            String lifecycleState,
            long lifecycleVersion) {
        @JsonProperty(value = "displayName", access = JsonProperty.Access.READ_ONLY)
        public String displayName() {
            return eventName == null ? null : EVENT_LABELS.get(eventName);
        }

        @JsonProperty(value = "ownerDisplayName", access = JsonProperty.Access.READ_ONLY)
        public String ownerDisplayName() {
            return ownerDomain == null ? null : DOMAIN_LABELS.get(ownerDomain);
        }
    }

    public record EventLifecycleView(String eventName, String state, long version, String updatedAt) {
    }

    public record EventDomainExtensionBatch(
            String id,
            String title,
            String state,
            String proposer,
            String impact,
            List<EventDomainItem> newDomains,
            List<EventDetailRow> details) {
    }

    public record EventDomainItem(String name, boolean n) {
    }
}
