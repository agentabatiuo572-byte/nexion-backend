package ffdd.opsconsole.content.application;

import ffdd.opsconsole.content.domain.NovaOptionView;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Server-owned allowlist for Nova business fact adapters. */
final class NovaRuntimeAdapterCatalog {
    private static final String PREFIX = "a4:";
    private static final Map<String, RuntimeSource> FIXED = fixed();
    private static final Map<String, RuntimeSource> GENERIC = generic();
    private static final Map<String, String> FIXED_EVENT_LABELS = Map.of(
            "auth.register_completed", "完成注册",
            "device.upgrade_recommended", "推荐设备升级",
            "earnings.credited", "收益到账",
            "tradein.eligible", "符合以旧换新资格",
            "event.reward_claimable", "活动奖励可领取",
            "nova.wrapped_ready", "用户回顾已就绪",
            "quest.monthly_lock_ready", "月度任务锁定已就绪",
            "quest.grace_started", "任务宽限期开始",
            "quest.expired", "任务已过期",
            "quest.weekly_refreshed", "每周任务已刷新");

    private NovaRuntimeAdapterCatalog() {
    }

    static Map<String, RuntimeSource> fixedSources() {
        return FIXED;
    }

    static Optional<RuntimeSource> resolve(String channel, String trigger) {
        RuntimeSource fixed = FIXED.get(channel);
        if (fixed != null) return Optional.of(fixed);
        if (trigger == null || !trigger.startsWith(PREFIX)) return Optional.empty();
        return Optional.ofNullable(GENERIC.get(trigger.substring(PREFIX.length()).trim()));
    }

    static boolean isControlledDynamicSource(String trigger) {
        return resolve("", trigger).isPresent();
    }

    static List<NovaOptionView> options() {
        return List.of(
                option("commission.paid", "团队奖励到账"),
                option("referral.bound", "团队推荐关系绑定"),
                option("staking.opened", "质押仓位创建"),
                option("staking.claimed", "质押收益领取"),
                option("staking.early_withdrawn", "质押提前退出"),
                option("genesis.purchased", "Genesis 购买"),
                option("market.curve_advanced", "市场曲线推进（广播）"));
    }

    /** B79: retired channels stay in history but can never be enabled again. */
    public static final java.util.Set<String> RETIRED_CHANNELS = java.util.Set.of("dailySummary");

    static Map<String, String> eventDisplayNames(List<String> events) {
        Map<String, String> labels = new LinkedHashMap<>(FIXED_EVENT_LABELS);
        options().forEach(option -> labels.put(option.value().substring(PREFIX.length()), option.label()));
        Map<String, String> result = new LinkedHashMap<>();
        for (String event : events) {
            String label = labels.get(event);
            if (label != null) result.put(event, label);
        }
        return result;
    }

    private static NovaOptionView option(String event, String label) {
        return new NovaOptionView(PREFIX + event, label);
    }

    private static Map<String, RuntimeSource> fixed() {
        Map<String, RuntimeSource> sources = new LinkedHashMap<>();
        sources.put("welcome", targeted("auth.register_completed"));
        sources.put("market", broadcast("market.curve_advanced"));
        sources.put("upgrade", targeted("device.upgrade_recommended"));
        sources.put("tradein", targeted("tradein.eligible"));
        sources.put("eventClaim", targeted("event.reward_claimable"));
        sources.put("wrapped", targeted("nova.wrapped_ready"));
        sources.put("taskLockMonthly", targeted("quest.monthly_lock_ready"));
        sources.put("quest", targeted("quest.grace_started", "quest.expired", "quest.weekly_refreshed"));
        sources.put("team_event", targeted("referral.bound", "commission.paid"));
        sources.put("staking_event", targeted("staking.opened", "staking.claimed", "staking.early_withdrawn"));
        sources.put("market_event", broadcast("market.curve_advanced"));
        return Map.copyOf(sources);
    }

    private static Map<String, RuntimeSource> generic() {
        Map<String, RuntimeSource> sources = new LinkedHashMap<>();
        sources.put("commission.paid", targeted("commission.paid"));
        sources.put("referral.bound", targeted("referral.bound"));
        sources.put("staking.opened", targeted("staking.opened"));
        sources.put("staking.claimed", targeted("staking.claimed"));
        sources.put("staking.early_withdrawn", targeted("staking.early_withdrawn"));
        sources.put("genesis.purchased", targeted("genesis.purchased"));
        sources.put("market.curve_advanced", broadcast("market.curve_advanced"));
        return Map.copyOf(sources);
    }

    private static RuntimeSource targeted(String... events) {
        return new RuntimeSource(List.of(events), true);
    }

    private static RuntimeSource broadcast(String... events) {
        return new RuntimeSource(List.of(events), false);
    }

    record RuntimeSource(List<String> eventNames, boolean targeted) {
    }
}
